import json
import math
from copy import deepcopy
from typing import Optional, List, Tuple
import numpy as np

# ===== 직선+코너 이음 전용 유틸 =====
def _line_at(t0, y0, t1, y1, t):
    # t0!=t1 가정
    alpha = (t - t0) / (t1 - t0)
    return y0 * (1 - alpha) + y1 * alpha

def _rdp_time_series(ts_s, ys, eps, i0=0, i1=None, out=None):
    """
    RDP: 수직 오차(픽셀/로그값) 기준의 단순화. 반환: 보존할 인덱스 리스트(정렬).
    eps: 허용 최대 오차 (px 또는 log-도메인 단위)
    """
    if out is None:
        out, i1 = [], len(ts_s) - 1
    # 시작/끝은 항상 포함
    if i0 == 0: out.append(i0)
    if i1 <= i0 + 1:
        out.append(i1)
        return out
    # 기준 직선
    t0, y0 = ts_s[i0], ys[i0]
    t1, y1 = ts_s[i1], ys[i1]
    # 최대 오차 점 찾기(수직 오차)
    idx_max, err_max = -1, -1.0
    for k in range(i0 + 1, i1):
        y_hat = _line_at(t0, y0, t1, y1, ts_s[k])
        err = abs(ys[k] - y_hat)
        if err > err_max:
            err_max, idx_max = err, k
    if err_max > eps:
        _rdp_time_series(ts_s, ys, eps, i0, idx_max, out)
        _rdp_time_series(ts_s, ys, eps, idx_max, i1, out)
    else:
        out.append(i1)
    # 중복 제거 + 정렬은 마지막 호출자가 수행
    return sorted(set(out))

def _piecewise_linear_eased(ts_us, y_des,
                            eps,                # 직선 허용 오차(px 또는 log단위)
                            min_seg_dur=0.18,   # 최소 세그먼트 길이(초) — 너무 잦은 코너 방지
                            corner_tau=0.10):   # 코너 매끈 전환 길이(초) — 짧을수록 “직선 느낌” 강함
    """
    반환: y_out (입력과 동일 길이), "직선 + 코너만 cubic-ease"
    """
    ts_s = (np.asarray(ts_us, dtype=np.float64) - float(ts_us[0])) / 1e6
    y = np.asarray(y_des, dtype=np.float64)
    T = len(y)
    if T <= 2: return y.copy()

    # 1) RDP로 키프레임(세그먼트 경계) 추출
    idxs = _rdp_time_series(ts_s, y, eps)
    # 너무 촘촘한 코너 제거
    pruned = [idxs[0]]
    for k in range(1, len(idxs)):
        if ts_s[idxs[k]] - ts_s[pruned[-1]] >= min_seg_dur or k == len(idxs) - 1:
            pruned.append(idxs[k])
    idxs = pruned

    # 2) 기본: 완전 직선으로 채우기
    y_lin = np.zeros_like(y)
    for a, b in zip(idxs[:-1], idxs[1:]):
        t0, y0 = ts_s[a], y[a]
        t1, y1 = ts_s[b], y[b]
        if t1 == t0:
            y_lin[a:b+1] = y0
        else:
            for i in range(a, b + 1):
                y_lin[i] = _line_at(t0, y0, t1, y1, ts_s[i])


    # 3) 코너에서만 짧은 Hermite 이음(C¹ 연속)
    y_out = y_lin.copy()
    for j in range(1, len(idxs) - 1):
        jc = idxs[j]                 # 코너 인덱스
        j0 = idxs[j-1]; j1 = idxs[j]; j2 = idxs[j+1]
        # 코너 주변 구간 길이 제한
        half = 0.5 * corner_tau
        tL = max(ts_s[j0], ts_s[j1] - half)
        tR = min(ts_s[j2], ts_s[j1] + half)
        if tR - tL < 1e-6:  # 너무 짧으면 스킵
            continue
        # 좌/우 직선의 기울기(속도)
        vL = (y[j1] - y[j0]) / max(ts_s[j1] - ts_s[j0], 1e-6)
        vR = (y[j2] - y[j1]) / max(ts_s[j2] - ts_s[j1], 1e-6)
        # tL/tR에서의 직선 값
        yL = _line_at(ts_s[j0], y[j0], ts_s[j1], y[j1], tL)
        yR = _line_at(ts_s[j1], y[j1], ts_s[j2], y[j2], tR)

        # Hermite 보간 (C¹)
        # s in [0,1], 길이 dT에 대해 y(t)=H00*yL + H10*(dT*vL) + H01*yR + H11*(dT*vR)
        dT = (tR - tL)
        def hermite(s):
            h00 =  2*s**3 - 3*s**2 + 1
            h10 =    s**3 - 2*s**2 + s
            h01 = -2*s**3 + 3*s**2
            h11 =    s**3 -   s**2
            return h00*yL + h10*(dT*vL) + h01*yR + h11*(dT*vR)

        # [tL, tR] 구간의 샘플에만 적용
        iL = int(np.searchsorted(ts_s, tL, side='left'))
        iR = int(np.searchsorted(ts_s, tR, side='right'))
        for i in range(iL, iR):
            s = (ts_s[i] - tL) / dT
            y_out[i] = hermite(s)

    return y_out

# ===== 추가 유틸 =====
def smoothstep01(x):
    # 0~1 사이에서 부드러운 S-curve (C1 연속)
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)

def soft_deadband(prev, cur, eps):
    # |delta|<=eps 구간을 "연속적으로" 눌러주되 경계에서 튀지 않도록 스무스
    d = cur - prev
    if abs(d) <= eps:
        # 중심으로 스무스하게 수렴
        w = smoothstep01(abs(d) / max(eps, 1e-9))
        return prev + w * d * 0.2  # 0.2: 남기는 비율(작을수록 더 고정)
    else:
        # eps 구간을 지나면서 완만히 풀어줌
        sign = 1.0 if d > 0 else -1.0
        m = abs(d) - eps
        return prev + sign * (eps * 0.2 + m)  # 경계에서 경사 연속

def jerk_limited_trajectory(y_des, ts, v_max, a_max, j_max):
    """
    y_des: 원하는(관측 기반) 궤적 1D 배열 (길이 T)
    ts: 각 프레임 타임스탬프(μs)
    v_max, a_max, j_max: 속도/가속/저크 제한 (픽셀/초, 픽셀/초², 픽셀/초³)
    반환: y_out (길이 T)
    """
    T = len(y_des)
    y = np.zeros(T, dtype=np.float64)
    v = 0.0
    a = 0.0
    y[0] = float(y_des[0])
    for t in range(1, T):
        dt = max((ts[t] - ts[t-1]) / 1e6, 1/120.0)

        # 1) 목표 변화량
        e = float(y_des[t] - y[t-1])

        # 2) 비례항으로 목표 속도 제안 (간단 PD)
        v_tgt = e / dt
        # 속도 제한
        if v_tgt >  v_max: v_tgt =  v_max
        if v_tgt < -v_max: v_tgt = -v_max

        # 3) 목표 속도까지 가속/저크 제한으로 접근
        dv = v_tgt - v
        # 먼저 저크로 가속도를 제한적으로 변경
        # 목표 가속도 변화 da_tgt를 dv/dt에 맞춰 설정
        a_tgt = dv / dt
        da = a_tgt - a
        # 저크 제한
        j_lim = j_max * dt
        if   da >  j_lim: da =  j_lim
        elif da < -j_lim: da = -j_lim
        a += da

        # 가속도 제한
        if   a >  a_max: a =  a_max
        elif a < -a_max: a = -a_max

        # 속도/위치 업데이트
        v += a * dt
        if   v >  v_max: v =  v_max
        elif v < -v_max: v = -v_max

        y[t] = y[t-1] + v * dt
    return y

def crossfade_series(base, meas, ts, when_mask, fade_frames=6):
    """
    when_mask[t]==True (예: 관측 게이트 통과가 막 재개된 프레임) 부근에서
    base(예측/스무스) -> meas(관측기반)로 N프레임에 걸쳐 부드럽게 크로스페이드.
    """
    T = len(base)
    out = np.array(base, dtype=np.float64)
    for t in range(T):
        if when_mask[t]:
            for k in range(fade_frames):
                i = t + k
                if i >= T: break
                w = smoothstep01((k+1)/max(fade_frames,1))
                out[i] = (1 - w) * out[i] + w * meas[i]
    return out

# ---------- 유틸 ----------
def xyxy_to_cxcywh(bb):
    x1,y1,x2,y2 = bb
    w = x2 - x1
    h = y2 - y1
    cx = (x1 + x2) * 0.5
    cy = (y1 + y2) * 0.5
    return np.array([cx, cy, w, h], dtype=np.float64)

def cxcywh_to_xyxy(v):
    cx,cy,w,h = [float(x) for x in v]
    return [cx - w*0.5, cy - h*0.5, cx + w*0.5, cy + h*0.5]

def iou_xyxy(a,b):
    ax1,ay1,ax2,ay2 = a; bx1,by1,bx2,by2 = b
    ix1,iy1 = max(ax1,bx1), max(ay1,by1)
    ix2,iy2 = min(ax2,bx2), min(ay2,by2)
    iw, ih = max(0.0, ix2-ix1), max(0.0, iy2-iy1)
    inter = iw*ih
    area = max(0.0,(ax2-ax1)*(ay2-ay1)) + max(0.0,(bx2-bx1)*(by2-by1)) - inter
    return inter / (area + 1e-9)

def clamp_aspect_box(cx, top, bottom, aspect, sw, sh):
    h = bottom - top
    w = h * aspect
    cx = float(cx)
    # 1) 중심 먼저 경계 내로
    left, right = cx - w/2, cx + w/2
    if left < 0: cx += -left
    if right > sw: cx -= (right - sw)
    # 2) 그래도 넘치면 w 축소
    left, right = cx - w/2, cx + w/2
    if left < 0 or right > sw:
        w = min(w, sw)
        cx = max(w/2, min(sw - w/2, cx))
    return [cx - w/2, top, cx + w/2, bottom]

def ensure_positive_size(bb, eps=1.0):
    x1,y1,x2,y2 = bb
    if x2 <= x1: x2 = x1 + eps
    if y2 <= y1: y2 = y1 + eps
    return [x1,y1,x2,y2]

# ---------- 칼만/RTS ----------
def build_F(dt: float) -> np.ndarray:
    F = np.eye(8, dtype=np.float64)
    for i in range(4):  # pos += v*dt
        F[i, i+4] = dt
    return F

def build_H() -> np.ndarray:
    H = np.zeros((4,8), dtype=np.float64)
    H[0,0]=H[1,1]=H[2,2]=H[3,3]=1.0
    return H

def rts_smoother(xs: List[np.ndarray], Ps: List[np.ndarray],
                 x_preds: List[np.ndarray], P_preds: List[np.ndarray],
                 Fs: List[np.ndarray]) -> Tuple[List[np.ndarray], List[np.ndarray]]:
    """
    xs, Ps : filtering 결과(앞→뒤)
    x_preds, P_preds, Fs : 각 스텝의 예측값/전이행렬
    반환: RTS 스무딩된 x_s, P_s (뒤→앞 정보 반영)
    """
    T = len(xs)
    x_s = [None]*T
    P_s = [None]*T
    x_s[-1] = xs[-1].copy()
    P_s[-1] = Ps[-1].copy()
    for k in range(T-2, -1, -1):
        F = Fs[k+1]  # k→k+1 전이
        C = Ps[k] @ F.T @ np.linalg.inv(P_preds[k+1])
        x_s[k] = xs[k] + C @ (x_s[k+1] - x_preds[k+1])
        P_s[k] = Ps[k] + C @ (P_s[k+1] - P_preds[k+1]) @ C.T
    return x_s, P_s

# ---------- 메인 파이프라인 ----------
def process_video(
        tracking_json: str,
        ts_json: str,
        output_json: str,
        # 튜닝 파라미터 (필요시 조정)
        q_pos_base: float = 25.0,     # 과정잡음(위치) 기본
        q_vel_base: float = 200.0,    # 과정잡음(속도) 기본
        r_meas_base: float = 35.0,    # 관측잡음 기본(픽셀)
        iou_gate: float = 0.05,       # IoU 게이트(아웃라이어 컷)
        maha_gate: float = 10.0,      # 마할라노비스 거리 게이트
        deadband_ratio: float = 0.004, # w/h 데드밴드(화면비율 대비)
        aspect: float = 9/16,         # 고정 크롭 비율
):
    # 1) 프레임 타임스탬프 로드(μs or us-like → float)
    with open(ts_json, 'r') as f:
        frame_ts = [float(ts) for ts in json.load(f)]  # us 단위라고 가정
    T = len(frame_ts)

    mapped = [
        {
            'frameIndex': i,
            'frame_ts': frame_ts[i],
            'screenWidth': None,
            'screenHeight': None,
            'zoom': None,
            'bbox': None,              # raw bbox (역줌 변환 후)
            'smoothed_bbox': None,     # RTS 결과
            'aspect_ratio_bbox': None  # 9:16 클램프 결과
        } for i in range(T)
    ]

    # 2) 트래킹 로드 + 원시 필드 매핑 (+ 역줌 변환)
    with open(tracking_json, 'r') as f:
        tracking_data = [json.loads(line) for line in f]

    # 각 관측 이벤트를 가장 가까운 프레임에 붙이기
    for e in tracking_data:
        ts_us = float(e.get('timestamp', 0)) / 1_000.0  # ns→us로 왔다면 이 변환 유지
        i = min(range(T), key=lambda j: abs(frame_ts[j] - ts_us))

        # 화면 크기, 줌
        sw = int(e.get('screenWidth')  or mapped[i]['screenWidth']  or 0)
        sh = int(e.get('screenHeight') or mapped[i]['screenHeight'] or 0)
        mapped[i]['screenWidth']  = sw or mapped[i]['screenWidth']
        mapped[i]['screenHeight'] = sh or mapped[i]['screenHeight']

        z = e.get('zoom')
        mapped[i]['zoom'] = float(z) if z is not None else mapped[i]['zoom']

        # bbox 역변환 - 상체 키포인트만 사용 (머리 0-4, 어깨 5-6, 엉덩이 11-12)
        TORSO_INDICES = [0, 1, 2, 3, 4, 5, 6, 11, 12]
        MIN_SCORE = 0.3
        
        # keypoints 파싱 (문자열로 저장된 경우 JSON 파싱)
        keypoints = e.get('keypoints')
        if isinstance(keypoints, str):
            try:
                keypoints = json.loads(keypoints)
            except Exception:
                keypoints = None
        
        raw_bbox = None
        
        # keypoints가 있으면 상체만으로 bbox 계산
        if keypoints and isinstance(keypoints, list) and len(keypoints) >= 17:
            torso_points = []
            for idx in TORSO_INDICES:
                if idx < len(keypoints):
                    kp = keypoints[idx]
                    if isinstance(kp, (list, tuple)) and len(kp) >= 3 and float(kp[2]) >= MIN_SCORE:
                        torso_points.append((float(kp[0]), float(kp[1])))
            
            if torso_points:
                xs = [p[0] for p in torso_points]
                ys = [p[1] for p in torso_points]
                x1, y1, x2, y2 = min(xs), min(ys), max(xs), max(ys)
                
                # 상체 bbox 중심을 아래로 이동 (위:아래 = 3:7 비율 맞추기)
                # bbox 높이의 20%만큼 아래로 이동하면 패딩이 위 30%, 아래 70%로 배분됨
                h = y2 - y1
                offset = h * 0.3  # 중심을 아래로 20% 이동
                raw_bbox = [x1, y1 + offset, x2, y2 + offset]
        
        # keypoints가 없거나 상체 점이 부족하면 기존 bbox 사용
        if raw_bbox is None:
            raw_bbox = e.get('bbox')
            if isinstance(raw_bbox, str):
                try:
                    raw_bbox = json.loads(raw_bbox)
                except Exception:
                    raw_bbox = None
        
        if raw_bbox:
            x1, y1, x2, y2 = (float(v) for v in raw_bbox)
            z = mapped[i]['zoom'] or 1.0
            if sw and sh and z != 0:
                xc, yc = sw/2.0, sh/2.0
                x1p = xc - (xc - x1) / z
                y1p = yc - (yc - y1) / z
                x2p = xc - (xc - x2) / z
                y2p = yc - (yc - y2) / z
                mapped[i]['bbox'] = ensure_positive_size([x1p,y1p,x2p,y2p])
            else:
                mapped[i]['bbox'] = ensure_positive_size([x1,y1,x2,y2])

    # === 2.5) ZOOM 채움/보간 ===
    # 이벤트가 있는 프레임에만 zoom이 들어오므로, 없는 프레임은 선형 보간/전파로 채운다.
    z_list = [mapped[i]['zoom'] for i in range(T)]
    anchors = [(i, float(z)) for i, z in enumerate(z_list) if z is not None]

    if not anchors:
        z_filled = [1.0] * T
    else:
        z_filled = [None] * T
        # 앞쪽 구간: 첫 앵커로 채우기
        first_i, first_z = anchors[0]
        for i in range(0, first_i + 1):
            z_filled[i] = first_z
        # 중간 구간: 선형 보간
        for (i0, z0), (i1, z1) in zip(anchors, anchors[1:]):
            span = max(1, i1 - i0)
            for k, i in enumerate(range(i0, i1 + 1)):
                alpha = k / span
                z_filled[i] = z0 * (1 - alpha) + z1 * alpha
        # 뒤쪽 구간: 마지막 앵커로 채우기
        last_i, last_z = anchors[-1]
        for i in range(last_i, T):
            z_filled[i] = last_z

    # 결과 반영: 이제부터 mapped[*]['zoom']은 절대 None 아님
    for i in range(T):
        mapped[i]['zoom'] = float(z_filled[i])

    # 3) 선형 보간(화면 크기, bbox) — 누락 최소화
    prev = {'bbox': None, 'screenWidth': None, 'screenHeight': None}
    prev_idx = None
    for i, x in enumerate(mapped):
        # 화면 크기 보간
        if x['screenWidth'] is None and prev['screenWidth'] is not None:
            next_idx = next((j for j in range(i+1, T) if mapped[j]['screenWidth'] is not None), prev_idx)
            next_sw = mapped[next_idx]['screenWidth'] if next_idx is not None else prev['screenWidth']
            next_sh = mapped[next_idx]['screenHeight'] if next_idx is not None else prev['screenHeight']
            gap = (next_idx - prev_idx) if next_idx is not None and prev_idx is not None else 1
            alpha = (i - prev_idx) / gap if gap else 0
            x['screenWidth']  = int(prev['screenWidth']  + (next_sw - prev['screenWidth'])  * alpha)
            x['screenHeight'] = int(prev['screenHeight'] + (next_sh - prev['screenHeight']) * alpha)
        elif x['screenWidth'] is not None:
            prev['screenWidth']  = x['screenWidth']
            prev['screenHeight'] = x['screenHeight']

        # bbox 보간
        if x['bbox'] is None and prev['bbox'] is not None:
            next_idx = next((j for j in range(i+1, T) if mapped[j]['bbox'] is not None), prev_idx)
            next_bbox = mapped[next_idx]['bbox'] if next_idx is not None else prev['bbox']
            gap = (next_idx - prev_idx) if next_idx is not None and prev_idx is not None else 1
            alpha = (i - prev_idx) / gap if gap else 0
            x['bbox'] = [prev['bbox'][k] + (next_bbox[k] - prev['bbox'][k]) * alpha for k in range(4)]
            x['bbox'] = ensure_positive_size(x['bbox'])
        elif x['bbox'] is not None:
            prev['bbox'] = x['bbox']
            prev_idx = i

    # 4) 칼만 필터(앞→뒤) + RTS(뒤→앞)
    H = build_H()
    xs = []       # filter posterior mean
    Ps = []       # filter posterior cov
    x_preds = []  # prior mean
    P_preds = []  # prior cov
    Fs = []

    # 초기화(첫 유효 bbox 기준)
    first_idx = next((i for i,x in enumerate(mapped) if x['bbox'] is not None and x['screenWidth'] and x['screenHeight']), 0)
    sw0 = mapped[first_idx]['screenWidth'] or 1920
    sh0 = mapped[first_idx]['screenHeight'] or 1080

    # 초기 상태: 관측에서 cx,cy,w,h 채우고 속도 0
    if mapped[first_idx]['bbox'] is not None:
        cxcywh0 = xyxy_to_cxcywh(mapped[first_idx]['bbox'])
    else:
        # 완전 누락 대비 안전값
        cxcywh0 = np.array([sw0/2, sh0/2, sw0*0.2, sh0*0.2], dtype=np.float64)

    x = np.zeros((8,1), dtype=np.float64)
    x[0:4,0] = cxcywh0
    # 초기 공분산: 위치는 화면 크기 비례, 속도는 큰 불확실성
    P = np.diag([ (0.05*sw0)**2, (0.05*sh0)**2, (0.05*sw0)**2, (0.05*sh0)**2,
                  (0.5*sw0)**2, (0.5*sh0)**2, (0.5*sw0)**2, (0.5*sh0)**2 ])

    def Q_from_dt(dt):
        # 과정잡음: 위치/속도 블록 분리
        q_pos = (q_pos_base**2) * max(dt, 1/120.0)
        q_vel = (q_vel_base**2) * max(dt, 1/120.0)
        Q = np.diag([q_pos, q_pos, q_pos, q_pos, q_vel, q_vel, q_vel, q_vel]).astype(np.float64)
        return Q

    def R_from_scale(sw,sh,scale=1.0):
        r = (r_meas_base**2) * scale
        # 픽셀 단위 고정 잡음(필요시 sw/sh 반영 가중 가능)
        return np.diag([r,r,r,r]).astype(np.float64)

    # 필터 루프
    prev_ts = frame_ts[first_idx]
    for t in range(T):
        sw = mapped[t]['screenWidth'] or sw0
        sh = mapped[t]['screenHeight'] or sh0
        ts = mapped[t]['frame_ts']
        dt = (ts - prev_ts)/1e6 if t>0 else 1/30.0
        prev_ts = ts

        F = build_F(dt)
        Q = Q_from_dt(dt)
        Fs.append(F)

        # 예측
        x_pred = F @ x
        P_pred = F @ P @ F.T + Q

        x_preds.append(x_pred.copy())
        P_preds.append(P_pred.copy())

        z = None
        if mapped[t]['bbox'] is not None:
            z = xyxy_to_cxcywh(mapped[t]['bbox']).reshape(4,1)

        if z is not None:
            # Outlier 게이팅
            # 1) IoU 게이트(예측 박스 vs 관측 박스)
            pred_bb = cxcywh_to_xyxy(x_pred[0:4,0])
            meas_bb = cxcywh_to_xyxy(z[:,0])
            pass_iou = iou_xyxy(pred_bb, meas_bb) >= iou_gate

            # 2) 마할라노비스 게이트
            y = z - (H @ x_pred)
            S = H @ P_pred @ H.T + R_from_scale(sw,sh,1.0)
            try:
                invS = np.linalg.inv(S)
                m2 = float(y.T @ invS @ y)  # D^2
            except np.linalg.LinAlgError:
                m2 = 0.0
            pass_maha = (m2 <= maha_gate)

            # 관측 업데이트 (게이트 실패 시 관측을 약화시키거나 스킵)
            if pass_iou and pass_maha:
                R = R_from_scale(sw,sh,1.0)
            else:
                # 완전 스킵보다 '약화'가 스무딩에 유리
                R = R_from_scale(sw,sh,scale=25.0)  # 잡음 크게 → 관측 영향 최소화

            S = H @ x_pred  # 예측 관측값
            y = z - (H @ x_pred)
            S_cov = H @ P_pred @ H.T + R
            K = P_pred @ H.T @ np.linalg.inv(S_cov)
            x = x_pred + K @ y
            P = (np.eye(8) - K @ H) @ P_pred
        else:
            # 관측 없음 → 예측만
            x = x_pred
            P = P_pred

        xs.append(x.copy())
        Ps.append(P.copy())

    # RTS smoother (뒤→앞 정보 반영)
    x_s, P_s = rts_smoother(xs, Ps, x_preds, P_preds, Fs)

    # 5) 궤적 배열
    cx_arr = np.array([float(x_s[t][0,0]) for t in range(T)])
    cy_arr = np.array([float(x_s[t][1,0]) for t in range(T)])
    w_arr  = np.array([float(x_s[t][2,0]) for t in range(T)])
    h_arr  = np.array([float(x_s[t][3,0]) for t in range(T)])

    # (선택) 아주 약한 노이즈만 눌러주고 싶으면 soft_deadband 유지,
    # 완전한 '직선 유지'를 원하면 아래 두 줄을 주석 처리해도 됨.
    REL_DB = 0.010  # 1.0% (직선 성향을 살리려면 0.5~1.0% 권장)
    for t in range(1, T):
        sw = mapped[t]['screenWidth']  or sw0
        sh = mapped[t]['screenHeight'] or sh0
        abs_dw = deadband_ratio * sw
        abs_dh = deadband_ratio * sh
        rel_dw = REL_DB * max(w_arr[t-1], 1.0)
        rel_dh = REL_DB * max(h_arr[t-1], 1.0)
        w_arr[t] = soft_deadband(w_arr[t-1], w_arr[t], max(abs_dw, rel_dw))
        h_arr[t] = soft_deadband(h_arr[t-1], h_arr[t], max(abs_dh, rel_dh))

    # === 직선 + 코너부만 이음 ===
    # 위치는 "픽셀 오차" 기준, 크기는 "로그-퍼센트" 기준으로 RDP 허용오차 설정
    t_sec = np.array(frame_ts, dtype=np.float64)

    EPS_POS = 8.0         # (px) 직선 허용 오차 — 줄일수록 더 직선/코너 많아짐
    EPS_POS_CY = 8.0      # y도 동일 기준 권장
    EPS_LOG  = math.log(1.015)  # (~1.5%) 크기 직선 허용 오차(로그)

    # 위치: 그대로
    cx_smooth = _piecewise_linear_eased(t_sec, cx_arr, eps=EPS_POS, min_seg_dur=0.18, corner_tau=0.10)
    cy_smooth = _piecewise_linear_eased(t_sec, cy_arr, eps=EPS_POS_CY, min_seg_dur=0.18, corner_tau=0.10)

    # 크기: 로그 도메인에서 직선화(=퍼센트 기준 직선), 마지막에 exp 복구
    w_log = np.log(np.clip(w_arr, 2.0, 1e9))
    h_log = np.log(np.clip(h_arr, 2.0, 1e9))
    w_log_s = _piecewise_linear_eased(t_sec, w_log, eps=EPS_LOG, min_seg_dur=0.20, corner_tau=0.10)
    h_log_s = _piecewise_linear_eased(t_sec, h_log, eps=EPS_LOG, min_seg_dur=0.20, corner_tau=0.10)
    w_smooth = np.exp(w_log_s)
    h_smooth = np.exp(h_log_s)

    # 6) 최종 bbox 및 9:16 크롭 + 경계 클램프
    for t in range(T):
        sw = mapped[t]['screenWidth'] or sw0
        sh = mapped[t]['screenHeight'] or sh0

        cx, cy, w, h = cx_smooth[t], cy_smooth[t], max(w_smooth[t],2.0), max(h_smooth[t],2.0)

        sm_bb = cxcywh_to_xyxy([cx,cy,w,h])
        sm_bb = ensure_positive_size(sm_bb)
        mapped[t]['smoothed_bbox'] = sm_bb

        top, bottom = sm_bb[1], sm_bb[3]
        ar_bb = clamp_aspect_box(cx=cx, top=top, bottom=bottom, aspect=aspect, sw=sw, sh=sh)
        mapped[t]['aspect_ratio_bbox'] = ensure_positive_size(ar_bb)

        if mapped[t]['bbox'] is None:
            mapped[t]['bbox'] = sm_bb


    # 7) 앞/뒤 채우기(혹시 남은 None들 마무리)
    fields = ['screenWidth', 'screenHeight', 'bbox', 'smoothed_bbox', 'aspect_ratio_bbox']
    for f in fields:
        first = next((k for k, v in enumerate(mapped) if v[f] is not None), None)
        if first is not None:
            for k in range(first): mapped[k][f] = deepcopy(mapped[first][f])
        last = next((k for k in range(T-1, -1, -1) if mapped[k][f] is not None), None)
        if last is not None:
            for k in range(last+1, T): mapped[k][f] = deepcopy(mapped[last][f])

    # 8) 저장
    with open(output_json, 'w', encoding='utf-8') as f:
        json.dump(mapped, f, indent=2, ensure_ascii=False)
