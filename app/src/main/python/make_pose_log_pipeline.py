"""
YOLO11n-pose 로그 후처리 파이프라인

입력: pose_log.jsonl (PoseDetectLogManager가 생성한 포즈 로그)
출력: processed_pose.json (칼만필터 스무딩된 상체 bbox)

주요 기능:
1. OKS(Object Keypoint Similarity) 기반 동일 인물 트래킹
2. 상체 관절(머리/어깨/엉덩이)만 사용한 bbox 계산
3. 칼만필터 + RTS 스무딩
4. 9:16 크롭 비율 적용
"""

import json
import math
from copy import deepcopy
from typing import Optional, List, Tuple, Dict, Any
import numpy as np

# ===== COCO Keypoint 정의 =====
KEYPOINT_NAMES = [
    "nose", "left_eye", "right_eye", "left_ear", "right_ear",
    "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
    "left_wrist", "right_wrist", "left_hip", "right_hip",
    "left_knee", "right_knee", "left_ankle", "right_ankle"
]

# 상체 인덱스 (머리 + 어깨 + 엉덩이)
TORSO_INDICES = [0, 1, 2, 3, 4, 5, 6, 11, 12]
HEAD_INDICES = [0, 1, 2, 3, 4]
SHOULDER_INDICES = [5, 6]
HIP_INDICES = [11, 12]

# OKS 계산용 시그마 (COCO 표준)
KEYPOINT_SIGMAS = np.array([
    0.026, 0.025, 0.025, 0.035, 0.035,  # 코, 눈, 귀
    0.079, 0.079, 0.072, 0.072,          # 어깨, 팔꿈치
    0.062, 0.062,                         # 손목
    0.107, 0.107,                         # 엉덩이
    0.087, 0.087,                         # 무릎
    0.089, 0.089                          # 발목
])


# ===== OKS (Object Keypoint Similarity) =====
def compute_oks(kps1: List[List[float]], kps2: List[List[float]], 
                area: float, sigmas: np.ndarray = KEYPOINT_SIGMAS) -> float:
    """
    두 포즈 간 OKS(Object Keypoint Similarity) 계산
    
    Args:
        kps1, kps2: [[x, y, conf], ...] × 17
        area: 바운딩 박스 면적 (스케일 팩터)
        sigmas: keypoint별 표준편차
    
    Returns:
        OKS 값 (0~1, 1이면 완전 일치)
    """
    if len(kps1) != len(kps2) or len(kps1) != 17:
        return 0.0
    
    oks_sum = 0.0
    count = 0
    area = max(1.0, area)
    
    for i in range(17):
        conf1 = kps1[i][2] if len(kps1[i]) >= 3 else 0
        conf2 = kps2[i][2] if len(kps2[i]) >= 3 else 0
        
        # 둘 다 visible 해야 비교
        if conf1 < 0.3 or conf2 < 0.3:
            continue
        
        dx = kps1[i][0] - kps2[i][0]
        dy = kps1[i][1] - kps2[i][1]
        d2 = dx * dx + dy * dy
        
        sigma = sigmas[i]
        k2 = 2 * sigma * sigma
        
        # exp(-d² / (2 * σ² * s²))
        oks_sum += math.exp(-d2 / (k2 * area))
        count += 1
    
    return oks_sum / count if count > 0 else 0.0


def compute_torso_similarity(kps1: List[List[float]], kps2: List[List[float]], 
                              area: float) -> float:
    """상체 keypoints만 사용한 유사도 계산"""
    if len(kps1) < 17 or len(kps2) < 17:
        return 0.0
    
    sim_sum = 0.0
    count = 0
    area = max(1.0, area)
    
    for i in TORSO_INDICES:
        conf1 = kps1[i][2] if len(kps1[i]) >= 3 else 0
        conf2 = kps2[i][2] if len(kps2[i]) >= 3 else 0
        
        if conf1 < 0.3 or conf2 < 0.3:
            continue
        
        dx = kps1[i][0] - kps2[i][0]
        dy = kps1[i][1] - kps2[i][1]
        d2 = dx * dx + dy * dy
        
        sigma = KEYPOINT_SIGMAS[i]
        k2 = 2 * sigma * sigma
        
        sim_sum += math.exp(-d2 / (k2 * area))
        count += 1
    
    return sim_sum / count if count > 0 else 0.0


# ===== 상체 bbox 계산 =====
def get_torso_bbox(keypoints: List[List[float]], min_conf: float = 0.3) -> Optional[List[float]]:
    """
    상체 관절(머리/어깨/엉덩이)로부터 바운딩 박스 계산 (중심을 아래로 40% 이동)
    
    Returns:
        [x1, y1, x2, y2] 또는 None
    """
    valid_points = []
    for i in TORSO_INDICES:
        if i >= len(keypoints):
            continue
        kp = keypoints[i]
        if len(kp) >= 3 and kp[2] >= min_conf:
            valid_points.append((kp[0], kp[1]))
    
    if len(valid_points) < 3:
        return None
    
    xs = [p[0] for p in valid_points]
    ys = [p[1] for p in valid_points]
    x1, y1, x2, y2 = min(xs), min(ys), max(xs), max(ys)
    
    # 상체 bbox 중심을 아래로 이동 (위:아래 = 3:7 비율)
    # bbox 높이의 40%만큼 아래로 이동하면 하체가 덜 잘림
    h = y2 - y1
    offset = h * 0.4  # 중심을 아래로 40% 이동
    
    return [x1, y1 + offset, x2, y2 + offset]


def get_torso_center(keypoints: List[List[float]], min_conf: float = 0.3) -> Optional[Tuple[float, float]]:
    """상체 중심점 (어깨-엉덩이 중간)"""
    points = []
    for i in SHOULDER_INDICES + HIP_INDICES:
        if i >= len(keypoints):
            continue
        kp = keypoints[i]
        if len(kp) >= 3 and kp[2] >= min_conf:
            points.append((kp[0], kp[1]))
    
    if not points:
        return None
    
    cx = sum(p[0] for p in points) / len(points)
    cy = sum(p[1] for p in points) / len(points)
    return (cx, cy)


# ===== 유틸 함수들 =====
def xyxy_to_cxcywh(bb):
    x1, y1, x2, y2 = bb
    w = x2 - x1
    h = y2 - y1
    cx = (x1 + x2) * 0.5
    cy = (y1 + y2) * 0.5
    return np.array([cx, cy, w, h], dtype=np.float64)


def cxcywh_to_xyxy(v):
    cx, cy, w, h = [float(x) for x in v]
    return [cx - w*0.5, cy - h*0.5, cx + w*0.5, cy + h*0.5]


def iou_xyxy(a, b):
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    area = max(0.0, (ax2-ax1)*(ay2-ay1)) + max(0.0, (bx2-bx1)*(by2-by1)) - inter
    return inter / (area + 1e-9)


def ensure_positive_size(bb, eps=1.0):
    x1, y1, x2, y2 = bb
    if x2 <= x1: x2 = x1 + eps
    if y2 <= y1: y2 = y1 + eps
    return [x1, y1, x2, y2]


def clamp_aspect_box(cx, top, bottom, aspect, sw, sh):
    """9:16 비율로 클램프"""
    h = bottom - top
    w = h * aspect
    cx = float(cx)
    left, right = cx - w/2, cx + w/2
    if left < 0: cx += -left
    if right > sw: cx -= (right - sw)
    left, right = cx - w/2, cx + w/2
    if left < 0 or right > sw:
        w = min(w, sw)
        cx = max(w/2, min(sw - w/2, cx))
    return [cx - w/2, top, cx + w/2, bottom]


# ===== 칼만/RTS 필터 =====
def build_F(dt: float) -> np.ndarray:
    F = np.eye(8, dtype=np.float64)
    for i in range(4):
        F[i, i+4] = dt
    return F


def build_H() -> np.ndarray:
    H = np.zeros((4, 8), dtype=np.float64)
    H[0, 0] = H[1, 1] = H[2, 2] = H[3, 3] = 1.0
    return H


def rts_smoother(xs, Ps, x_preds, P_preds, Fs):
    T = len(xs)
    x_s = [None] * T
    P_s = [None] * T
    x_s[-1] = xs[-1].copy()
    P_s[-1] = Ps[-1].copy()
    for k in range(T-2, -1, -1):
        F = Fs[k+1]
        C = Ps[k] @ F.T @ np.linalg.inv(P_preds[k+1])
        x_s[k] = xs[k] + C @ (x_s[k+1] - x_preds[k+1])
        P_s[k] = Ps[k] + C @ (P_s[k+1] - P_preds[k+1]) @ C.T
    return x_s, P_s


# ===== 동일 인물 트래킹 =====
class PoseTracker:
    """OKS 기반 동일 인물 트래커"""
    
    def __init__(self, oks_threshold: float = 0.5, max_age: int = 10):
        self.oks_threshold = oks_threshold
        self.max_age = max_age
        self.target_keypoints: Optional[List[List[float]]] = None
        self.target_bbox: Optional[List[float]] = None
        self.age = 0
        
    def init(self, keypoints: List[List[float]], bbox: List[float]):
        self.target_keypoints = keypoints
        self.target_bbox = bbox
        self.age = 0
    
    def update(self, detections: List[Dict]) -> Optional[Dict]:
        """
        현재 프레임의 detections에서 target과 가장 유사한 것을 찾음
        
        Args:
            detections: [{"keypoints": [...], "x1", "y1", "x2", "y2", "score"}, ...]
        
        Returns:
            매칭된 detection 또는 None
        """
        if self.target_keypoints is None:
            # 첫 프레임: 가장 큰 detection 선택
            if not detections:
                return None
            best = max(detections, key=lambda d: (d["x2"]-d["x1"]) * (d["y2"]-d["y1"]))
            self.init(best["keypoints"], [best["x1"], best["y1"], best["x2"], best["y2"]])
            return best
        
        if not detections:
            self.age += 1
            if self.age > self.max_age:
                return None
            # 이전 bbox 유지
            return {"keypoints": self.target_keypoints, 
                    "x1": self.target_bbox[0], "y1": self.target_bbox[1],
                    "x2": self.target_bbox[2], "y2": self.target_bbox[3],
                    "score": 0.5, "interpolated": True}
        
        # OKS 기반 매칭
        target_area = max(1.0, (self.target_bbox[2] - self.target_bbox[0]) * 
                          (self.target_bbox[3] - self.target_bbox[1]))
        
        best_det = None
        best_score = 0.0
        
        for det in detections:
            kps = det.get("keypoints", [])
            if len(kps) < 17:
                continue
            
            # 상체 유사도 우선, 전체 OKS 보조
            torso_sim = compute_torso_similarity(self.target_keypoints, kps, target_area)
            oks = compute_oks(self.target_keypoints, kps, target_area)
            
            # 가중 평균 (상체 70%, 전체 30%)
            combined = 0.7 * torso_sim + 0.3 * oks
            
            if combined > best_score:
                best_score = combined
                best_det = det
        
        if best_det is not None and best_score >= self.oks_threshold:
            # 타겟 업데이트 (EMA)
            alpha = 0.3
            for i in range(17):
                if i < len(best_det["keypoints"]) and best_det["keypoints"][i][2] >= 0.3:
                    for j in range(2):  # x, y만 업데이트
                        self.target_keypoints[i][j] = (
                            (1 - alpha) * self.target_keypoints[i][j] + 
                            alpha * best_det["keypoints"][i][j]
                        )
            self.target_bbox = [best_det["x1"], best_det["y1"], 
                                best_det["x2"], best_det["y2"]]
            self.age = 0
            return best_det
        else:
            self.age += 1
            if self.age <= self.max_age:
                return {"keypoints": self.target_keypoints,
                        "x1": self.target_bbox[0], "y1": self.target_bbox[1],
                        "x2": self.target_bbox[2], "y2": self.target_bbox[3],
                        "score": 0.3, "interpolated": True}
            return None


# ===== 메인 파이프라인 =====
def process_pose_video(
    pose_log_path: str,
    output_json_path: str,
    oks_threshold: float = 0.5,
    q_pos_base: float = 25.0,
    q_vel_base: float = 200.0,
    r_meas_base: float = 35.0,
    aspect: float = 9/16,
):
    """
    YOLO11n-pose 로그를 처리하여 상체 기반 크롭 좌표 생성
    
    Args:
        pose_log_path: 입력 포즈 로그 경로 (JSONL)
        output_json_path: 출력 JSON 경로
        oks_threshold: OKS 매칭 임계치
        q_pos_base, q_vel_base, r_meas_base: 칼만필터 파라미터
        aspect: 출력 비율 (기본 9:16)
    """
    # 1) 로그 로드
    frames = []
    with open(pose_log_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                frames.append(json.loads(line))
    
    if not frames:
        print("❌ 로그가 비어있습니다")
        return
    
    T = len(frames)
    print(f"📊 총 {T} 프레임 로드됨")
    
    # 화면 크기 추출
    sw = frames[0].get("src_w", 1920)
    sh = frames[0].get("src_h", 1080)
    
    # 2) 동일 인물 트래킹
    tracker = PoseTracker(oks_threshold=oks_threshold)
    tracked_data = []
    
    for i, frame in enumerate(frames):
        dets = frame.get("detections", [])
        matched = tracker.update(dets)
        
        if matched:
            kps = matched.get("keypoints", [])
            torso_box = get_torso_bbox(kps)
            
            if torso_box:
                tracked_data.append({
                    "frame": frame.get("frame", i),
                    "pts_ms": frame.get("pts_ms", 0),
                    "bbox": torso_box,
                    "keypoints": kps,
                    "score": matched.get("score", 0),
                    "interpolated": matched.get("interpolated", False)
                })
            else:
                # 상체 bbox 실패 시 전체 bbox 사용
                tracked_data.append({
                    "frame": frame.get("frame", i),
                    "pts_ms": frame.get("pts_ms", 0),
                    "bbox": [matched["x1"], matched["y1"], matched["x2"], matched["y2"]],
                    "keypoints": kps,
                    "score": matched.get("score", 0),
                    "interpolated": True
                })
        else:
            # 트래킹 실패 - 이전 데이터 보간
            if tracked_data:
                prev = tracked_data[-1]
                tracked_data.append({
                    "frame": frame.get("frame", i),
                    "pts_ms": frame.get("pts_ms", 0),
                    "bbox": prev["bbox"],
                    "keypoints": prev["keypoints"],
                    "score": 0.0,
                    "interpolated": True
                })
    
    if not tracked_data:
        print("❌ 트래킹된 데이터가 없습니다")
        return
    
    print(f"✅ 트래킹 완료: {len(tracked_data)}/{T} 프레임")
    
    # 3) 칼만필터 + RTS 스무딩
    H = build_H()
    
    # 타임스탬프 추출 (ms → us 변환)
    timestamps = [d["pts_ms"] * 1000.0 for d in tracked_data]
    
    # 초기 상태
    first_bbox = tracked_data[0]["bbox"]
    cxcywh0 = xyxy_to_cxcywh(first_bbox)
    
    x = np.zeros((8, 1), dtype=np.float64)
    x[0:4, 0] = cxcywh0
    
    P = np.diag([
        (0.05 * sw)**2, (0.05 * sh)**2, (0.05 * sw)**2, (0.05 * sh)**2,
        (0.5 * sw)**2, (0.5 * sh)**2, (0.5 * sw)**2, (0.5 * sh)**2
    ])
    
    xs, Ps, x_preds, P_preds, Fs = [], [], [], [], []
    prev_ts = timestamps[0]
    
    for t, data in enumerate(tracked_data):
        ts = timestamps[t]
        dt = (ts - prev_ts) / 1e6 if t > 0 else 1/30.0
        dt = max(dt, 1/120.0)
        prev_ts = ts
        
        F = build_F(dt)
        Q = np.diag([
            (q_pos_base**2) * dt, (q_pos_base**2) * dt,
            (q_pos_base**2) * dt, (q_pos_base**2) * dt,
            (q_vel_base**2) * dt, (q_vel_base**2) * dt,
            (q_vel_base**2) * dt, (q_vel_base**2) * dt
        ])
        R = np.diag([r_meas_base**2] * 4)
        
        Fs.append(F)
        
        # 예측
        x_pred = F @ x
        P_pred = F @ P @ F.T + Q
        x_preds.append(x_pred.copy())
        P_preds.append(P_pred.copy())
        
        # 관측
        bbox = data["bbox"]
        z = xyxy_to_cxcywh(bbox).reshape(4, 1)
        
        # 보간된 프레임은 관측 신뢰도 낮춤
        if data.get("interpolated", False):
            R = R * 10.0
        
        # 칼만 업데이트
        y = z - (H @ x_pred)
        S = H @ P_pred @ H.T + R
        K = P_pred @ H.T @ np.linalg.inv(S)
        x = x_pred + K @ y
        P = (np.eye(8) - K @ H) @ P_pred
        
        xs.append(x.copy())
        Ps.append(P.copy())
    
    # RTS 스무딩
    x_s, P_s = rts_smoother(xs, Ps, x_preds, P_preds, Fs)
    
    # 4) 결과 생성
    output = []
    for t, data in enumerate(tracked_data):
        smoothed_cxcywh = x_s[t][0:4, 0]
        smoothed_bbox = cxcywh_to_xyxy(smoothed_cxcywh)
        smoothed_bbox = ensure_positive_size(smoothed_bbox)
        
        # 9:16 비율 적용
        cx = smoothed_cxcywh[0]
        top, bottom = smoothed_bbox[1], smoothed_bbox[3]
        aspect_bbox = clamp_aspect_box(cx, top, bottom, aspect, sw, sh)
        
        output.append({
            "frameIndex": data["frame"],
            "frame_ts": timestamps[t],
            "screenWidth": sw,
            "screenHeight": sh,
            "bbox": data["bbox"],
            "smoothed_bbox": smoothed_bbox,
            "aspect_ratio_bbox": ensure_positive_size(aspect_bbox),
            "keypoints": data["keypoints"],
            "score": data["score"],
            "interpolated": data.get("interpolated", False)
        })
    
    # 5) 저장
    with open(output_json_path, 'w', encoding='utf-8') as f:
        json.dump(output, f, indent=2, ensure_ascii=False)
    
    print(f"✅ 저장 완료: {output_json_path}")


if __name__ == "__main__":
    import sys
    if len(sys.argv) >= 3:
        process_pose_video(sys.argv[1], sys.argv[2])
    else:
        print("Usage: python make_pose_log_pipeline.py <pose_log.jsonl> <output.json>")

