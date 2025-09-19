# merge_offline_logs.py — detections만 사용 + 정렬 파이프라인 추가
# 1) 프레임 1: 면적 내림차순으로 L1/L2/L3 채우기
# 2) 프레임 ≥2: 직전(또는 마지막 유효) L1/L2/L3와 IoU로 유사도 계산 → threshold(0.8) 넘는 최대값을 L1→L3 순서로 배정(중복 금지)
# 3) 정렬 결과는
#    - sorting_detections: 각 det에 SimilarityL1/L2/L3 필드 포함 (프레임1은 None)
#    - sorted_detections: [det_or_None_for_L1, det_or_None_for_L2, det_or_None_for_L3]
# 4) 크롭 파이프라인은 기존 그대로(원하면 L1/2/3로 변경 가능)

import json
from typing import List, Dict, Any, Optional, Sequence, Tuple
from copy import deepcopy
from math import sqrt
from collections import deque

SIM_THRESH = 0.5  # Similarity(=IoU) 임계값

# ---------- helpers ----------

# --- 추가 helpers: EMA 앵커, L1-정규화 유사도 ---

def _box_from_det(d):
    return {"x1": float(d["x1"]), "y1": float(d["y1"]),
            "x2": float(d["x2"]), "y2": float(d["y2"])}

def _box_ema(anchor: Optional[Dict[str, float]],
             obs: Dict[str, Any],
             alpha: float) -> Dict[str, float]:
    """anchor ← (1-alpha)*anchor + alpha*obs  (alpha in (0,1])"""
    o = _box_from_det(obs)
    if anchor is None:
        return dict(o)
    return {
        "x1": anchor["x1"] * (1 - alpha) + o["x1"] * alpha,
        "y1": anchor["y1"] * (1 - alpha) + o["y1"] * alpha,
        "x2": anchor["x2"] * (1 - alpha) + o["x2"] * alpha,
        "y2": anchor["y2"] * (1 - alpha) + o["y2"] * alpha,
    }

def _l1_similarity_box(
        a: Dict[str, Any],
        b: Optional[Dict[str, Any]],
        *,
        frame_w: Optional[float],
        frame_h: Optional[float],
        mode: str = "frame",  # "frame" | "prevbox" (여기선 앵커 기준이므로 "prevbox"도 의미있음)
        eps: float = 1e-6,
) -> float:
    """좌표 L1 거리(Δx1+Δx2+Δy1+Δy2) 기반 0~1 유사도 (1=완전일치)."""
    if b is None:
        return 0.0
    ax1, ay1, ax2, ay2 = map(float, (a["x1"], a["y1"], a["x2"], a["y2"]))
    bx1, by1, bx2, by2 = map(float, (b["x1"], b["y1"], b["x2"], b["y2"]))
    dx1, dx2 = abs(ax1 - bx1), abs(ax2 - bx2)
    dy1, dy2 = abs(ay1 - by1), abs(ay2 - by2)

    if mode == "prevbox":
        w_prev = max(bx2 - bx1, eps)
        h_prev = max(by2 - by1, eps)
        dx_norm = (dx1 + dx2) / (2.0 * w_prev)
        dy_norm = (dy1 + dy2) / (2.0 * h_prev)
        d_norm = 0.5 * (dx_norm + dy_norm)
    else:  # "frame"
        W = float(frame_w or 0.0)
        H = float(frame_h or 0.0)
        denom = max(2.0 * W + 2.0 * H, eps)
        d_norm = (dx1 + dx2 + dy1 + dy2) / denom

    sim = max(0.0, 1.0 - min(1.0, d_norm))
    return sim

def _effective_anchor_boxes(
        current_frame: int,
        last_refs: List[Dict[str, Any]],
        max_age: int,
) -> List[Optional[Dict[str, Any]]]:
    """TTL 이내의 앵커만 유효."""
    eff: List[Optional[Dict[str, Any]]] = []
    for ref in last_refs:
        anchor = ref.get("anchor")
        last_seen = ref.get("last_seen")
        if anchor is not None and isinstance(last_seen, int) and (current_frame - last_seen) <= max_age:
            eff.append(anchor)
        else:
            eff.append(None)
    return eff

def _effective_last_boxes(
        current_frame: int,
        last_refs: List[Dict[str, Any]],
        max_age: int,
) -> List[Optional[Dict[str, Any]]]:
    """
    last_refs: [{"det": dict|None, "last_seen": int|None}, ...] x3
    현재 프레임과의 차가 max_age 이내인 것만 유효, 아니면 None로 무효화.
    """
    eff: List[Optional[Dict[str, Any]]] = []
    for ref in last_refs:
        det = ref.get("det")
        last_seen = ref.get("last_seen")
        if det is not None and isinstance(last_seen, int):
            if (current_frame - last_seen) <= max_age:
                eff.append(det)
            else:
                eff.append(None)  # 만료
        else:
            eff.append(None)
    return eff

def _l1_similarity_box(
        a: Dict[str, Any],
        b: Optional[Dict[str, Any]],
        *,
        frame_w: Optional[float],
        frame_h: Optional[float],
        mode: str = "frame",   # "frame" 또는 "prevbox"
        eps: float = 1e-6,
) -> float:
    """
    좌표 L1 거리 기반 유사도. 0~1 범위 반환 (1=완전 일치, 0=매우 다름).
    mode="frame": (|dx1|+|dx2|+|dy1|+|dy2|) / (2W+2H)로 정규화
    mode="prevbox": x차이는 2*w_prev, y차이는 2*h_prev로 각각 나눠 평균
    """
    if b is None:
        return 0.0
    try:
        ax1, ay1, ax2, ay2 = map(float, (a["x1"], a["y1"], a["x2"], a["y2"]))
        bx1, by1, bx2, by2 = map(float, (b["x1"], b["y1"], b["x2"], b["y2"]))
    except Exception:
        return 0.0

    dx1 = abs(ax1 - bx1)
    dx2 = abs(ax2 - bx2)
    dy1 = abs(ay1 - by1)
    dy2 = abs(ay2 - by2)

    if mode == "prevbox":
        w_prev = max(bx2 - bx1, eps)
        h_prev = max(by2 - by1, eps)
        # x방향, y방향 각각 상대 오차(0~1+)를 만들고 평균
        dx_norm = (dx1 + dx2) / (2.0 * w_prev)
        dy_norm = (dy1 + dy2) / (2.0 * h_prev)
        d_norm = 0.5 * (dx_norm + dy_norm)   # 0이면 완전일치, 1이면 '이전 박스' 크기만큼 어긋남
    else:  # "frame"
        W = float(frame_w or 0.0)
        H = float(frame_h or 0.0)
        denom = max(2.0 * W + 2.0 * H, eps)
        d_norm = (dx1 + dx2 + dy1 + dy2) / denom

    # 거리->유사도 (0~1로 클램프)
    sim = max(0.0, 1.0 - min(1.0, d_norm))
    return sim


def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(v, hi))

def _area_xyxy_xy(x1: float, y1: float, x2: float, y2: float) -> float:
    return max(0.0, x2 - x1) * max(0.0, y2 - y1)

def _area_xyxy(d: Dict[str, Any]) -> float:
    try:
        return _area_xyxy_xy(float(d["x1"]), float(d["y1"]), float(d["x2"]), float(d["y2"]))
    except Exception:
        return -1.0

def _iou_xyxy(a: Dict[str, Any], b: Optional[Dict[str, Any]]) -> float:
    if b is None:
        return 0.0
    try:
        ax1, ay1, ax2, ay2 = map(float, (a["x1"], a["y1"], a["x2"], a["y2"]))
        bx1, by1, bx2, by2 = map(float, (b["x1"], b["y1"], b["x2"], b["y2"]))
    except Exception:
        return 0.0

    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    inter = _area_xyxy_xy(ix1, iy1, ix2, iy2)
    if inter <= 0.0:
        return 0.0
    area_a = _area_xyxy_xy(ax1, ay1, ax2, ay2)
    area_b = _area_xyxy_xy(bx1, by1, bx2, by2)
    denom = area_a + area_b - inter
    if denom <= 0.0:
        return 0.0
    return inter / denom

def _scale_xyxy(box: Sequence[float], sx: float, sy: float) -> List[float]:
    x1, y1, x2, y2 = map(float, box)
    return [x1 * sx, y1 * sy, x2 * sx, y2 * sy]

def _center_unzoom_xyxy(box: Sequence[float], W: float, H: float, z: float) -> List[float]:
    """임의 해상도(W,H)의 중심 기준 1/z 역스케일. 입력/출력 동일 좌표계."""
    x1, y1, x2, y2 = map(float, box)
    z = float(z) if z else 1.0
    if abs(z) < 1e-9:
        z = 1.0
    cx, cy = float(W) * 0.5, float(H) * 0.5

    def unz(x, c): return c - (c - x) / z

    nx1, ny1 = unz(x1, cx), unz(y1, cy)
    nx2, ny2 = unz(x2, cx), unz(y2, cy)
    x1p, x2p = sorted((nx1, nx2))
    y1p, y2p = sorted((ny1, ny2))
    # 경계 클램프
    x1p = _clamp(x1p, 0.0, float(W)); x2p = _clamp(x2p, 0.0, float(W))
    y1p = _clamp(y1p, 0.0, float(H)); y2p = _clamp(y2p, 0.0, float(H))
    return [x1p, y1p, x2p, y2p]

def _enforce_9x16_height_first(box: Sequence[float], W: float, H: float) -> List[float]:
    """세로 기준 9:16 비율 강제. 높이를 기준으로 가로 = h*9/16. 화면 밖이면 이동/축소."""
    x1, y1, x2, y2 = map(float, box)
    cx = (x1 + x2) * 0.5
    cy = (y1 + y2) * 0.5
    h  = max(2.0, y2 - y1)
    target_w = h * 9.0 / 16.0

    # 화면을 넘으면 비율 유지한 채 축소
    if target_w > W:
        target_w = float(W)
        h = target_w * 16.0 / 9.0
    if h > H:
        h = float(H)
        target_w = h * 9.0 / 16.0

    x1n = cx - target_w * 0.5
    x2n = cx + target_w * 0.5
    y1n = cy - h * 0.5
    y2n = cy + h * 0.5

    # 경계 내로 이동
    if x1n < 0.0:
        x2n -= x1n; x1n = 0.0
    if x2n > W:
        diff = x2n - W
        x1n -= diff; x2n = float(W)
    if y1n < 0.0:
        y2n -= y1n; y1n = 0.0
    if y2n > H:
        diff = y2n - H
        y1n -= diff; y2n = float(H)

    return [x1n, y1n, x2n, y2n]

def _best_det(dets: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not dets:
        return None
    cand = [d for d in dets if "score" in d]
    if cand:
        return max(cand, key=lambda d: float(d.get("score", 0.0)))
    return max(dets, key=lambda d: _area_xyxy(d))

# ---------- 정렬 파이프라인 ----------

def _sort_frame_by_area(dets: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """면적 내림차순 정렬(프레임 1에 사용)."""
    return sorted(dets, key=_area_xyxy, reverse=True)

# --- 정렬 파이프라인: IoU → L1-정규화 유사도 & 앵커 사용 ---

def _compute_sorting_detections(
        dets: List[Dict[str, Any]],
        ref_boxes: List[Optional[Dict[str, Any]]],  # 앵커(L1/L2/L3)
        is_first_frame: bool,
        *,
        frame_w: Optional[float],
        frame_h: Optional[float],
        sim_mode: str = "frame",   # "frame" | "prevbox"
) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    if is_first_frame:
        base = _sort_frame_by_area(dets)
        for d in base:
            nd = dict(d)
            nd["SimilarityL1"] = None
            nd["SimilarityL2"] = None
            nd["SimilarityL3"] = None
            out.append(nd)
        return out

    l1, l2, l3 = ref_boxes
    for d in dets:
        nd = dict(d)
        nd["SimilarityL1"] = _l1_similarity_box(d, l1, frame_w=frame_w, frame_h=frame_h, mode=sim_mode)
        nd["SimilarityL2"] = _l1_similarity_box(d, l2, frame_w=frame_w, frame_h=frame_h, mode=sim_mode)
        nd["SimilarityL3"] = _l1_similarity_box(d, l3, frame_w=frame_w, frame_h=frame_h, mode=sim_mode)
        out.append(nd)
    return out

def _assign_sorted_detections(
        sorting_dets: List[Dict[str, Any]],
        is_first_frame: bool,
        *,
        sim_thresh: float,   # 0~1
) -> List[Optional[Dict[str, Any]]]:
    chosen: List[Optional[Dict[str, Any]]] = [None, None, None]

    if is_first_frame:
        by_area = sorted(sorting_dets, key=_area_xyxy, reverse=True)
        for k in range(3):
            chosen[k] = deepcopy(by_area[k]) if k < len(by_area) else None
        return chosen

    used: set = set()
    with_index = list(enumerate(sorting_dets))

    def pick_for_line(line_key: str):
        cand = [(i, d) for (i, d) in with_index
                if i not in used and float(d.get(line_key, 0.0)) >= sim_thresh]
        if not cand:
            return None
        return max(cand, key=lambda t: float(t[1].get(line_key, 0.0)))

    for k, key in enumerate(["SimilarityL1", "SimilarityL2", "SimilarityL3"]):
        picked = pick_for_line(key)
        if picked is None:
            chosen[k] = None
        else:
            i, d = picked
            used.add(i)
            chosen[k] = deepcopy(d)
    return chosen

#--------------노이즈 날리는 헬퍼 ---------------
def _det_to_box_or_none(d):
    if not isinstance(d, dict):
        return None
    try:
        return {
            "x1": float(d["x1"]), "y1": float(d["y1"]),
            "x2": float(d["x2"]), "y2": float(d["y2"]),
        }
    except Exception:
        return None

def _diag_of(box):
    if box is None:
        return None
    w = max(0.0, box["x2"] - box["x1"])
    h = max(0.0, box["y2"] - box["y1"])
    return sqrt(w*w + h*h)

def _mean_box(boxes):
    """boxes: dict or None 리스트. None은 제외하고 평균."""
    xs = {"x1": [], "y1": [], "x2": [], "y2": []}
    for b in boxes:
        if b is None:
            continue
        for k in xs:
            xs[k].append(b[k])
    if not all(xs[k] for k in xs):
        return None
    return {k: float(sum(xs[k]) / len(xs[k])) for k in xs}

def _linear_interpolate_series(series):
    """
    series: 길이 T의 [box or None]
    None 구간을 선형 보간. 앞/뒤 가장자리 None은 근처 유효값으로 보간(앞→첫 유효, 뒤→마지막 유효).
    """
    T = len(series)
    out = [None]*T

    # 유효 인덱스
    valid_idx = [i for i, b in enumerate(series) if b is not None]
    if not valid_idx:
        return out

    # 앞쪽 채우기
    first = valid_idx[0]
    for i in range(0, first):
        out[i] = dict(series[first])

    # 구간 보간
    for s, e in zip(valid_idx, valid_idx[1:]):
        bs, be = series[s], series[e]
        out[s] = dict(bs)
        span = e - s
        for t in range(1, span):
            r = t / span
            out[s+t] = {
                "x1": bs["x1"]*(1-r) + be["x1"]*r,
                "y1": bs["y1"]*(1-r) + be["y1"]*r,
                "x2": bs["x2"]*(1-r) + be["x2"]*r,
                "y2": bs["y2"]*(1-r) + be["y2"]*r,
            }
    # 끝쪽 채우기
    last = valid_idx[-1]
    out[last] = dict(series[last])
    for i in range(last+1, T):
        out[i] = dict(series[last])
    return out

def _moving_average_series(series, k=3):
    """간단 이동평균(홀수 k). 각 좌표별 NaN 없이 평균."""
    if k < 1:
        return series
    if k % 2 == 0:
        k += 1
    T = len(series)
    half = k // 2
    out = []
    for i in range(T):
        acc = {"x1": 0.0, "y1": 0.0, "x2": 0.0, "y2": 0.0}
        cnt = 0
        for j in range(max(0, i-half), min(T, i+half+1)):
            b = series[j]
            if b is None:
                continue
            acc["x1"] += b["x1"]; acc["y1"] += b["y1"]
            acc["x2"] += b["x2"]; acc["y2"] += b["y2"]
            cnt += 1
        if cnt == 0:
            out.append(None)
        else:
            out.append({k2: acc[k2]/cnt for k2 in acc})
    return out

def _flag_noise_overlapped(series, win=10, abs_px=100.0, rel_ratio=0.15):
    """
    두 번 오버랩(오프셋 0, win//2) 윈도우 평균 대비 벗어남 여부를 플래그(True=노이즈).
    상대 임계는 '윈도우 평균 박스의 대각선' * rel_ratio 로 계산.
    """
    T = len(series)
    if win < 2:
        win = 2
    if win % 2 == 1:   # 짝수 강제
        win += 1

    def _one_pass(offset):
        flags = [False]*T
        step = win // 2
        for start in range(offset, T, step):
            end = min(T, start + win)
            if end - start < 2:
                break
            window = [series[i] for i in range(start, end)]
            ref = _mean_box(window)
            if ref is None:
                continue
            ref_diag = _diag_of(ref) or 0.0
            thr_x = max(abs_px, ref_diag * rel_ratio)
            thr_y = max(abs_px, ref_diag * rel_ratio)
            for i in range(start, end):
                b = series[i]
                if b is None:
                    continue
                if (abs(b["x1"] - ref["x1"]) > thr_x or
                        abs(b["x2"] - ref["x2"]) > thr_x or
                        abs(b["y1"] - ref["y1"]) > thr_y or
                        abs(b["y2"] - ref["y2"]) > thr_y):
                    flags[i] = True
        return flags

    f0 = _one_pass(0)
    f1 = _one_pass(win//2)
    return [a or b for a, b in zip(f0, f1)]



# ---------- main ----------

# --- 메인: EMA 앵커 업데이트(과거 가중치 누적), TTL, 임계치, half-life 파라미터 ---
# --- 메인: EMA 앵커 업데이트 + 노이즈 제거/보간/스무딩 반영 ---
def merge_offline_logs(
        detect_log_path: str,
        zoom_log_path: str,
        output_path: str,
        *,
        line_valid_horizon: int = 20,   # TTL: 앵커 최대 유효 프레임 수
        sim_mode: str = "prevbox",      # 유사도 정규화 기준("prevbox" 권장)
        sim_thresh: float = 0.3,        # 유사도 임계(높을수록 보수적)
        history_half_life: int = 45,    # EMA 반감 프레임(최근 프레임 강조)
        # ▼ 노이즈 처리 파라미터
        noise_win: int = 10,            # 짝수 권장(자동 보정). 예: 6, 10, 12...
        noise_abs_px: float = 100.0,    # 절대 임계
        noise_rel_ratio: float = 0.15,  # 상대 임계(윈도우 평균 박스 대각선 대비)
        fill_ma_k: int = 3,             # 보간 후 소형 이동평균(부드럽게). 0/1이면 생략
) -> None:
    # 1) detection JSONL
    detect: List[Dict[str, Any]] = []
    with open(detect_log_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                detect.append(json.loads(line))

    # 2) zoom/makelog
    with open(zoom_log_path, "r", encoding="utf-8") as f:
        txt = f.read().strip()
        if txt.startswith("["):
            zoom_list = json.loads(txt)
        else:
            zoom_list = [json.loads(l) for l in txt.splitlines() if l.strip()]

    # frame → zoom row
    zmap: Dict[int, Dict[str, Any]] = {}
    for zr in zoom_list:
        idx = zr.get("frame") or zr.get("frameIndex")
        if idx is None:
            continue
        zmap[int(idx)] = zr

    merged: List[Dict[str, Any]] = []
    prev_zoom = 1.0
    matched = 0

    # L1/L2/L3 앵커 상태(EMA)
    last_line_refs: List[Dict[str, Any]] = [
        {"anchor": None, "last_seen": None},  # L1
        {"anchor": None, "last_seen": None},  # L2
        {"anchor": None, "last_seen": None},  # L3
    ]

    # half-life → per-frame decay γ, Δ프레임 간격 반영해 α_eff = 1 - γ^Δ
    history_half_life = max(1, int(history_half_life))
    gamma = 0.5 ** (1.0 / history_half_life)

    # L1 raw 시계열을 모아 두기
    l1_raw_series: List[Optional[Dict[str, float]]] = []

    # ----- 1차 루프: 정렬/앵커만 계산하고, L1을 수집 (크롭은 아직 안 함) -----
    for f_idx, rec in enumerate(detect):
        frame = int(rec.get("frame", len(merged)))
        src_w, src_h = rec.get("src_w"), rec.get("src_h")

        out = dict(rec)
        zr = zmap.get(frame)
        if zr:
            matched += 1
            out["zoom"] = zr.get("zoom", prev_zoom)
            out["screenWidth"]  = zr.get("screenWidth")  or src_w
            out["screenHeight"] = zr.get("screenHeight") or src_h
            if "aspect_ratio_bbox" in zr:
                out["aspect_ratio_bbox"] = zr["aspect_ratio_bbox"]
        else:
            out.setdefault("zoom", prev_zoom)
            out.setdefault("screenWidth", src_w)
            out.setdefault("screenHeight", src_h)

        try:
            prev_zoom = float(out.get("zoom", prev_zoom))
        except Exception:
            pass

        sw = out.get("screenWidth") or src_w
        sh = out.get("screenHeight") or src_h
        z  = out.get("zoom") or 1.0

        dets = out.get("detections", []) or []

        # ---------- 정렬 ----------
        # TTL 반영된 '유효 앵커'
        effective_anchors = _effective_anchor_boxes(frame, last_line_refs, line_valid_horizon)
        is_first_frame = all(b is None for b in effective_anchors)

        # 프레임 크기(정규화용). src 없으면 screen 사용
        fw = src_w or out.get("screenWidth")
        fh = src_h or out.get("screenHeight")

        sorting_dets = _compute_sorting_detections(
            dets, effective_anchors, is_first_frame=is_first_frame,
            frame_w=fw, frame_h=fh, sim_mode=sim_mode,
        )
        out["sorting_detections"] = sorting_dets

        sorted_lines = _assign_sorted_detections(
            sorting_dets, is_first_frame=is_first_frame, sim_thresh=sim_thresh,
        )
        out["sorted_detections"] = sorted_lines

        # ---------- EMA 앵커 업데이트 ----------
        for k in range(3):
            det_k = sorted_lines[k]
            if det_k is not None:
                last_seen = last_line_refs[k]["last_seen"]
                delta = 1 if last_seen is None else max(1, frame - last_seen)
                alpha_eff = 1.0 - (gamma ** float(delta))  # 최근일수록 큰 가중치
                last_line_refs[k]["anchor"] = _box_ema(last_line_refs[k]["anchor"], det_k, alpha_eff)
                last_line_refs[k]["last_seen"] = frame

        # L1 raw 축적
        l1_raw_series.append(_det_to_box_or_none(sorted_lines[0]))
        merged.append(out)

    # ----- 2차: L1 노이즈 제거 + 보간 + (선택) MA 스무딩 -----
    # (a) 오버랩 윈도우 평균 대비 이탈 플래그
    noise_flags = _flag_noise_overlapped(
        l1_raw_series,
        win=noise_win,
        abs_px=noise_abs_px,
        rel_ratio=noise_rel_ratio
    )

    # (b) 노이즈 프레임을 None으로 마스킹
    l1_clean = [None if (b is None or noise_flags[i]) else b for i, b in enumerate(l1_raw_series)]

    # (c) None 보간(선형)
    l1_filled = _linear_interpolate_series(l1_clean)

    # (d) (옵션) 소형 이동평균으로 부드럽게
    if fill_ma_k and fill_ma_k >= 2:
        l1_smoothed = _moving_average_series(l1_filled, k=fill_ma_k)
    else:
        l1_smoothed = l1_filled

    # ----- 3차: 최종 det_for_crop으로 크롭 재계산 & 결과 쓰기 -----
    for i, out in enumerate(merged):
        det_for_crop = l1_smoothed[i]  # 최종 결정치(노이즈 제거 + 보간 + 스무딩)
        out["det_for_crop"] = det_for_crop  # 디버깅용 기록

        src_w = out.get("src_w"); src_h = out.get("src_h")
        sw = out.get("screenWidth") or src_w
        sh = out.get("screenHeight") or src_h
        z  = out.get("zoom") or 1.0

        if det_for_crop and src_w and src_h and sw and sh:
            box_src = [det_for_crop["x1"], det_for_crop["y1"], det_for_crop["x2"], det_for_crop["y2"]]
            box_src = _center_unzoom_xyxy(box_src, float(src_w), float(src_h), float(z))
            box_src = _enforce_9x16_height_first(box_src, float(src_w), float(src_h))
            out["crop_box_src_xyxy"] = {"x1": box_src[0], "y1": box_src[1], "x2": box_src[2], "y2": box_src[3]}
            sx = float(sw) / float(src_w); sy = float(sh) / float(src_h)
            x1s, y1s, x2s, y2s = _scale_xyxy(box_src, sx, sy)
            out["crop_box_screen_xyxy"] = {"x1": x1s, "y1": y1s, "x2": x2s, "y2": y2s}

    # 쓰기
    with open(output_path, "w", encoding="utf-8") as f:
        for m in merged:
            f.write(json.dumps(m, ensure_ascii=False) + "\n")

    print(f"✅ merge 완료: {output_path} (detect={len(detect)}, zoom_frames={len(zmap)}, matched={matched})")



