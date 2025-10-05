# merge_tracks_pipeline.py (robust-super-smooth)
# - track 기반 병합 + 강력 노이즈 제거 + 가중 스무딩
# - 파이프라인:
#   (MAD 기반 롤링 아웃라이어 제거 → 버스트 제거 → 보간 → Biweight 가중 스무딩)
#   → (줌 역변환) → (9:16 강제) → (스크린 좌표 스케일)
# - 출력(JSONL) 최소 필드 (FrameCropper 호환)

import json
from typing import List, Dict, Any, Optional, Sequence, Tuple

# ------------------ 기본 유틸 ------------------

def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(v, hi))

def _center_unzoom_xyxy(box: Sequence[float], W: float, H: float, z: float) -> List[float]:
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
    x1p = _clamp(x1p, 0.0, float(W)); x2p = _clamp(x2p, 0.0, float(W))
    y1p = _clamp(y1p, 0.0, float(H)); y2p = _clamp(y2p, 0.0, float(H))
    return [x1p, y1p, x2p, y2p]

def _enforce_9x16_height_first(box: Sequence[float], W: float, H: float) -> List[float]:
    x1, y1, x2, y2 = map(float, box)
    cx, cy = (x1 + x2) * 0.5, (y1 + y2) * 0.5
    h = max(2.0, y2 - y1)
    target_w = h * 9.0 / 16.0
    if target_w > W:
        target_w = float(W); h = target_w * 16.0 / 9.0
    if h > H:
        h = float(H); target_w = h * 9.0 / 16.0
    x1n, x2n = cx - target_w * 0.5, cx + target_w * 0.5
    y1n, y2n = cy - h * 0.5,        cy + h * 0.5
    if x1n < 0.0: x2n -= x1n; x1n = 0.0
    if x2n > W:   d = x2n - W; x1n -= d; x2n = float(W)
    if y1n < 0.0: y2n -= y1n; y1n = 0.0
    if y2n > H:   d = y2n - H; y1n -= d; y2n = float(H)
    return [x1n, y1n, x2n, y2n]

def _scale_xyxy(box: Sequence[float], sx: float, sy: float) -> List[float]:
    x1, y1, x2, y2 = map(float, box)
    return [x1 * sx, y1 * sy, x2 * sx, y2 * sy]

def _track_box_from_rec(rec: Dict[str, Any]) -> Optional[Dict[str, float]]:
    def as_box(obj) -> Optional[Dict[str, float]]:
        if obj is None: return None
        if isinstance(obj, dict):
            try:    return {k: float(obj[k]) for k in ("x1", "y1", "x2", "y2")}
            except: return None
        if isinstance(obj, (list, tuple)) and len(obj) == 4:
            try:
                x1, y1, x2, y2 = map(float, obj)
                return {"x1": x1, "y1": y1, "x2": x2, "y2": y2}
            except: return None
        return None
    for k in ("track", "track_box", "bbox"):
        if k in rec:
            b = as_box(rec[k])
            if b is not None: return b
    return None

# ------------------ 보간/스무딩 ------------------

def _linear_interpolate_series(series: List[Optional[Dict[str, float]]]) -> List[Optional[Dict[str, float]]]:
    T = len(series)
    out = [None] * T
    idx = [i for i, b in enumerate(series) if b is not None]
    if not idx: return out
    first = idx[0]
    for i in range(0, first): out[i] = dict(series[first])
    for s, e in zip(idx, idx[1:]):
        bs, be = series[s], series[e]
        out[s] = dict(bs)
        span = e - s
        for t in range(1, span):
            r = t / span
            out[s + t] = {
                "x1": bs["x1"] * (1 - r) + be["x1"] * r,
                "y1": bs["y1"] * (1 - r) + be["y1"] * r,
                "x2": bs["x2"] * (1 - r) + be["x2"] * r,
                "y2": bs["y2"] * (1 - r) + be["y2"] * r,
            }
    last = idx[-1]
    out[last] = dict(series[last])
    for i in range(last + 1, T): out[i] = dict(series[last])
    return out

def _moving_average_series(series: List[Optional[Dict[str, float]]], k: int = 3) -> List[Optional[Dict[str, float]]]:
    if k < 2: return series
    if k % 2 == 0: k += 1
    T, half = len(series), k // 2
    out: List[Optional[Dict[str, float]]] = []
    for i in range(T):
        acc = {"x1": 0.0, "y1": 0.0, "x2": 0.0, "y2": 0.0}; cnt = 0
        for j in range(max(0, i - half), min(T, i + half + 1)):
            b = series[j]
            if b is None: continue
            acc["x1"] += b["x1"]; acc["y1"] += b["y1"]; acc["x2"] += b["x2"]; acc["y2"] += b["y2"]; cnt += 1
        out.append(None if cnt == 0 else {k2: acc[k2] / cnt for k2 in acc})
    return out

def _weighted_moving_average_series(series: List[Optional[Dict[str, float]]], k: int = 7) -> List[Optional[Dict[str, float]]]:
    """Tukey 삼각형(선형) 가중 이동평균 (중심에 높은 가중)"""
    if k < 2: return series
    if k % 2 == 0: k += 1
    T, half = len(series), k // 2
    out: List[Optional[Dict[str, float]]] = []
    for i in range(T):
        acc = {"x1": 0.0, "y1": 0.0, "x2": 0.0, "y2": 0.0}; wsum = 0.0
        for j in range(max(0, i - half), min(T, i + half + 1)):
            b = series[j]
            if b is None: continue
            dist = abs(j - i)
            weight = max(0.0001, 1.0 - dist / (half if half > 0 else 1))
            acc["x1"] += b["x1"] * weight; acc["y1"] += b["y1"] * weight
            acc["x2"] += b["x2"] * weight; acc["y2"] += b["y2"] * weight
            wsum += weight
        out.append(None if wsum <= 0 else {k2: acc[k2] / wsum for k2 in acc})
    return out

# ------------------ 노이즈 제거 (강화) ------------------

def _to_cxcywh(b: Dict[str, float]) -> Tuple[float, float, float, float]:
    x1, y1, x2, y2 = b["x1"], b["y1"], b["x2"], b["y2"]
    w = max(1.0, x2 - x1); h = max(1.0, y2 - y1)
    cx = (x1 + x2) * 0.5; cy = (y1 + y2) * 0.5
    return cx, cy, w, h

def _area(b: Dict[str, float]) -> float:
    return max(1.0, (b["x2"] - b["x1"])) * max(1.0, (b["y2"] - b["y1"]))

def _avg_box(b1: Dict[str, float], b2: Dict[str, float]) -> Dict[str, float]:
    return {"x1": (b1["x1"] + b2["x1"]) * 0.5, "y1": (b1["y1"] + b2["y1"]) * 0.5,
            "x2": (b1["x2"] + b2["x2"]) * 0.5, "y2": (b1["y2"] + b2["y2"]) * 0.5}

def _box_distance_norm(b: Dict[str, float], ref: Dict[str, float]) -> float:
    cx, cy, w, h = _to_cxcywh(b); rx, ry, rw, rh = _to_cxcywh(ref)
    diag = max(1.0, (rw * rh) ** 0.5)
    return ((cx - rx) ** 2 + (cy - ry) ** 2) ** 0.5 / diag

def _is_hard_spike(mid: Dict[str, float], left: Dict[str, float], right: Dict[str, float],
                   center_jump_frac: float, area_ratio_thr: float) -> bool:
    ref = _avg_box(left, right)
    d = _box_distance_norm(mid, ref)
    a_mid, a_ref = _area(mid), _area(ref)
    ra = a_mid / max(1.0, a_ref)
    return (d > center_jump_frac) or (ra > area_ratio_thr) or (ra < 1.0 / area_ratio_thr)

def _despike_single(series: List[Optional[Dict[str, float]]],
                    center_jump_frac: float = 0.10,
                    area_ratio_thr: float = 1.4) -> List[Optional[Dict[str, float]]]:
    out = series[:]
    for i in range(1, len(series) - 1):
        b = series[i]; L = series[i - 1]; R = series[i + 1]
        if b is None or L is None or R is None: continue
        if _is_hard_spike(b, L, R, center_jump_frac, area_ratio_thr):
            out[i] = None
    return out

def _despike_burst(series: List[Optional[Dict[str, float]]],
                   max_len: int = 6,
                   center_jump_frac: float = 0.13,
                   area_ratio_thr: float = 1.5) -> List[Optional[Dict[str, float]]]:
    out = series[:]
    T = len(series); i = 0
    while i < T:
        while i < T and series[i] is None: i += 1
        if i >= T: break
        Lidx = i; L = series[Lidx]
        j = Lidx + 1
        while j < T and series[j] is not None: j += 1
        Ridx = j
        k = Ridx + 1
        while k < T and series[k] is None: k += 1
        if k < T and (Ridx - Lidx) <= max_len:
            R = series[k]
            if L is not None and R is not None:
                drop = False
                for t in range(Lidx, Ridx):
                    if _is_hard_spike(series[t], L, R, center_jump_frac, area_ratio_thr):
                        drop = True; break
                if drop:
                    for t in range(Lidx, Ridx): out[t] = None
        i = Ridx
    return out

# --------- MAD(중앙절대편차) 기반 롤링 아웃라이어 제거 ---------

def _median(vals: List[float]) -> float:
    s = sorted(vals); n = len(s)
    if n == 0: return 0.0
    mid = n // 2
    return s[mid] if n % 2 == 1 else 0.5 * (s[mid - 1] + s[mid])

def _mad(vals: List[float], med: float) -> float:
    dev = [abs(v - med) for v in vals]
    return _median(dev) or 0.0

def _roll_mad_filter(series: List[Optional[Dict[str, float]]],
                     win: int = 21,
                     k_sigma: float = 3.5) -> List[Optional[Dict[str, float]]]:
    """
    각 좌표(x1,y1,x2,y2)에 대해 롤링 중앙값/중앙절대편차(MAD)로 아웃라이어 프레임을 None 처리.
    """
    if win % 2 == 0: win += 1
    T = len(series); half = win // 2
    out = series[:]
    for i in range(T):
        # 윈도우 수집
        xs1 = []; ys1 = []; xs2 = []; ys2 = []
        for j in range(max(0, i - half), min(T, i + half + 1)):
            b = series[j]
            if b is None: continue
            xs1.append(b["x1"]); ys1.append(b["y1"]); xs2.append(b["x2"]); ys2.append(b["y2"])
        b = series[i]
        if b is None or not xs1: continue

        def is_outlier(val, arr):
            med = _median(arr)
            mad = _mad(arr, med)
            # 1.4826 * MAD ≈ σ (정규 근사)
            sigma = 1.4826 * mad
            if sigma < 1e-6:  # 거의 정지 상태면 아웃라이어 없음
                return False
            return abs(val - med) > (k_sigma * sigma)

        if (is_outlier(b["x1"], xs1) or is_outlier(b["y1"], ys1)
                or is_outlier(b["x2"], xs2) or is_outlier(b["y2"], ys2)):
            out[i] = None
    return out

# ------------------ 메인 파이프라인 ------------------

def merge_offline_logs_from_tracks_min(
        detect_log_path: str,
        zoom_log_path: str,
        output_path: str,
        *,
        # 강력 필터/스무딩 파라미터 (필요시 조정)
        mad_win: int = 61,          # 롤링 윈도우(프레임)
        mad_k_sigma: float = 2.8,   # 중앙값-기반 σ 배수 임계치
        single_center_jump_frac: float = 0.08,
        single_area_ratio_thr: float = 1.35,
        burst_max_len: int = 18,
        burst_center_jump_frac: float = 0.12,
        burst_area_ratio_thr: float = 1.4,
        smooth_k: int = 31,          # 최종 가중 이동평균 윈도우
) -> None:
    # 1) detect JSONL
    detect: List[Dict[str, Any]] = []
    with open(detect_log_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                detect.append(json.loads(line))

    # 2) processed/zoom JSON (list 또는 jsonl)
    with open(zoom_log_path, "r", encoding="utf-8") as f:
        txt = f.read().strip()
        zoom_list = json.loads(txt) if txt.startswith("[") else [
            json.loads(l) for l in txt.splitlines() if l.strip()
        ]

    # frame → zoom row
    zmap: Dict[int, Dict[str, Any]] = {}
    for zr in zoom_list:
        idx = zr.get("frame") or zr.get("frameIndex")
        if idx is not None:
            zmap[int(idx)] = zr

    # 3) track 시계열(raw)
    tracks: List[Optional[Dict[str, float]]] = [_track_box_from_rec(r) for r in detect]

    # 4) 1차 보간 (MAD 계산을 위해 빈 구간 메움)
    filled = _linear_interpolate_series(tracks)

    # 5) 롤링 중앙값+MAD로 강한 아웃라이어 제거
    robust = _roll_mad_filter(filled, win=mad_win, k_sigma=mad_k_sigma)

    # 6) 단일 스파이크/짧은 버스트 제거(추가 보강)
    robust = _despike_single(robust, single_center_jump_frac, single_area_ratio_thr)
    robust = _despike_burst(robust, burst_max_len, burst_center_jump_frac, burst_area_ratio_thr)

    # 7) 제거 구간 재보간
    refilled = _linear_interpolate_series(robust)

    # 8) 부드러운 최종 스무딩(보통 smooth_k=9~15)
    smooth = _weighted_moving_average_series(refilled, k=max(5, smooth_k))

    # 9) 출력(JSONL) — FrameCropper 호환
    with open(output_path, "w", encoding="utf-8") as f:
        matched = 0
        prev_sw = prev_sh = None
        for i, rec in enumerate(detect):
            frame = int(rec.get("frame", i))
            out: Dict[str, Any] = {"frame": frame}
            if "pts_ms" in rec: out["pts_ms"] = rec["pts_ms"]
            if "state"  in rec: out["state"]  = rec["state"]
            if "track"  in rec: out["track"]  = rec["track"]

            zr = zmap.get(frame)
            if zr:
                matched += 1
                out["zoom"] = zr.get("zoom", 1.0)
                out["screenWidth"]  = zr.get("screenWidth")
                out["screenHeight"] = zr.get("screenHeight")
            else:
                out["zoom"] = rec.get("zoom", 1.0)
                out["screenWidth"]  = rec.get("screenWidth", prev_sw)
                out["screenHeight"] = rec.get("screenHeight", prev_sh)

            if out.get("screenWidth")  is not None: prev_sw = out["screenWidth"]
            if out.get("screenHeight") is not None: prev_sh = out["screenHeight"]

            src_w = rec.get("src_w") or rec.get("in_w") or 2160
            src_h = rec.get("src_h") or rec.get("in_h") or 3840
            if zr:
                src_w = src_w or zr.get("in_w") or zr.get("sourceWidth") or zr.get("videoWidth") or 2160
                src_h = src_h or zr.get("in_h") or zr.get("sourceHeight") or zr.get("videoHeight") or 3840

            out["src_w"] = int(src_w); out["src_h"] = int(src_h)

            z = float(out.get("zoom") or 1.0)
            det = smooth[i]

            if det is not None and src_w and src_h:
                box_src = [det["x1"], det["y1"], det["x2"], det["y2"]]
                box_src = _center_unzoom_xyxy(box_src, float(src_w), float(src_h), z)            # 역줌
                box_src = _enforce_9x16_height_first(box_src, float(src_w), float(src_h))        # 9:16 강제

                out["det_for_crop"] = {"x1": box_src[0], "y1": box_src[1], "x2": box_src[2], "y2": box_src[3]}
                out["crop_box_src_xyxy"] = dict(out["det_for_crop"])

                sw = out.get("screenWidth")  or src_w
                sh = out.get("screenHeight") or src_h
                if sw and sh:
                    sx, sy = float(sw) / float(src_w), float(sh) / float(src_h)
                    x1s, y1s, x2s, y2s = _scale_xyxy(box_src, sx, sy)
                    out["crop_box_screen_xyxy"] = {"x1": x1s, "y1": y1s, "x2": x2s, "y2": y2s}

                out["sorted_detections"] = [dict(out["det_for_crop"]), None, None]
            else:
                out["sorted_detections"] = [None, None, None]

            f.write(json.dumps(out, ensure_ascii=False) + "\n")

    print(f"✅ merge(from tracks, robust+smooth) 완료: {output_path} "
          f"(detect={len(detect)}, zoom_frames={len(zmap)}, matched={matched})")
