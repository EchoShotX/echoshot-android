package com.echoshot.app.mp4detact.tracking

import android.util.Log
import org.opencv.core.Rect
import org.opencv.core.Point
import com.echoshot.app.mp4detact.data.*
import com.echoshot.app.mp4detact.tracking.Association.maxIoUWithOthers
import com.echoshot.app.mp4detact.tracking.Association.top2ByIoU
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * 겹침(AMBIGUOUS) 상태에서 매 프레임 얼굴 비교를 강제 수행하는 상태머신.
 * - TRACKING: 하드 IoU 게이트로 점프 억제. 첫 디텍션 프레임은 강제 선택 1회.
 * - AMBIGUOUS: 분리 여부와 상관없이 top2 후보에 대해 즉시 REID 시도(로그 기록).
 * - 실패 프레임은 track=null 로깅. 다음 프레임 기준은 lastGoodRect 유지.
 */
class StateMachine(
    private val cfg: HybridConfig,
    private val imgW: Int,
    private val imgH: Int,
    private val poseHead: (Rect) -> Keypoints,
    private val faceEmbed: (Rect) -> FaceVec?
) {
    companion object { private const val TAG = "StateMachine" }
    private var track: TrackBox? = null
    private var state: TrackState = TrackState.LOST

    // 표시/로깅용 현재 프레임 박스(성공 시만 설정, 실패 시 null)
    private var currentRect: Rect? = null
    // 다음 프레임 매칭 기준으로 쓰는 최근 성공 박스
    private var lastGoodRect: Rect? = null
    // 첫 디텍션 프레임 강제 선택 여부
    private var didFirstForcePick: Boolean = false
    
    // 적응형 IoU 게이트 (홀드 누적 완화)
    private var adaptiveIoUThreshold: Float = cfg.adaptiveIoUBase  // 적응형 IoU 임계치

    // 기준 임베딩(E_ref)
    private var refFace: FloatArray? = null

    // AMBIGUOUS 단계에서 직전 top2
    private var ambiPrevTop2: List<Pair<Int, Float>> = emptyList()

    // 점프 방지 카운터
    private var lowIoUStreak: Int = 0

    private val imgDiag: Float = hypot(imgW.toDouble(), imgH.toDouble()).toFloat()

    // 프레임 단위 얼굴 임베딩 로깅 버퍼
    private var frameFaceLogs = mutableListOf<Map<String, Any?>>()

    fun isInitialized(): Boolean = (track != null && state != TrackState.LOST)

    fun open() { /* no-op */ }

    fun close() {
        track = null
        state = TrackState.LOST
        refFace = null
        ambiPrevTop2 = emptyList()
        lowIoUStreak = 0
        currentRect = null
        lastGoodRect = null
        didFirstForcePick = false
        frameFaceLogs.clear()
    }

    /** 기준 얼굴 임베딩 설정 */
    fun setReferenceFace(faceEmbed: FloatArray) {
        refFace = faceEmbed.copyOf()
    }

    /** 여러 디텍션 중에서 얼굴 유사도가 가장 높은 것 선택 */
    fun performReid(detections: List<Detection>): Detection? {
        if (refFace == null) return null
        
        var bestDetection: Detection? = null
        var bestScore = -1f
        
        for (det in detections) {
            try {
                // 각 디텍션 영역에서만 pose estimation 수행
                val detRect = det.toRect()
                val headBox = poseHead(detRect)?.head ?: continue
                val faceEmbed = faceEmbed(headBox) ?: continue
                
                // 기준 얼굴과 비교
                val similarity = cosineSimilarity(refFace!!, faceEmbed.v)
                
                // 로깅
                frameFaceLogs.add(mapOf(
                    "det_idx" to detections.indexOf(det),
                    "det_rect" to "${detRect.x},${detRect.y},${detRect.width},${detRect.height}",
                    "head_box" to "${headBox.x},${headBox.y},${headBox.width},${headBox.height}",
                    "similarity" to similarity,
                    "is_best" to (similarity > bestScore)
                ))
                
                if (similarity > bestScore) {
                    bestScore = similarity
                    bestDetection = det
                }
            } catch (e: Exception) {
                // 개별 디텍션 실패는 무시하고 계속
                continue
            }
        }
        
        return if (bestScore > cfg.reidCosThresh) bestDetection else null
    }

    /** 코사인 유사도 계산 */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        
        var dot = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val norm = kotlin.math.sqrt(normA * normB)
        return if (norm > 0f) dot / norm else 0f
    }

    fun initWith(target: Rect, frameIdx: Int): TrackLogEntry {
        val clamped = clampRect(target, imgW, imgH)
        track = TrackBox(clamped, state = TrackState.TRACKING, lastUpdateFrame = frameIdx)
        state = TrackState.TRACKING
        currentRect = clamped
        lastGoodRect = clamped
        didFirstForcePick = false
        refreshRefEmbedding(clamped, ctx = "init_refresh_ref", isInitial = true)
        return log(frameIdx, 0L, emptyList(), note = mapOf("init" to true))
    }


    fun step(
        dets: List<Detection>,
        frameIdx: Int,
        ptsMs: Long
    ): TrackLogEntry {
        frameFaceLogs = mutableListOf()

        val t = track
        if (t == null) {
            state = TrackState.LOST
            currentRect = null
            return log(frameIdx, ptsMs, dets, note = mapOf("no_track" to true))
        }

        val prevForMatch: Rect = (lastGoodRect ?: t.rect)

        when (state) {
            TrackState.TRACKING -> {
                if (dets.isEmpty()) {
                    t.rect = t.predict(imgW, imgH)
                    t.lostCount++
                    currentRect = null
                    if (t.lostCount > cfg.keepVelocityFrames) {
                        state = TrackState.LOST
            return log(frameIdx, ptsMs, dets, note = mapOf("to" to "LOST", "reason" to "no_detections"))
        }
                    return log(frameIdx, ptsMs, dets, note = mapOf("hold" to true, "reason" to "no_detections"))
                } else {
                    if (!didFirstForcePick) {
                        val best = dets.maxByOrNull { d -> iou(prevForMatch, d.toRect()) }!!.toRect()
                        val i = iou(prevForMatch, best)
                        t.updateWith(best, frameIdx)
                        t.lostCount = 0
                        currentRect = best
                        lastGoodRect = best
                        didFirstForcePick = true
                        // ★ 변경: 첫 강제 픽에서 즉시 ref 임베딩 생성
                        val refOk = refreshRefEmbedding(best, ctx = "first_force_pick_refresh_ref")
                        return log(
                            frameIdx, ptsMs, dets,
                            note = mapOf(
                                "first_frame_force_pick" to true,
                                "iou" to "%.3f".format(i),
                                "ref_set" to refOk
                            )
                        )
                    }

                    val (chosen, reason) = chooseFromDetectionsHardGate(prevForMatch, dets)
                    if (chosen != null) {
                        t.updateWith(chosen, frameIdx)
                        t.lostCount = 0
                        lowIoUStreak = 0
                        adaptiveIoUThreshold = cfg.adaptiveIoUBase  // 성공 시 임계치 리셋
                        currentRect = chosen
                        lastGoodRect = chosen
                    } else {
                        lowIoUStreak++
                        currentRect = null
                        return log(
                            frameIdx, ptsMs, dets,
                            note = mapOf("hold" to true, "reason" to reason, "low_iou_streak" to lowIoUStreak)
                        )
                    }
                }

                // AMBIGUOUS 판단: 디텍션들끼리 비교해서 1등과 2등의 IoU 차이 확인
                if (dets.size >= 2) {
                    val detsWithIoU = dets.map { d -> 
                        val iou = iou(currentRect ?: t.rect, d.toRect())
                        d to iou 
                    }.sortedByDescending { it.second }
                    
                    val bestIoU = detsWithIoU[0].second
                    val secondBestIoU = detsWithIoU[1].second
                    val iouGap = bestIoU - secondBestIoU
                    
                    Log.d(TAG, "[AMBIGUOUS] Best IoU: ${"%.3f".format(bestIoU)}, 2nd: ${"%.3f".format(secondBestIoU)}, gap: ${"%.3f".format(iouGap)}")
                    
                    if (iouGap < cfg.ambiguousIoUGap) {
                    state = TrackState.AMBIGUOUS
                        ambiPrevTop2 = top2ByIoU(currentRect ?: t.rect, dets)
                        return log(frameIdx, ptsMs, dets, note = mapOf(
                            "to" to "AMBIGUOUS",
                            "reason" to "small_iou_gap",
                            "best_iou" to "%.3f".format(bestIoU),
                            "second_iou" to "%.3f".format(secondBestIoU),
                            "gap" to "%.3f".format(iouGap)
                        ))
                    }
                }

                // 주기적 참조 임베딩 업데이트 (초기 30프레임은 더 자주)
                val shouldRefresh = if (frameIdx <= cfg.initialLearningFrames) {
                    frameIdx % cfg.initialLearningInterval == 0  // 초기 30프레임: 3프레임마다
                } else {
                    frameIdx % cfg.poseStride == 0  // 이후: 3프레임마다
                }
                
                if (shouldRefresh) {
                    val isInitial = frameIdx <= cfg.initialLearningFrames
                    if (refreshRefEmbedding(currentRect ?: t.rect, ctx = "refresh_ref", isInitial = isInitial)) {
                        return log(frameIdx, ptsMs, dets, note = mapOf(
                            "face_refresh" to true,
                            "initial_learning" to isInitial
                        ))
                    }
                }
            }

            TrackState.AMBIGUOUS -> {
                // 1) 기본 추적 업데이트(하드게이트)
                if (dets.isEmpty()) {
                    t.rect = t.predict(imgW, imgH)
                    currentRect = null
                    return log(frameIdx, ptsMs, dets, note = mapOf("hold" to true, "reason" to "no_detections"))
                } else {
                    val (chosen, _) = chooseFromDetectionsHardGate(prevForMatch, dets)
                    if (chosen != null) {
                        t.updateWith(chosen, frameIdx)
                        lowIoUStreak = 0
                        adaptiveIoUThreshold = cfg.adaptiveIoUBase  // 성공 시 임계치 리셋
                        currentRect = chosen
                        lastGoodRect = chosen
                    } else {
                        lowIoUStreak++
                        currentRect = null
                    }
                }

                // 2) ★ 변경: IoU-게이트 기반 후보 선정 + refFace와 유사도 비교(1~2개 모두 처리)
                val baseRect = lastGoodRect ?: t.rect

                val candWithIoU: List<Pair<Int, Float>> = dets
                    .mapIndexed { idx, d -> idx to iou(baseRect, d.toRect()) }
                    .sortedByDescending { it.second }

                // AMBIGUOUS 상태에서도 IoU 차이 확인
                val bestIoU = if (candWithIoU.isNotEmpty()) candWithIoU[0].second else 0f
                val secondBestIoU = if (candWithIoU.size >= 2) candWithIoU[1].second else 0f
                val iouGap = bestIoU - secondBestIoU
                
                Log.d(TAG, "[AMBIGUOUS] Best IoU: ${"%.3f".format(bestIoU)}, 2nd: ${"%.3f".format(secondBestIoU)}, gap: ${"%.3f".format(iouGap)}")
                
                // IoU 차이가 충분히 벌어졌으면 TRACKING으로 복귀
                if (iouGap >= cfg.ambiguousIoUGap) {
                    state = TrackState.TRACKING
                    val bestDet = dets[candWithIoU[0].first]
                    t.updateWith(bestDet.toRect(), frameIdx)
                    currentRect = bestDet.toRect()
                    lastGoodRect = bestDet.toRect()
                    return log(frameIdx, ptsMs, dets, note = mapOf(
                        "to" to "TRACKING", 
                        "reason" to "sufficient_iou_gap",
                        "best_iou" to "%.3f".format(bestIoU),
                        "second_iou" to "%.3f".format(secondBestIoU),
                        "gap" to "%.3f".format(iouGap)
                    ))
                }
                
                val gated = candWithIoU.filter { it.second >= cfg.overlapAmbiguous }
                val nowTop2 = when {
                    gated.size >= 2 -> gated.take(2)
                    candWithIoU.isNotEmpty() -> candWithIoU.take(2) // 보정: 최소 top2 확보
                    else -> emptyList()
                }

                // 참조가 없다면 즉시 갱신 시도
                if (refFace == null) {
                    refreshRefEmbedding(baseRect, ctx = "amb_refresh_ref")
                }

                if (refFace != null && nowTop2.isNotEmpty()) {
                    val idxA = nowTop2[0].first
                    val candA = dets[idxA].toRect()
                    
                    // 하드 게이트: 단일 디텍션이고 IoU ≥ 0.93이면 ReID 호출 금지
                    val iouWithTrack = iou(baseRect, candA)
                    if (nowTop2.size == 1 && iouWithTrack >= cfg.reidHardGateIoU) {
                        Log.d(TAG, "[REID] Hard gate: single detection, IoU=${"%.3f".format(iouWithTrack)} >= ${cfg.reidHardGateIoU}, skipping ReID")
                        t.updateWith(candA, frameIdx)
                        currentRect = candA
                        lastGoodRect = candA
                        return log(
                            frameIdx, ptsMs, dets,
                            note = mapOf(
                                "amb_reid" to "hard_gate_skip",
                                "iou" to "%.3f".format(iouWithTrack),
                                "reason" to "single_detection_high_iou"
                            )
                        )
                    }
                    
                    val scA = reidScoreWithLog(candA, refFace!!, ctx = "amb_reid_A")

                    var pick: Rect? = null
                    var scB: Float? = null
                    if (nowTop2.size >= 2) {
                        val idxB = nowTop2[1].first
                        val candB = dets[idxB].toRect()
                        scB = reidScoreWithLog(candB, refFace!!, ctx = "amb_reid_B")

                        // 마진 규칙: cos_A - cos_B ≥ 0.20이면 즉시 자기 유지
                        val margin = (cfg.reidMargin.takeIf { it > 0f } ?: 0.02f)
                        val strongMargin = cfg.reidMarginThreshold
                        
                        pick = when {
                            scA >= cfg.reidCosThresh && (scA - (scB ?: -1f)) >= strongMargin -> {
                                Log.d(TAG, "[REID] Strong margin: ${"%.3f".format(scA - (scB ?: -1f))} >= ${strongMargin}, keeping A")
                                candA
                            }
                            scA >= cfg.reidCosThresh && (scA - (scB ?: -1f)) >= margin -> candA
                            (scB ?: -1f) >= cfg.reidCosThresh && ((scB ?: -1f) - scA) >= strongMargin -> {
                                Log.d(TAG, "[REID] Strong margin: ${"%.3f".format((scB ?: -1f) - scA)} >= ${strongMargin}, switching to B")
                                candB
                            }
                            (scB ?: -1f) >= cfg.reidCosThresh && ((scB ?: -1f) - scA) >= margin -> candB
                            else -> null
                        }
                    } else {
                        // 후보 1개뿐이면 동일인 검증만
                        if (scA >= cfg.reidCosThresh) pick = candA
                    }

                    if (pick != null) {
                        t.updateWith(pick, frameIdx)
                        currentRect = pick
                        lastGoodRect = pick
                        return log(
                            frameIdx, ptsMs, dets,
                            note = buildMap {
                                put("amb_reid", "picked")
                                put(
                                    "scores",
                                    if (scB != null) mapOf("A" to scA, "B" to scB) else mapOf("A" to scA)
                                )
                                put("picked_tag", when (pick) { candA -> "A"; else -> "B" })
                            }
                        )
                    } else {
                        return log(
                            frameIdx, ptsMs, dets,
                            note = buildMap {
                                put("amb_reid", "no_switch")
                                put(
                                    "scores",
                                    if (scB != null) mapOf("A" to scA, "B" to scB) else mapOf("A" to scA)
                                )
                            }
                        )
                    }
                } else {
                    // 후보 부족/참조 없음
                    ambiPrevTop2 = nowTop2
                }
            }

            TrackState.REID -> {
                // 안전망: dets 있으면 하드게이트로 업데이트 시도
                if (dets.isNotEmpty()) {
                    val (chosen, _) = chooseFromDetectionsHardGate(prevForMatch, dets)
                    if (chosen != null) {
                        track?.updateWith(chosen, frameIdx)
                        lowIoUStreak = 0
                        adaptiveIoUThreshold = cfg.adaptiveIoUBase  // 성공 시 임계치 리셋
                        currentRect = chosen
                        lastGoodRect = chosen
                    } else {
                        lowIoUStreak++
                        currentRect = null
                    }
                } else {
                    currentRect = null
                }
                state = TrackState.TRACKING
            }

            TrackState.LOST -> {
                if (dets.isEmpty()) {
                    currentRect = null
                    return log(frameIdx, ptsMs, dets)
                }
                val prev = lastGoodRect ?: t.rect
                val revived = reviveFromDetectionsGated(dets, prev)
                if (revived != null) {
                    track = TrackBox(revived, state = TrackState.TRACKING, lastUpdateFrame = frameIdx)
                    state = TrackState.TRACKING
                    lowIoUStreak = 0
                    currentRect = revived
                    lastGoodRect = revived
                    didFirstForcePick = true
                    return log(frameIdx, ptsMs, dets, note = mapOf("to" to "TRACKING_from_LOST"))
                } else {
                    currentRect = null
                }
            }
        }

        return log(frameIdx, ptsMs, dets)
    }

    private fun hasSplit(
        prevTop2: List<Pair<Int, Float>>,
        nowTop2: List<Pair<Int, Float>>,
        dropIoU: Float
    ): Boolean {
        if (prevTop2.size < 2 || nowTop2.size < 2) return false
        val prevMin = minOf(prevTop2[0].second, prevTop2[1].second)
        val nowMax = maxOf(nowTop2[0].second, nowTop2[1].second)
        return (prevMin >= cfg.overlapAmbiguous) && (nowMax < dropIoU)
    }

    /** ref 임베딩 갱신 + 얼굴 ROI 로깅 */
    private fun refreshRefEmbedding(trackRect: Rect, ctx: String, isInitial: Boolean = false): Boolean {
        val k = poseHead(trackRect)
        val head = k.head
        if (head == null) {
            frameFaceLogs.add(mapOf("ctx" to ctx, "ok" to false, "reason" to "no_head_from_pose", "from" to trackRect))
            return false
        }
        val f = faceEmbed(head)
        if (f == null) {
            frameFaceLogs.add(mapOf("ctx" to ctx, "ok" to false, "reason" to "face_embed_fail", "box" to head))
            return false
        }
        
        // 초기 학습과 정상 학습에 따른 다른 알파값 사용
        val alpha = if (isInitial) cfg.faceEmaAlphaInitial else cfg.faceEmaAlpha
        if (refFace == null) refFace = f.v.copyOf() else emaInPlace(refFace!!, f.v, alpha)
        
        frameFaceLogs.add(mapOf("ctx" to ctx, "ok" to true, "box" to head, "alpha" to alpha))
        return true
    }

    private val lastReidHeadBox = HashMap<String, Rect?>()

    /** REID 점수 계산 + 얼굴 ROI 로깅 */
    private fun reidScoreWithLog(box: Rect, ref: FloatArray, ctx: String): Float {
        val kp = poseHead(box)
        val head = kp.head
        if (head == null) {
            frameFaceLogs.add(mapOf("ctx" to ctx, "ok" to false, "reason" to "no_head_from_pose", "from" to box))
            lastReidHeadBox[ctx] = null
            return -1f
        }
        val fv = faceEmbed(head)
        if (fv == null) {
            frameFaceLogs.add(mapOf("ctx" to ctx, "ok" to false, "reason" to "face_embed_fail", "box" to head))
            lastReidHeadBox[ctx] = head
            return -1f
        }
        val cos = cosineSim(ref, fv.v)
        frameFaceLogs.add(mapOf("ctx" to ctx, "ok" to true, "box" to head, "cos" to cos))
        lastReidHeadBox[ctx] = head
        return cos
    }

    /** LOST 재획득(관대한): 모든 디텍션을 REID 평가 + 로깅 */
    private fun reviveFromDetectionsGated(dets: List<Detection>, prev: Rect): Rect? {
        val r = refFace ?: return null
        
        // 더 관대한 게이팅: IoU 0.1 이상이거나 중심 거리가 허용 범위 내
        val gated = dets.map { it.toRect() }.filter { cand ->
            val iouPrev = iou(prev, cand)
            val centerOk = (centerDist(prev, cand) / imgDiag) <= (cfg.centerGateRatio * 3f) // 3배 더 관대
            (iouPrev >= 0.1f) || centerOk // IoU 0.1 이상으로 완화
        }
        if (gated.isEmpty()) return null

        var best: Rect? = null
        var bestSc = -1f
        for ((idx, c) in gated.withIndex()) {
            val sc = reidScoreWithLog(c, r, ctx = "revive[$idx]")
            if (sc > bestSc) { bestSc = sc; best = c }
        }
        
        // 더 관대한 임계값: 0.5 이상이면 복구 허용
        return if (bestSc >= 0.5f) best else null
    }

    private fun chooseFromDetectionsHardGate(prev: Rect, dets: List<Detection>): Pair<Rect?, String> {
        if (dets.isEmpty()) return null to "no_detections"
        
        // 강화된 매칭: IoU + 면적 유사도 + 위치 유사도
        var bestRect: Rect? = null
        var bestScore = -1f
        var bestIoU = -1f
        var secondBestScore = -1f
        var secondBestRect: Rect? = null
        
        // 디텍션별 유사도 로깅용
        val detectionSimilarities = mutableListOf<Map<String, Any?>>()
        
        for ((idx, d) in dets.withIndex()) {
            val r = d.toRect()
            val iouVal = iou(prev, r)
            
            // 면적 유사도
            val areaSimilarity = calculateAreaSimilarity(prev, r)
            // 위치 유사도 (중심점 기반)
            val positionSimilarity = calculatePositionSimilarity(prev, r)
            // 모서리 위치 유사도 (새로운 지표)
            val cornerSimilarity = calculateCornerSimilarity(prev, r)
            
            // IoU 중심으로 되돌리고 끝점 좌표 유사도 추가
            val score = (0.7f * iouVal) + (0.1f * areaSimilarity) + (0.1f * positionSimilarity) + (0.1f * cornerSimilarity)
            
            // 디텍션별 유사도 로깅
            detectionSimilarities.add(mapOf(
                "det_idx" to idx,
                "det_rect" to "${r.x},${r.y},${r.width},${r.height}",
                "iou" to "%.3f".format(iouVal),
                "area_sim" to "%.3f".format(areaSimilarity),
                "pos_sim" to "%.3f".format(positionSimilarity),
                "corner_sim" to "%.3f".format(cornerSimilarity),
                "total_score" to "%.3f".format(score),
                "is_best" to (score > bestScore)
            ))
            
            if (score > bestScore) {
                secondBestScore = bestScore
                secondBestRect = bestRect
                bestScore = score
                bestRect = r
                bestIoU = iouVal
            } else if (score > secondBestScore) {
                secondBestScore = score
                secondBestRect = r
            }
        }
        
        // 유사도 로깅을 frameFaceLogs에 추가
        frameFaceLogs.add(mapOf(
            "ctx" to "detection_similarities",
            "track_rect" to "${prev.x},${prev.y},${prev.width},${prev.height}",
            "similarities" to detectionSimilarities
        ))
        
        // 적응형 IoU 게이트: 연속 홀드가 쌓일수록 임계치 완화
        val currentThreshold = max(cfg.adaptiveIoUFloor, cfg.adaptiveIoUBase - (lowIoUStreak * cfg.adaptiveIoUDecay))
        adaptiveIoUThreshold = currentThreshold
        
        Log.d(TAG, "[ADAPTIVE_IOU] Streak: $lowIoUStreak, Threshold: ${"%.3f".format(currentThreshold)}, Best IoU: ${"%.3f".format(bestIoU)}")
        
        if (bestIoU < currentThreshold) {
            return null to "adaptive_gate_blocked(iou=${"%.3f".format(bestIoU)} < ${"%.3f".format(currentThreshold)}, streak=$lowIoUStreak)"
        }
        
        // 스마트 얼굴 비교 트리거: 애매한 경우만 얼굴 비교 수행
        val secondBestIoU = if (secondBestRect != null) iou(prev, secondBestRect!!) else 0f
        val iouGap = bestIoU - secondBestIoU
        val trackSizeRatio = calculateAreaSimilarity(prev, bestRect!!)
        
        val shouldDoFaceComparison = refFace != null && 
            bestIoU <= cfg.smartReidMaxIoU &&  // 최고 IoU가 설정값 이하
            iouGap <= cfg.smartReidMaxGap &&   // 1등과 2등 IoU 차이가 설정값 이하
            trackSizeRatio <= cfg.smartReidMaxSizeRatio  // 트랙 박스 크기 유사도가 설정값 이하
        
        Log.d(TAG, "[SMART_REID] Best IoU: ${"%.3f".format(bestIoU)} (≤${cfg.smartReidMaxIoU}), Gap: ${"%.3f".format(iouGap)} (≤${cfg.smartReidMaxGap}), Size Ratio: ${"%.3f".format(trackSizeRatio)} (≤${cfg.smartReidMaxSizeRatio}), Do Face: $shouldDoFaceComparison")
        
        // ReID 호출 억제: 단일 디텍션이고 IoU ≥ 0.93이면 얼굴 비교 생략
        if (dets.size == 1 && bestIoU >= cfg.reidHardGateIoU) {
            return bestRect to "reid_suppressed_single(iou=${"%.3f".format(bestIoU)} >= ${cfg.reidHardGateIoU})"
        }
        
        // 스마트 얼굴 비교: 애매한 경우만 수행
        if (shouldDoFaceComparison && secondBestRect != null) {
            val faceScore1 = performFaceComparison(bestRect!!)
            val faceScore2 = performFaceComparison(secondBestRect)
            
            // 마진 규칙: cos_A - cos_B ≥ 0.20이면 즉시 자기 유지
            val faceMargin = faceScore1 - faceScore2
            Log.d(TAG, "[FACE_COMPARISON] Score1: ${"%.3f".format(faceScore1)}, Score2: ${"%.3f".format(faceScore2)}, Margin: ${"%.3f".format(faceMargin)}")
            
            if (faceMargin >= cfg.reidMarginThreshold) {
                return bestRect to "face_strong_margin(score1=${"%.3f".format(faceScore1)}, score2=${"%.3f".format(faceScore2)}, margin=${"%.3f".format(faceMargin)})"
            }
            
            if (faceScore2 > faceScore1) {
                Log.d(TAG, "[FACE_SWITCH] Switching to second candidate due to higher face score")
                return secondBestRect to "face_switched(score1=${"%.3f".format(faceScore1)}, score2=${"%.3f".format(faceScore2)})"
            } else {
                Log.d(TAG, "[FACE_KEEP] Keeping first candidate due to higher face score")
            }
        }
        
        return bestRect to "ok(score=${"%.3f".format(bestScore)}, iou=${"%.3f".format(bestIoU)})"
    }
    
    /** 면적 유사도 계산 (0..1) */
    private fun calculateAreaSimilarity(a: Rect, b: Rect): Float {
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val minArea = min(areaA, areaB).toFloat()
        val maxArea = max(areaA, areaB).toFloat()
        return if (maxArea > 0f) minArea / maxArea else 0f
    }

    /** 위치 유사도 계산 (0..1) - 중심점 기반 */
    private fun calculatePositionSimilarity(a: Rect, b: Rect): Float {
        val center1X = a.x + a.width / 2.0
        val center1Y = a.y + a.height / 2.0
        val center2X = b.x + b.width / 2.0
        val center2Y = b.y + b.height / 2.0
        
        // 중심점 거리 계산
        val distance = kotlin.math.sqrt(
            ((center1X - center2X).pow(2) + (center1Y - center2Y).pow(2))
        ).toFloat()
        
        // 이미지 대각선 길이로 정규화
        val maxDistance = kotlin.math.sqrt((imgW * imgW + imgH * imgH).toDouble()).toFloat()
        val normalizedDistance = distance / maxDistance
        
        // 거리가 가까울수록 높은 유사도 (0~1)
        return 1.0f - normalizedDistance.coerceIn(0f, 1f)
    }
    
    /** 모서리 위치 유사도 계산 (0..1) - 네 모서리 위치 비교 */
    private fun calculateCornerSimilarity(a: Rect, b: Rect): Float {
        val cornerSimilarity = (
            (1f - kotlin.math.abs(a.x - b.x) / max(a.width, b.width).toFloat()) +
            (1f - kotlin.math.abs(a.y - b.y) / max(a.height, b.height).toFloat()) +
            (1f - kotlin.math.abs((a.x + a.width) - (b.x + b.width)) / max(a.width, b.width).toFloat()) +
            (1f - kotlin.math.abs((a.y + a.height) - (b.y + b.height)) / max(a.height, b.height).toFloat())
        ) / 4f
        
        return cornerSimilarity.coerceIn(0f, 1f)
    }
    
    /** 얼굴 비교 수행 */
    private fun performFaceComparison(rect: Rect): Float {
        return try {
            val headBox = poseHead(rect)?.head ?: return -1f
            val faceEmbed = faceEmbed(headBox) ?: return -1f
            cosineSimilarity(refFace!!, faceEmbed.v)
        } catch (e: Exception) {
            -1f
        }
    }

    private fun Detection.toRect(): Rect =
        Rect(x1, y1, (x2 - x1).coerceAtLeast(1), (y2 - y1).coerceAtLeast(1))

    /** 로그에 currentRect 를 기록 + faces 배열 병합 */
    private fun log(
        frame: Int,
        ptsMs: Long,
        dets: List<Detection>,
        note: Map<String, Any?> = emptyMap()
    ): TrackLogEntry {
        val t = track
        val notesMerged: MutableMap<String, Any?> = if (note.isEmpty()) mutableMapOf() else note.toMutableMap()
        if (frameFaceLogs.isNotEmpty()) {
            notesMerged["faces"] = frameFaceLogs
        }
        return TrackLogEntry(
            frame = frame,
            ptsMs = ptsMs,
            state = state.name,
            track = currentRect,
            vx = t?.vx,
            vy = t?.vy,
            dets = dets,
            notes = notesMerged
        )
    }
}
