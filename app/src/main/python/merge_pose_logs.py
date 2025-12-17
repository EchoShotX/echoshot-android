"""
YOLO11n-pose 로그 병합 파이프라인

두 개의 로그를 병합:
1. pose_log.jsonl (PoseDetectLogManager 출력 - 오프라인 포즈 감지)
2. tracking_log.json (실시간 녹화 중 저장된 트래킹 로그)

병합 전략:
- OKS/상체 유사도 기반으로 동일 인물 매칭
- 두 소스를 보완하여 더 정확한 트래킹 수행
- 칼만필터 스무딩 적용
"""

import json
import math
from copy import deepcopy
from typing import Optional, List, Dict, Any, Tuple
import numpy as np

# COCO Keypoint 시그마
KEYPOINT_SIGMAS = np.array([
    0.026, 0.025, 0.025, 0.035, 0.035,
    0.079, 0.079, 0.072, 0.072,
    0.062, 0.062,
    0.107, 0.107,
    0.087, 0.087,
    0.089, 0.089
])

TORSO_INDICES = [0, 1, 2, 3, 4, 5, 6, 11, 12]


def normalize_skeleton(kps: List, min_conf: float = 0.3) -> Optional[List]:
    """
    스켈레톤을 bbox 기준으로 정규화 (0~1 범위)
    위치와 크기에 무관하게 체형 비교 가능
    """
    if len(kps) < 17:
        return None
    
    valid_x, valid_y = [], []
    for kp in kps:
        if len(kp) >= 3 and kp[2] >= min_conf:
            valid_x.append(kp[0])
            valid_y.append(kp[1])
    
    if len(valid_x) < 3:
        return None
    
    x_min, x_max = min(valid_x), max(valid_x)
    y_min, y_max = min(valid_y), max(valid_y)
    w = max(x_max - x_min, 1.0)
    h = max(y_max - y_min, 1.0)
    
    norm_kps = []
    for kp in kps:
        if len(kp) >= 3 and kp[2] >= min_conf:
            nx = (kp[0] - x_min) / w
            ny = (kp[1] - y_min) / h
            norm_kps.append([nx, ny, kp[2]])
        else:
            norm_kps.append([0.5, 0.5, 0.0])
    
    return norm_kps


def compute_skeleton_shape_similarity(kps1: List, kps2: List, min_conf: float = 0.3) -> float:
    """
    정규화된 스켈레톤 형태 유사도 (위치/크기 무관, 체형만 비교)
    """
    norm1 = normalize_skeleton(kps1, min_conf)
    norm2 = normalize_skeleton(kps2, min_conf)
    
    if norm1 is None or norm2 is None:
        return 0.0
    
    sim_sum = 0.0
    count = 0
    
    for i in range(17):
        if norm1[i][2] < min_conf or norm2[i][2] < min_conf:
            continue
        
        dx = norm1[i][0] - norm2[i][0]
        dy = norm1[i][1] - norm2[i][1]
        d = math.sqrt(dx * dx + dy * dy)
        
        sim = max(0.0, 1.0 - d / 0.5)
        sim_sum += sim
        count += 1
    
    return sim_sum / count if count > 0 else 0.0


def compute_body_proportions(kps: List, min_conf: float = 0.3) -> Optional[Dict]:
    """
    체형 비율 특징 (어깨 너비 기준으로 정규화)
    """
    if len(kps) < 17:
        return None
    
    def get_point(idx):
        if idx < len(kps) and len(kps[idx]) >= 3 and kps[idx][2] >= min_conf:
            return (kps[idx][0], kps[idx][1])
        return None
    
    def dist(p1, p2):
        if p1 is None or p2 is None:
            return None
        return math.sqrt((p1[0] - p2[0])**2 + (p1[1] - p2[1])**2)
    
    left_shoulder, right_shoulder = get_point(5), get_point(6)
    left_hip, right_hip = get_point(11), get_point(12)
    left_eye, right_eye = get_point(1), get_point(2)
    
    shoulder_width = dist(left_shoulder, right_shoulder)
    hip_width = dist(left_hip, right_hip)
    eye_distance = dist(left_eye, right_eye)
    
    if left_shoulder and right_shoulder and left_hip and right_hip:
        sc = ((left_shoulder[0] + right_shoulder[0]) / 2, (left_shoulder[1] + right_shoulder[1]) / 2)
        hc = ((left_hip[0] + right_hip[0]) / 2, (left_hip[1] + right_hip[1]) / 2)
        torso_length = dist(sc, hc)
    else:
        torso_length = None
    
    if shoulder_width and shoulder_width > 10:
        return {
            'hip_shoulder': hip_width / shoulder_width if hip_width else None,
            'torso_shoulder': torso_length / shoulder_width if torso_length else None,
            'eye_shoulder': eye_distance / shoulder_width if eye_distance else None,
        }
    return None


def compare_body_proportions(props1: Optional[Dict], props2: Optional[Dict]) -> float:
    """체형 비율 유사도 (0~1)"""
    if props1 is None or props2 is None:
        return 0.5
    
    sim_sum = 0.0
    count = 0
    
    for key in ['hip_shoulder', 'torso_shoulder', 'eye_shoulder']:
        v1, v2 = props1.get(key), props2.get(key)
        if v1 is not None and v2 is not None:
            diff = abs(v1 - v2)
            sim = max(0.0, 1.0 - diff / 0.3)
            sim_sum += sim
            count += 1
    
    return sim_sum / count if count > 0 else 0.5


def compute_oks(kps1: List, kps2: List, area: float) -> float:
    """OKS - 절대 좌표 기반 (위치 연속성용)"""
    if len(kps1) != 17 or len(kps2) != 17:
        return 0.0
    
    oks_sum = 0.0
    count = 0
    area = max(1.0, area)
    
    for i in range(17):
        c1 = kps1[i][2] if len(kps1[i]) >= 3 else 0
        c2 = kps2[i][2] if len(kps2[i]) >= 3 else 0
        
        if c1 < 0.3 or c2 < 0.3:
            continue
        
        dx = kps1[i][0] - kps2[i][0]
        dy = kps1[i][1] - kps2[i][1]
        d2 = dx * dx + dy * dy
        
        sigma = KEYPOINT_SIGMAS[i]
        k2 = 2 * sigma * sigma
        
        oks_sum += math.exp(-d2 / (k2 * area))
        count += 1
    
    return oks_sum / count if count > 0 else 0.0


def compute_torso_similarity(kps1: List, kps2: List, area: float) -> float:
    """상체 유사도 - 절대 좌표 기반"""
    if len(kps1) < 17 or len(kps2) < 17:
        return 0.0
    
    sim_sum = 0.0
    count = 0
    area = max(1.0, area)
    
    for i in TORSO_INDICES:
        c1 = kps1[i][2] if len(kps1[i]) >= 3 else 0
        c2 = kps2[i][2] if len(kps2[i]) >= 3 else 0
        
        if c1 < 0.3 or c2 < 0.3:
            continue
        
        dx = kps1[i][0] - kps2[i][0]
        dy = kps1[i][1] - kps2[i][1]
        d2 = dx * dx + dy * dy
        
        sigma = KEYPOINT_SIGMAS[i]
        k2 = 2 * sigma * sigma
        
        sim_sum += math.exp(-d2 / (k2 * area))
        count += 1
    
    return sim_sum / count if count > 0 else 0.0


def get_torso_bbox(keypoints: List, min_conf: float = 0.3) -> Optional[List[float]]:
    """상체 관절로 bbox 계산 (중심을 아래로 40% 이동)"""
    valid = []
    for i in TORSO_INDICES:
        if i >= len(keypoints):
            continue
        kp = keypoints[i]
        if len(kp) >= 3 and kp[2] >= min_conf:
            valid.append((kp[0], kp[1]))
    
    if len(valid) < 3:
        return None
    
    xs = [p[0] for p in valid]
    ys = [p[1] for p in valid]
    x1, y1, x2, y2 = min(xs), min(ys), max(xs), max(ys)
    
    # 상체 bbox 중심을 아래로 이동 (위:아래 = 3:7 비율)
    # bbox 높이의 40%만큼 아래로 이동하면 하체가 덜 잘림
    h = y2 - y1
    offset = h * 0.4  # 중심을 아래로 40% 이동
    
    return [x1, y1 + offset, x2, y2 + offset]


def iou_xyxy(a: List, b: List) -> float:
    """IoU 계산"""
    ax1, ay1, ax2, ay2 = a[:4]
    bx1, by1, bx2, by2 = b[:4]
    
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    
    iw = max(0, ix2 - ix1)
    ih = max(0, iy2 - iy1)
    inter = iw * ih
    
    area_a = max(0, (ax2 - ax1) * (ay2 - ay1))
    area_b = max(0, (bx2 - bx1) * (by2 - by1))
    union = area_a + area_b - inter
    
    return inter / (union + 1e-9)


def box_area(box: List) -> float:
    return max(0.0, (box[2] - box[0]) * (box[3] - box[1]))


def center_unzoom(box: List, W: float, H: float, z: float) -> List[float]:
    """줌 역변환"""
    x1, y1, x2, y2 = box[:4]
    z = float(z) if z else 1.0
    if abs(z) < 1e-9:
        z = 1.0
    
    cx, cy = W * 0.5, H * 0.5
    
    def unz(x, c):
        return c - (c - x) / z
    
    nx1, ny1 = unz(x1, cx), unz(y1, cy)
    nx2, ny2 = unz(x2, cx), unz(y2, cy)
    
    x1p, x2p = sorted([nx1, nx2])
    y1p, y2p = sorted([ny1, ny2])
    
    x1p = max(0, min(W, x1p))
    x2p = max(0, min(W, x2p))
    y1p = max(0, min(H, y1p))
    y2p = max(0, min(H, y2p))
    
    return [x1p, y1p, x2p, y2p]


def enforce_9x16(box: List, W: float, H: float) -> List[float]:
    """9:16 비율 강제"""
    x1, y1, x2, y2 = box[:4]
    cx = (x1 + x2) * 0.5
    cy = (y1 + y2) * 0.5
    h = max(2.0, y2 - y1)
    target_w = h * 9.0 / 16.0
    
    if target_w > W:
        target_w = W
        h = target_w * 16.0 / 9.0
    if h > H:
        h = H
        target_w = h * 9.0 / 16.0
    
    x1n = cx - target_w * 0.5
    x2n = cx + target_w * 0.5
    y1n = cy - h * 0.5
    y2n = cy + h * 0.5
    
    if x1n < 0:
        x2n -= x1n
        x1n = 0
    if x2n > W:
        x1n -= (x2n - W)
        x2n = W
    if y1n < 0:
        y2n -= y1n
        y1n = 0
    if y2n > H:
        y1n -= (y2n - H)
        y2n = H
    
    return [x1n, y1n, x2n, y2n]


class PoseTracker:
    """
    OKS + 위치 연속성 기반 트래커
    
    동일 인물을 지속적으로 트래킹하기 위해:
    1. 스켈레톤 유사도 (OKS/상체)
    2. 위치 연속성 (앵커 중심과의 거리)
    3. 크기 연속성 (박스 크기 비율)
    를 종합적으로 고려
    """
    
    def __init__(self, oks_thresh: float = 0.4, max_age: int = 30):
        self.oks_thresh = oks_thresh
        self.max_age = max_age
        self.anchor_kps: Optional[List] = None
        self.anchor_box: Optional[List] = None
        self.anchor_center: Optional[Tuple[float, float]] = None
        self.anchor_props: Optional[Dict] = None  # 체형 비율
        self.age = 0
        self.alpha = 0.3  # EMA 계수
        self.initialized = False
    
    def _box_center(self, box: List) -> Tuple[float, float]:
        """박스 중심점"""
        return ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2)
    
    def _center_distance(self, box1: List, box2: List) -> float:
        """두 박스 중심간 거리"""
        c1 = self._box_center(box1)
        c2 = self._box_center(box2)
        return math.sqrt((c1[0] - c2[0])**2 + (c1[1] - c2[1])**2)
    
    def _size_ratio(self, box1: List, box2: List) -> float:
        """크기 비율 (0~1, 1이면 같은 크기)"""
        a1 = box_area(box1)
        a2 = box_area(box2)
        if a1 < 1 or a2 < 1:
            return 0.0
        ratio = min(a1, a2) / max(a1, a2)
        return ratio
    
    def _compute_combined_similarity(self, det: Dict) -> float:
        """
        종합 유사도 계산 (진짜 pose 기반!):
        - 35%: 정규화된 스켈레톤 형태 유사도 (체형 비교, 위치 무관)
        - 25%: 체형 비율 유사도 (어깨/엉덩이/상체 비율)
        - 20%: 위치 연속성 (가까울수록 높음)
        - 20%: 절대 좌표 OKS (빠른 움직임 필터링)
        """
        kps = det.get("keypoints", [])
        if len(kps) < 17:
            return 0.0
        
        det_box = [det["x1"], det["y1"], det["x2"], det["y2"]]
        area = box_area(self.anchor_box) if self.anchor_box else 10000
        
        # 1. 정규화된 스켈레톤 형태 유사도 (위치/크기 무관, 체형만!)
        shape_sim = compute_skeleton_shape_similarity(self.anchor_kps, kps)
        
        # 2. 체형 비율 유사도 (어깨 너비 기준)
        anchor_props = compute_body_proportions(self.anchor_kps)
        det_props = compute_body_proportions(kps)
        proportion_sim = compare_body_proportions(anchor_props, det_props)
        
        # 3. 위치 연속성 (갑자기 멀리 점프하면 낮음)
        if self.anchor_box:
            dist = self._center_distance(self.anchor_box, det_box)
            diag = math.sqrt(area) if area > 0 else 100
            max_dist = diag * 3  # 박스 대각선의 3배까지 허용
            pos_sim = max(0.0, 1.0 - dist / max_dist)
        else:
            pos_sim = 0.5
        
        # 4. 절대 좌표 OKS (너무 빨리 움직이는 것 필터링)
        oks = compute_oks(self.anchor_kps, kps, area)
        
        # 종합: 체형 비교 비중 높임 (60%), 위치는 보조 (40%)
        combined = 0.35 * shape_sim + 0.25 * proportion_sim + 0.20 * pos_sim + 0.20 * oks
        
        return combined
    
    def match(self, dets: List[Dict]) -> Tuple[Optional[Dict], float]:
        """가장 유사한 detection 찾기 (위치 연속성 포함)"""
        if not dets:
            self.age += 1
            return None, 0.0
        
        # 첫 프레임: 화면 중앙에 가장 가까운 detection 선택
        if not self.initialized:
            # 화면 중앙 (src_w, src_h가 없으면 추정)
            all_boxes = [[d["x1"], d["y1"], d["x2"], d["y2"]] for d in dets]
            
            # 모든 박스의 평균 중심을 화면 중앙으로 추정
            avg_cx = sum((b[0]+b[2])/2 for b in all_boxes) / len(all_boxes)
            avg_cy = sum((b[1]+b[3])/2 for b in all_boxes) / len(all_boxes)
            
            # 중앙에 가장 가깝고 크기도 적당한 detection
            def score(d):
                box = [d["x1"], d["y1"], d["x2"], d["y2"]]
                cx, cy = (box[0]+box[2])/2, (box[1]+box[3])/2
                dist = math.sqrt((cx - avg_cx)**2 + (cy - avg_cy)**2)
                area = box_area(box)
                # 중앙에 가깝고 크기가 큰 것 선호
                return -dist + math.sqrt(area) * 0.5
            
            best = max(dets, key=score)
            self._update_anchor(best)
            self.initialized = True
            self.age = 0
            return best, 1.0
        
        # 앵커가 너무 오래되면 → 체형이 가장 유사한 detection으로 리셋
        if self.age > self.max_age:
            best = None
            best_score = 0.0
            
            for det in dets:
                kps = det.get("keypoints", [])
                if len(kps) < 17:
                    continue
                
                # 체형 유사도로 선택 (위치 무관)
                score = 0.0
                
                # 정규화된 스켈레톤 형태
                if self.anchor_kps:
                    score += compute_skeleton_shape_similarity(self.anchor_kps, kps) * 0.5
                
                # 체형 비율
                if self.anchor_props:
                    det_props = compute_body_proportions(kps)
                    score += compare_body_proportions(self.anchor_props, det_props) * 0.3
                
                # 위치 (보조)
                if self.anchor_box:
                    det_box = [det["x1"], det["y1"], det["x2"], det["y2"]]
                    dist = self._center_distance(self.anchor_box, det_box)
                    area = box_area(self.anchor_box)
                    diag = math.sqrt(area) if area > 0 else 100
                    pos_score = max(0.0, 1.0 - dist / (diag * 5))  # 거리 여유롭게
                    score += pos_score * 0.2
                
                if score > best_score:
                    best_score = score
                    best = det
            
            if best is None:
                best = max(dets, key=lambda d: box_area([d["x1"], d["y1"], d["x2"], d["y2"]]))
            
            self._update_anchor(best)
            self.age = 0
            return best, best_score
        
        # 종합 유사도로 매칭
        best_det = None
        best_sim = 0.0
        
        for det in dets:
            sim = self._compute_combined_similarity(det)
            if sim > best_sim:
                best_sim = sim
                best_det = det
        
        if best_det and best_sim >= self.oks_thresh:
            self._update_anchor(best_det)
            self.age = 0
            return best_det, best_sim
        elif best_det and best_sim >= self.oks_thresh * 0.6:
            # 임계값의 60% 이상이면 느리게 업데이트
            self._update_anchor_slow(best_det)
            self.age += 1
            return best_det, best_sim
        else:
            self.age += 1
            return None, best_sim
    
    def _update_anchor(self, det: Dict):
        """앵커 업데이트 (alpha EMA)"""
        kps = det.get("keypoints", [])
        if len(kps) >= 17:
            if self.anchor_kps is None:
                self.anchor_kps = deepcopy(kps)
            else:
                for i in range(17):
                    if kps[i][2] >= 0.3:
                        self.anchor_kps[i][0] = (1 - self.alpha) * self.anchor_kps[i][0] + self.alpha * kps[i][0]
                        self.anchor_kps[i][1] = (1 - self.alpha) * self.anchor_kps[i][1] + self.alpha * kps[i][1]
            
            # 체형 비율 업데이트
            new_props = compute_body_proportions(kps)
            if new_props:
                self.anchor_props = new_props
        
        self.anchor_box = [det["x1"], det["y1"], det["x2"], det["y2"]]
    
    def _update_anchor_slow(self, det: Dict):
        """앵커 느린 업데이트 (alpha=0.05, 드리프트 방지)"""
        kps = det.get("keypoints", [])
        slow_alpha = 0.05  # 느린 업데이트
        
        if len(kps) >= 17 and self.anchor_kps is not None:
            for i in range(17):
                if kps[i][2] >= 0.3:
                    self.anchor_kps[i][0] = (1 - slow_alpha) * self.anchor_kps[i][0] + slow_alpha * kps[i][0]
                    self.anchor_kps[i][1] = (1 - slow_alpha) * self.anchor_kps[i][1] + slow_alpha * kps[i][1]
        
        # 박스도 느리게 업데이트
        if self.anchor_box is not None:
            self.anchor_box[0] = (1 - slow_alpha) * self.anchor_box[0] + slow_alpha * det["x1"]
            self.anchor_box[1] = (1 - slow_alpha) * self.anchor_box[1] + slow_alpha * det["y1"]
            self.anchor_box[2] = (1 - slow_alpha) * self.anchor_box[2] + slow_alpha * det["x2"]
            self.anchor_box[3] = (1 - slow_alpha) * self.anchor_box[3] + slow_alpha * det["y2"]


def linear_interpolate_boxes(series: List[Optional[Dict]], key: str = "bbox") -> List[Dict]:
    """None인 프레임을 선형 보간"""
    T = len(series)
    if T == 0:
        return []
    
    # 기본 bbox 생성 함수
    def make_default(frame_idx: int) -> Dict:
        return {
            "frame": frame_idx,
            "pts_ms": 0,
            "src_w": 1920,
            "src_h": 1080,
            "screenWidth": 1920,
            "screenHeight": 1080,
            "zoom": 1.0,
            "bbox": [100, 200, 300, 600],
            "keypoints": [],
            "score": 0.0,
            "similarity": 0.0
        }
    
    out = [None] * T
    
    valid_idx = [i for i, s in enumerate(series) if s is not None and key in s]
    if not valid_idx:
        # 유효한 데이터가 없으면 기본값으로 채움
        return [make_default(i) for i in range(T)]
    
    # 앞쪽 채우기
    first = valid_idx[0]
    for i in range(first):
        out[i] = deepcopy(series[first])
        out[i]["frame"] = i
    
    # 보간
    for s, e in zip(valid_idx, valid_idx[1:]):
        out[s] = deepcopy(series[s])
        span = e - s
        for t in range(1, span):
            r = t / span
            bbox_s = series[s][key]
            bbox_e = series[e][key]
            out[s + t] = deepcopy(series[s])
            out[s + t]["frame"] = s + t
            out[s + t][key] = [
                bbox_s[0] * (1 - r) + bbox_e[0] * r,
                bbox_s[1] * (1 - r) + bbox_e[1] * r,
                bbox_s[2] * (1 - r) + bbox_e[2] * r,
                bbox_s[3] * (1 - r) + bbox_e[3] * r,
            ]
    
    # 마지막 유효 인덱스 채우기
    last = valid_idx[-1]
    out[last] = deepcopy(series[last])
    
    # 뒤쪽 채우기
    for i in range(last + 1, T):
        out[i] = deepcopy(series[last])
        out[i]["frame"] = i
    
    # None이 남아있으면 기본값으로 채움
    for i in range(T):
        if out[i] is None:
            if i > 0 and out[i-1] is not None:
                out[i] = deepcopy(out[i-1])
                out[i]["frame"] = i
            else:
                out[i] = make_default(i)
    
    return out


def remove_spike_noise(series: List[Optional[Dict]], key: str = "bbox", 
                       window: int = 7, 
                       pos_threshold: float = 0.15,
                       size_threshold: float = 0.25,
                       velocity_threshold: float = 0.1) -> List[Optional[Dict]]:
    """
    강력한 노이즈 제거:
    1. 위치 스파이크: 주변 평균과 급격히 다른 위치
    2. 크기 스파이크: 주변 평균과 급격히 다른 크기
    3. 속도 스파이크: 이전 프레임 대비 너무 빠른 이동
    
    사람은 순간이동 못함! 급격한 변화는 모두 노이즈
    """
    T = len(series)
    if T < window:
        return series
    
    result = [deepcopy(s) if s else None for s in series]
    half = window // 2
    
    pos_removed = 0
    size_removed = 0
    velocity_removed = 0
    
    # 1차: 위치/크기 스파이크 제거
    for i in range(T):
        if result[i] is None or key not in result[i]:
            continue
        
        curr_bbox = result[i][key]
        if curr_bbox is None:
            continue
        
        curr_cx = (curr_bbox[0] + curr_bbox[2]) / 2
        curr_cy = (curr_bbox[1] + curr_bbox[3]) / 2
        curr_w = curr_bbox[2] - curr_bbox[0]
        curr_h = curr_bbox[3] - curr_bbox[1]
        curr_area = curr_w * curr_h
        curr_diag = math.sqrt(curr_w**2 + curr_h**2)
        
        if curr_diag < 10:
            continue
        
        # 주변 프레임 수집
        neighbors_cx, neighbors_cy, neighbors_area = [], [], []
        
        for j in range(max(0, i - half), min(T, i + half + 1)):
            if j == i:
                continue
            if result[j] is None or key not in result[j]:
                continue
            nb = result[j][key]
            if nb is None:
                continue
            neighbors_cx.append((nb[0] + nb[2]) / 2)
            neighbors_cy.append((nb[1] + nb[3]) / 2)
            neighbors_area.append((nb[2] - nb[0]) * (nb[3] - nb[1]))
        
        if len(neighbors_cx) < 2:
            continue
        
        # 주변 평균
        avg_cx = sum(neighbors_cx) / len(neighbors_cx)
        avg_cy = sum(neighbors_cy) / len(neighbors_cy)
        avg_area = sum(neighbors_area) / len(neighbors_area)
        
        # 위치 스파이크: 주변 평균과의 거리가 대각선의 15% 초과
        pos_dist = math.sqrt((curr_cx - avg_cx)**2 + (curr_cy - avg_cy)**2)
        if pos_dist > curr_diag * pos_threshold:
            result[i] = None
            pos_removed += 1
            continue
        
        # 크기 스파이크: 면적 차이가 25% 초과
        if avg_area > 0:
            area_ratio = abs(curr_area - avg_area) / avg_area
            if area_ratio > size_threshold:
                result[i] = None
                size_removed += 1
                continue
    
    # 2차: 속도 스파이크 제거 (이전 프레임 대비 너무 빠른 이동)
    prev_cx, prev_cy, prev_diag = None, None, None
    
    for i in range(T):
        if result[i] is None or key not in result[i]:
            prev_cx, prev_cy, prev_diag = None, None, None
            continue
        
        curr_bbox = result[i][key]
        if curr_bbox is None:
            prev_cx, prev_cy, prev_diag = None, None, None
            continue
        
        curr_cx = (curr_bbox[0] + curr_bbox[2]) / 2
        curr_cy = (curr_bbox[1] + curr_bbox[3]) / 2
        curr_w = curr_bbox[2] - curr_bbox[0]
        curr_h = curr_bbox[3] - curr_bbox[1]
        curr_diag = math.sqrt(curr_w**2 + curr_h**2)
        
        if prev_cx is not None and prev_diag is not None:
            # 프레임간 이동 거리
            frame_dist = math.sqrt((curr_cx - prev_cx)**2 + (curr_cy - prev_cy)**2)
            
            # 1프레임에 대각선의 10% 이상 이동하면 순간이동 → 노이즈
            if frame_dist > prev_diag * velocity_threshold:
                result[i] = None
                velocity_removed += 1
                prev_cx, prev_cy, prev_diag = None, None, None
                continue
        
        prev_cx, prev_cy, prev_diag = curr_cx, curr_cy, curr_diag
    
    total_removed = pos_removed + size_removed + velocity_removed
    if total_removed > 0:
        print(f"📊 노이즈 제거: 총 {total_removed}개 (위치:{pos_removed}, 크기:{size_removed}, 속도:{velocity_removed})")
    
    return result


def moving_average_smooth(series: List[Dict], key: str = "bbox", k: int = 5) -> List[Dict]:
    """
    이동평균으로 bbox 스무딩 (홀수 k 사용)
    
    Args:
        series: 보간된 시리즈
        key: bbox 키 이름
        k: 윈도우 크기 (홀수로 자동 조정)
    
    Returns:
        스무딩된 시리즈
    """
    if k < 2:
        return series
    if k % 2 == 0:
        k += 1  # 홀수로 만들기
    
    T = len(series)
    half = k // 2
    out = []
    
    for i in range(T):
        if series[i] is None:
            out.append(None)
            continue
        
        # 윈도우 내 유효 bbox 수집
        bboxes = []
        for j in range(max(0, i - half), min(T, i + half + 1)):
            if series[j] is not None and key in series[j] and series[j][key]:
                bboxes.append(series[j][key])
        
        if not bboxes:
            out.append(deepcopy(series[i]))
            continue
        
        # 각 좌표별 평균
        avg_bbox = [
            sum(b[0] for b in bboxes) / len(bboxes),
            sum(b[1] for b in bboxes) / len(bboxes),
            sum(b[2] for b in bboxes) / len(bboxes),
            sum(b[3] for b in bboxes) / len(bboxes),
        ]
        
        result = deepcopy(series[i])
        result[key] = avg_bbox
        out.append(result)
    
    return out


def merge_pose_logs(
    pose_log_path: str,
    processed_log_path: str,  # make_log_pipeline에서 생성된 processed.json (모든 프레임 zoom 보간됨)
    output_path: str,
    oks_thresh: float = 0.4,
):
    """
    두 로그를 병합하여 최종 크롭 좌표 생성
    
    Args:
        pose_log_path: YOLO11n-pose 로그 (JSONL)
        processed_log_path: make_log_pipeline에서 생성된 processed.json (모든 프레임 zoom 보간됨)
        output_path: 출력 경로 (JSONL)
        oks_thresh: OKS 매칭 임계치
    """
    
    # 1) 포즈 로그 로드
    pose_frames = []
    with open(pose_log_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                pose_frames.append(json.loads(line))
    
    # 2) processed.json 로드 (make_log_pipeline 출력, 모든 프레임 zoom 보간됨)
    # 형식: [{"frameIndex": 0, "zoom": 1.5, "screenWidth": 1080, ...}, ...]
    with open(processed_log_path, 'r', encoding='utf-8') as f:
        txt = f.read().strip()
        if txt.startswith('['):
            processed_data = json.loads(txt)
        else:
            processed_data = [json.loads(l) for l in txt.splitlines() if l.strip()]
    
    # frameIndex로 매핑 (모든 프레임에 zoom이 있음)
    processed_map = {}
    for p in processed_data:
        idx = p.get("frameIndex") or p.get("frame")
        if idx is not None:
            processed_map[int(idx)] = p
    
    print(f"📊 포즈 로그: {len(pose_frames)} 프레임")
    print(f"📊 processed 로그: {len(processed_data)} 프레임, 매핑: {len(processed_map)}")
    
    # zoom 값 샘플 확인
    zoom_samples = [(p.get("frameIndex"), p.get("zoom")) 
                    for p in processed_data[:5] if p.get("zoom") is not None]
    print(f"📊 processed zoom 샘플 (frameIndex, zoom): {zoom_samples}")
    
    # processed 전체 zoom 분포
    all_zooms = [p.get("zoom") for p in processed_data]
    zoom_not_none = sum(1 for z in all_zooms if z is not None)
    zoom_not_one = sum(1 for z in all_zooms if z is not None and abs(z - 1.0) > 0.01)
    print(f"📊 processed zoom 분포: 총 {len(all_zooms)}, not None={zoom_not_none}, !=1.0={zoom_not_one}")
    
    # pose_log frame 범위
    pose_frames_idx = [f.get("frame", i) for i, f in enumerate(pose_frames)]
    min_frame = min(pose_frames_idx) if pose_frames_idx else 0
    max_frame = max(pose_frames_idx) if pose_frames_idx else 0
    print(f"📊 pose_log frame 범위: {min_frame} ~ {max_frame}")
    
    # processed_map 키 범위
    if processed_map:
        print(f"📊 processed_map 키 범위: {min(processed_map.keys())} ~ {max(processed_map.keys())}")
    
    # 첫 프레임이 0이 아니면 경고 및 0부터 시작하도록 패딩
    if min_frame > 0:
        print(f"⚠️ pose_log가 frame {min_frame}부터 시작합니다. frame 0~{min_frame-1}을 패딩합니다.")
        # 첫 프레임의 데이터를 기반으로 앞쪽 프레임 생성
        first_pose = pose_frames[0]
        for pad_frame in range(min_frame):
            padded = deepcopy(first_pose)
            padded["frame"] = pad_frame
            padded["pts_ms"] = pad_frame * 33  # 약 30fps 가정
            pose_frames.insert(pad_frame, padded)
        print(f"📊 패딩 후 pose_frames 크기: {len(pose_frames)}")
    
    def get_processed_for_frame(frame_idx):
        """프레임 인덱스로 processed 데이터 가져오기"""
        return processed_map.get(frame_idx, {})
    
    
    if not pose_frames:
        print("❌ 포즈 로그가 비어있습니다")
        return
    
    # 기본 해상도
    src_w = pose_frames[0].get("src_w", 1920)
    src_h = pose_frames[0].get("src_h", 1080)
    
    # 3) OKS 기반 트래킹
    tracker = PoseTracker(oks_thresh=oks_thresh)
    tracked = []
    
    # 트래킹 통계
    match_count = 0
    slow_update_count = 0
    reset_count = 0
    none_count = 0
    
    for i, frame in enumerate(pose_frames):
        frame_idx = frame.get("frame", i)
        pts_ms = frame.get("pts_ms", 0)
        dets = frame.get("detections", [])
        
        matched, sim = tracker.match(dets)
        
        # 트래킹 상태 확인
        if matched and sim >= oks_thresh:
            match_count += 1
        elif matched and sim >= oks_thresh * 0.5:
            slow_update_count += 1
        elif tracker.age == 0 and matched:  # 리셋된 경우
            reset_count += 1
        else:
            none_count += 1
        
        # processed 로그에서 보조 정보 가져오기 (프레임 인덱스 기반, 모든 프레임 보간됨)
        pr = get_processed_for_frame(frame_idx)
        zoom = pr.get("zoom", 1.0) or 1.0
        screen_w = pr.get("screenWidth", src_w) or src_w
        screen_h = pr.get("screenHeight", src_h) or src_h
        
        if matched:
            kps = matched.get("keypoints", [])
            torso_box = get_torso_bbox(kps)
            
            if torso_box:
                tracked.append({
                    "frame": frame_idx,
                    "pts_ms": frame.get("pts_ms", 0),
                    "src_w": src_w,
                    "src_h": src_h,
                    "screenWidth": screen_w,
                    "screenHeight": screen_h,
                    "zoom": zoom,
                    "bbox": torso_box,
                    "keypoints": kps,
                    "score": matched.get("score", 0),
                    "similarity": sim
                })
            else:
                tracked.append({
                    "frame": frame_idx,
                    "pts_ms": frame.get("pts_ms", 0),
                    "src_w": src_w,
                    "src_h": src_h,
                    "screenWidth": screen_w,
                    "screenHeight": screen_h,
                    "zoom": zoom,
                    "bbox": [matched["x1"], matched["y1"], matched["x2"], matched["y2"]],
                    "keypoints": kps,
                    "score": matched.get("score", 0),
                    "similarity": sim
                })
        else:
            tracked.append(None)  # 보간 필요
    
    # 트래킹 통계 출력
    total = len(pose_frames)
    valid_tracked = sum(1 for t in tracked if t is not None)
    print(f"📊 트래킹 결과: 총 {total} 프레임")
    print(f"   - 정상 매칭: {match_count} ({100*match_count/total:.1f}%)")
    print(f"   - 느린 업데이트: {slow_update_count}")
    print(f"   - 리셋: {reset_count}")
    print(f"   - 매칭 실패: {none_count}")
    print(f"   - 유효 트래킹: {valid_tracked}/{total}")
    
    # 3.5) 첫 프레임 zoom 불연속 보정
    # 녹화 시작 시 zoom이 1.0이었다가 바로 변경되는 경우, 첫 몇 프레임의 crop이 이상해짐
    # 안정적인 zoom 값 찾기 (처음 10프레임 중 가장 많이 나오는 값)
    zoom_values = []
    for t in tracked[:min(30, len(tracked))]:
        if t is not None and t.get("zoom"):
            zoom_values.append(round(t["zoom"], 2))  # 소수점 2자리로 반올림
    
    if zoom_values:
        # 가장 많이 나오는 zoom 값 찾기
        from collections import Counter
        zoom_counter = Counter(zoom_values)
        stable_zoom = zoom_counter.most_common(1)[0][0]
        
        # 첫 프레임들의 zoom이 stable_zoom과 크게 다르면 보정
        zoom_fixed_count = 0
        for i, t in enumerate(tracked[:10]):  # 첫 10프레임만 검사
            if t is not None:
                current_zoom = t.get("zoom", 1.0)
                # zoom이 50% 이상 차이나면 보정
                if abs(current_zoom - stable_zoom) / max(stable_zoom, 0.1) > 0.3:
                    t["zoom"] = stable_zoom
                    zoom_fixed_count += 1
        
        if zoom_fixed_count > 0:
            print(f"⚠️ 첫 {zoom_fixed_count}개 프레임의 zoom을 {stable_zoom}으로 보정")
    
    # 4) 초강력 노이즈 제거
    tracked = remove_spike_noise(
        tracked, key="bbox",
        window=11,             # 전후 5프레임씩 비교 (더 넓게)
        pos_threshold=0.08,    # 위치: 대각선의 8% 초과하면 제거 (더 엄격)
        size_threshold=0.15,   # 크기: 15% 초과 차이나면 제거 (더 엄격)
        velocity_threshold=0.05  # 속도: 1프레임에 5% 이상 이동하면 제거 (더 엄격)
    )
    
    # 4.5) None 보간
    tracked = linear_interpolate_boxes(tracked, key="bbox")
    
    # 4.6) 강한 이동평균 스무딩 (k=11: 전후 5프레임씩)
    tracked = moving_average_smooth(tracked, key="bbox", k=11)
    
    # 4.7) 2차 스무딩 (더 부드럽게)
    tracked = moving_average_smooth(tracked, key="bbox", k=7)
    
    print(f"📊 후처리 완료: 강력 노이즈 제거 + 보간 + 2단계 스무딩")
    
    # 5) 크롭 박스 계산 (zoom 역변환 + 9:16)
    merged = []
    zoom_applied_count = 0
    
    # 첫 프레임(frame 0)이 있는지 확인
    first_valid_idx = next((i for i, t in enumerate(tracked) if t is not None and t.get("bbox")), None)
    if first_valid_idx is not None and tracked[first_valid_idx].get("frame", 0) > 0:
        # frame 0이 없으면 첫 유효 프레임을 복사해서 frame 0부터 채움
        first_frame = tracked[first_valid_idx].get("frame", 0)
        print(f"⚠️ 첫 프레임이 frame {first_frame}입니다. frame 0~{first_frame-1}을 채웁니다.")
        for pad_idx in range(first_frame):
            padded = deepcopy(tracked[first_valid_idx])
            padded["frame"] = pad_idx
            padded["pts_ms"] = pad_idx * 33
            tracked.insert(pad_idx, padded)
    
    for idx, t in enumerate(tracked):
        if t is None:
            continue
        
        bbox = t.get("bbox")
        if bbox is None:
            continue
        
        # 각 프레임의 src 크기와 zoom 값 사용
        frame_src_w = t.get("src_w", src_w)
        frame_src_h = t.get("src_h", src_h)
        sw = t.get("screenWidth", frame_src_w)
        sh = t.get("screenHeight", frame_src_h)
        z = t.get("zoom", 1.0)
        
        # zoom이 1.0이 아니면 카운트
        if abs(z - 1.0) > 0.01:
            zoom_applied_count += 1
            if zoom_applied_count <= 3:
                print(f"  ↳ 프레임 {t.get('frame')}: zoom={z:.2f} 적용")
        
        # zoom 역변환 (각 프레임의 src 크기 사용)
        unzoomed = center_unzoom(bbox, frame_src_w, frame_src_h, z)
        
        # 9:16 비율 (각 프레임의 src 크기 사용)
        crop_box = enforce_9x16(unzoomed, frame_src_w, frame_src_h)
        
        t["crop_box_src_xyxy"] = {
            "x1": crop_box[0], "y1": crop_box[1],
            "x2": crop_box[2], "y2": crop_box[3]
        }
        
        # 스크린 좌표로 변환
        sx = sw / src_w
        sy = sh / src_h
        t["crop_box_screen_xyxy"] = {
            "x1": crop_box[0] * sx, "y1": crop_box[1] * sy,
            "x2": crop_box[2] * sx, "y2": crop_box[3] * sy
        }
        
        # keypoints는 JSON 직렬화를 위해 리스트로 변환
        if "keypoints" in t and t["keypoints"]:
            t["keypoints"] = [[float(x) for x in kp] for kp in t["keypoints"]]
        
        merged.append(t)
    
    # 6) 저장 (JSONL)
    with open(output_path, 'w', encoding='utf-8') as f:
        for m in merged:
            f.write(json.dumps(m, ensure_ascii=False) + "\n")
    
    print(f"✅ 병합 완료: {output_path} ({len(merged)} 프레임, zoom 적용: {zoom_applied_count}개)")


if __name__ == "__main__":
    import sys
    if len(sys.argv) >= 4:
        merge_pose_logs(sys.argv[1], sys.argv[2], sys.argv[3])
    else:
        print("Usage: python merge_pose_logs.py <pose_log.jsonl> <tracking_log.json> <output.jsonl>")

