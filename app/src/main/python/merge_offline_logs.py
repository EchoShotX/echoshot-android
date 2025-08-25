# merge_offline_logs.py — detections만 사용
# 1) src에서 best det 선택
# 2) 중심 기준 1/z 역스케일 (src)
# 3) 세로 기준 9:16 강제 (src, 화면 경계 클램프)
# 4) src→screen 스케일해서 crop_box_screen_xyxy 생성
# aspect_ratio_bbox 는 그대로 패스 (계산 미사용)

import json
from typing import List, Dict, Any, Optional, Sequence


# ---------- helpers ----------

def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(v, hi))

def _best_det(dets: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not dets:
        return None
    cand = [d for d in dets if "score" in d]
    if cand:
        return max(cand, key=lambda d: float(d.get("score", 0.0)))
    return max(dets, key=lambda d: (float(d["x2"]) - float(d["x1"])) * (float(d["y2"]) - float(d["y1"])))

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


# ---------- main ----------

def merge_offline_logs(detect_log_path: str, zoom_log_path: str, output_path: str) -> None:
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
        idx = zr.get("frame")
        if idx is None:
            idx = zr.get("frameIndex")
        if idx is None:
            continue
        zmap[int(idx)] = zr

    merged: List[Dict[str, Any]] = []
    prev_zoom = 1.0
    matched = 0

    for rec in detect:
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
                out["aspect_ratio_bbox"] = zr["aspect_ratio_bbox"]  # 그대로 패스
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

        det = _best_det(out.get("detections", []) or [])
        if det and src_w and src_h and sw and sh:
            # (A) src 좌표에서 역스케일
            box_src = [float(det["x1"]), float(det["y1"]), float(det["x2"]), float(det["y2"])]
            box_src = _center_unzoom_xyxy(box_src, float(src_w), float(src_h), float(z))
            box_src = _enforce_9x16_height_first(box_src, float(src_w), float(src_h))

            # 디버그/검증용: src 좌표 그대로 기록 (여기 높이가 1627.5/2.188 ≈ 743.83)
            out["crop_box_src_xyxy"] = {
                "x1": box_src[0], "y1": box_src[1],
                "x2": box_src[2], "y2": box_src[3],
            }

            # (B) screen으로 스케일
            sx = float(sw) / float(src_w)
            sy = float(sh) / float(src_h)
            box_scr = _scale_xyxy(box_src, sx, sy)

            out["crop_box_screen_xyxy"] = {
                "x1": box_scr[0], "y1": box_scr[1],
                "x2": box_scr[2], "y2": box_scr[3],
            }

        merged.append(out)

    with open(output_path, "w", encoding="utf-8") as f:
        for m in merged:
            f.write(json.dumps(m, ensure_ascii=False) + "\n")

    print(f"✅ merge 완료: {output_path} (detect={len(detect)}, zoom_frames={len(zmap)}, matched={matched})")
