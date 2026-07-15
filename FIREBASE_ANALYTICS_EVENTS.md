# EchoShotX Firebase Analytics 이벤트 정리

이 문서는 EchoShotX Android 앱에 적용된 Firebase Analytics 맞춤 이벤트와 각 이벤트가 발생하는 사용자 행동을 정리한다.

## 구현 개요

- Firebase 프로젝트 패키지: `com.echoshot.app`
- 공통 이벤트 전송기: `app/src/main/java/com/echoshot/app/AnalyticsTracker.kt`
- Firebase Analytics SDK는 `app/build.gradle`에 연결되어 있다.
- 이벤트에는 이메일, 파일 경로, 영상 URI, 사진 이름 등 개인을 식별할 수 있는 값을 전송하지 않는다.
- 현재 맞춤 이벤트는 총 13종이다.

## 전체 이벤트 목록

| 이벤트 이름 | 기록 시점 | 주요 파라미터 |
|---|---|---|
| `capture_button_click` | 사진 또는 동영상 촬영 버튼 클릭 | `media_type`, `camera_facing` |
| `capture_stop_click` | 동영상 촬영 종료 버튼 클릭 | `media_type`, `camera_facing` |
| `capture_complete` | 사진 또는 동영상이 실제로 저장 완료 | `media_type`, `camera_facing` |
| `gallery_open` | 촬영 화면에서 갤러리 버튼 클릭 | `source_screen` |
| `gallery_video_play` | 갤러리에서 재생 가능한 영상 클릭 | `gallery_mode` |
| `gallery_locked_click` | 갤러리에서 잠긴 영상 클릭 | `gallery_mode` |
| `composition_type_select` | 자동 구도 또는 인물 선택 구도 선택 | `composition_type` |
| `composition_start` | 구도 생성 설정창에서 시작 버튼 클릭 | `composition_type` |
| `camera_setting_change` | 프리뷰 촬영 설정이 실제로 변경됨 | `setting_name`, `setting_value`, `camera_facing` |
| `edit_nav_click` | 하단 바의 영상 편집 버튼 클릭 | `source_page` |
| `edit_video_picker_open` | 편집할 영상 선택 버튼 클릭 | 없음 |
| `edit_video_selected` | 시스템 파일 선택기에서 영상 선택 완료 | 없음 |
| `edit_start_click` | 선택한 영상의 편집 시작 버튼 클릭 | 없음 |

## 촬영 이벤트

### 촬영 버튼 클릭

이벤트: `capture_button_click`

다음 네 촬영 화면에 적용되어 있다.

| 촬영 화면 | `media_type` | `camera_facing` |
|---|---|---|
| 후면 동영상 촬영 | `video` | `back` |
| 전면 동영상 촬영 | `video` | `front` |
| 후면 사진 촬영 | `photo` | `back` |
| 전면 사진 촬영 | `photo` | `front` |

이 이벤트는 사용자가 촬영 버튼을 누른 횟수를 나타낸다. 저장 성공 횟수가 아니므로 `capture_complete`와 함께 비교해야 한다.

### 동영상 촬영 종료 버튼

이벤트: `capture_stop_click`

후면 및 전면 동영상 촬영 화면에서 사용자가 종료 버튼을 정상적으로 누른 경우 기록한다. 짧은 시간에 발생한 중복 터치는 디바운스 처리 후 기록하지 않는다.

### 촬영 완료

이벤트: `capture_complete`

- 사진은 CameraX의 `onImageSaved` 콜백이 호출된 후 기록한다.
- 동영상은 인코더 종료와 저장 처리가 성공한 후 기록한다.
- 사진과 동영상, 전면과 후면은 `media_type`과 `camera_facing`으로 구분한다.

촬영 성공률은 다음처럼 계산할 수 있다.

```text
촬영 성공률 = capture_complete 수 / capture_button_click 수
```

사진에는 별도의 종료 버튼이 없으므로 `capture_stop_click`은 동영상에만 존재한다.

## 촬영 화면의 갤러리 진입

이벤트: `gallery_open`

| 진입 화면 | `source_screen` |
|---|---|
| 후면 동영상 촬영 화면 | `video_back` |
| 전면 동영상 촬영 화면 | `video_front` |
| 후면 사진 촬영 화면 | `photo_back` |
| 전면 사진 촬영 화면 | `photo_front` |

이 이벤트로 어느 촬영 화면에서 갤러리에 가장 많이 진입하는지 비교할 수 있다.

## 갤러리 이벤트

### 영상 재생

이벤트: `gallery_video_play`

갤러리에서 영상을 눌러 플레이어 화면으로 이동할 때 기록한다. `gallery_mode` 값으로 기본 갤러리와 확장 갤러리를 구분한다.

### 잠긴 영상 클릭

이벤트: `gallery_locked_click`

잠금 오버레이가 있는 영상을 클릭했을 때 기록한다. 잠긴 영상을 클릭한 뒤 표시되는 구도 선택 행동은 `composition_type_select`로 이어진다.

## 구도 생성 이벤트

### 구도 종류 선택

이벤트: `composition_type_select`

| 사용자가 누른 버튼 | `composition_type` |
|---|---|
| 자동 구도 생성 | `auto` |
| 인물 선택 구도 생성 | `person_select` |

### 구도 생성 시작

이벤트: `composition_start`

| 시작 버튼 위치 | `composition_type` |
|---|---|
| 자동 구도 설정 다이얼로그 | `auto` |
| 인물 선택 구도 설정 다이얼로그 | `person_select` |
| 팬캠 편집 인물 선택 설정창 | `person_select` |

구도 선택 후 실제 시작 비율은 다음 이벤트 흐름으로 확인할 수 있다.

```text
gallery_locked_click
  → composition_type_select
  → composition_start
```

## 프리뷰 촬영 설정 변경

이벤트: `camera_setting_change`

초기 화면 표시만으로는 기록하지 않으며, 사용자가 설정을 실제로 다른 값으로 변경했을 때 기록한다.

### 구도 모드

`setting_name=composition_mode`

| 선택 항목 | `setting_value` |
|---|---|
| 기본/단일 화면 | `single` |
| 중앙 구도 | `default` |
| 하단 구도 | `bottom` |
| 상단 구도 | `top` |

### 렌즈

`setting_name=lens`

- 광각: `setting_value=wide`
- 망원: `setting_value=tele`

현재 선택된 렌즈를 다시 누른 경우에는 변경 이벤트를 기록하지 않는다.

### 자동 줌

`setting_name=auto_zoom`

- 켜기: `setting_value=on`
- 끄기: `setting_value=off`

### 카메라 방향

`setting_name=camera_facing`

- 후면에서 전면으로 전환: `setting_value=front`
- 전면에서 후면으로 전환: `setting_value=back`

`camera_facing` 파라미터에는 변경 직전의 카메라 방향이 함께 기록된다.

## 영상 편집 퍼널

### 하단 바 편집 버튼

이벤트: `edit_nav_click`

하단 바의 영상 편집 버튼을 눌러 팬캠 편집 화면으로 이동할 때 기록한다. `source_page` 값으로 어느 화면에서 진입했는지 확인할 수 있다.

### 편집할 영상 선택 버튼

이벤트: `edit_video_picker_open`

팬캠 편집 화면에서 시스템 영상 선택기를 여는 버튼을 누를 때 기록한다.

### 영상 선택 완료

이벤트: `edit_video_selected`

시스템 영상 선택기에서 실제 영상을 선택하고 앱으로 돌아온 경우에만 기록한다. 파일 선택기를 열었다가 취소한 경우에는 기록하지 않는다.

### 편집 시작 버튼

이벤트: `edit_start_click`

선택한 영상의 설정창에서 최종 시작 버튼을 누를 때 기록한다. 같은 지점에서 `composition_start`의 `person_select`도 함께 기록한다.

편집 퍼널은 다음 순서로 분석한다.

```text
edit_nav_click
  → edit_video_picker_open
  → edit_video_selected
  → edit_start_click
```

- `edit_video_picker_open`만 존재: 파일 선택기에서 취소했을 가능성
- `edit_video_selected`까지 존재: 영상 선택 완료
- `edit_start_click`까지 존재: 실제 편집 시작

## Firebase에서 확인하는 방법

일반 집계는 Firebase Console의 `Analytics > Events`에서 이벤트 이름을 선택해 확인한다. 파라미터별 분석이 필요하면 Google Analytics의 맞춤 정의에 이벤트 파라미터를 맞춤 측정기준으로 등록하거나 BigQuery 내보내기를 사용한다.

개발 기기에서 즉시 확인하려면 다음 명령으로 DebugView를 활성화한다.

```bash
adb shell setprop debug.firebase.analytics.app com.echoshot.app
```

그다음 Firebase Console의 `Analytics > DebugView`에서 버튼을 직접 눌러 이벤트와 파라미터가 들어오는지 확인한다.

DebugView를 끄려면 다음 명령을 사용한다.

```bash
adb shell setprop debug.firebase.analytics.app .none.
```

## 개인정보 관련 주의사항

새 이벤트를 추가할 때 다음 값은 Analytics 파라미터로 전송하지 않는다.

- 사용자 이름, 이메일, 전화번호
- 사진이나 영상의 파일명 및 로컬 경로
- 영상 URI
- 로그인 토큰
- 얼굴이나 인물을 식별할 수 있는 값
- 오류 메시지 원문에 포함된 개인정보

기능 사용 여부를 구분할 때는 현재 구현처럼 `photo`, `video`, `front`, `back`, `auto` 등 미리 정한 비식별 값만 사용한다.
