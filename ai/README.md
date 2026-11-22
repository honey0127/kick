# 안주잘부 — AI 주차 판정 공유 킥보드 반납

인식 대상은 **전동 킥보드**입니다. 킥보드는 COCO 에 없는 클래스라, 공개 데이터셋(Roboflow Universe)으로 학습한 `models/kick_seg.pt` 로 인식합니다. 이 모델이 없을 때는 COCO 의 bicycle/motorcycle 을 대신 쓰지만 정확도가 낮습니다.

반납 순서를 **① GPS 위치 → ② 주차 사진 AI 판정 → ③ 반납 완료** 로 강제합니다 (결제는 범위에서 뺌). 서버가 세션 단계를 관리해서 앞 단계를 통과하지 못하면 다음 API가 `409`를 돌려줍니다.

| 단계 | 검사 | 결과 |
|---|---|---|
| ① 위치 | `server/zones.geojson` 의 서비스 지역 / 지정 주차구역 / 금지구역, GPS 정확도 | 통과해야 반납 토큰 발급 |
| ② 사진 | 차도에 세움 · 횡단보도 · 점자블록 점유 · 전도 · **보도 가로폭 60% 이상 점유** · **남은 통행폭 120cm 미만** (점자블록으로 실측될 때) | `APPROVE` / `REJECT` / `RETAKE` (+ 경고·주의 안내) |
| ③ 반납 완료 | 사진이 `APPROVE` 일 때만 가능 | 반납번호 발급 |

## 구조

```
server/
  main.py       FastAPI: /api/return/location → /photo → /complete, 웹 화면 서빙
  zones.py      GPS 폴리곤 판정 (point-in-polygon, 오차반경 고려)
  analyzer.py   사진 → 마스크 (YOLO-seg + SegFormer) → 판정 → 근거 이미지
  rules.py      판정 규칙·등급·침해점수 (모델과 독립, 임계값은 Thresholds)
  ground.py     점자블록 30cm 기준 호모그래피 → 조감도 → 단면별 잔여 통행폭(cm)
  zones.geojson 샘플 구역 (경북대 북문 일대, 실제 좌표로 교체 필요)
web/            모바일 반납 화면 (지도, 카메라, 판정 결과, 반납 완료)
training/       데이터셋 설정 · 사전라벨 · 학습 · 판정 평가
tests/          규칙·API 테스트 (모델 없이 실행)
data/collected/ 반납 때 올라온 사진+판정 결과 (재학습용으로 자동 저장)
```

### 판정 방식 (`server/rules.py`)

- **접지부**: 기기 마스크 아래쪽 25%. 그 주변 바닥 픽셀 중 차도가 30% 넘으면 `ON_ROAD`, 점자블록 5%·횡단보도 10% 넘으면 각각 거절.
  - 이 비율은 '인식된 바닥' 안에서의 비율이라, 바퀴 바로 앞 바닥을 모델이 못 알아봤으면(접지부 열의 60% 미만) 통과시키지 않고 `UNKNOWN_GROUND` 재촬영 요청 (기준값은 임시값).
- **보도 폭 점유율**: 기기 아래쪽 40% 구간의 각 행(가로 단면)마다 `기기가 막은 폭 / 그 행의 보도 폭`을 재고 90퍼센타일이 60% 이상이면 `WIDTH` 거절. 여러 대가 같이 있으면 **합쳐서** 계산(군집 효과).
  - 평평한 지면을 수평으로 찍으면 한 행은 같은 거리의 가로선이라 픽셀 비율 = 실제 폭 비율입니다. 그래서 **보도 진행 방향으로 찍은 사진**에서 정확합니다.
  - 판정에는 차지한 폭 대신 **유효 점유율**(1 − 이어져서 남은 가장 넓은 통로 비율)을 씁니다. 가장자리에서 떨어져 세워 통로가 양쪽으로 나뉘면 차지한 폭이 60% 미만이어도 `OFF_EDGE` 거절 / `BORDER_OFF_EDGE` 보류.
- 보도 양끝이 화면 밖이라 실제 폭을 모르면 거절 대신 `TRUNCATED` 재촬영 요청.
- 보도를 옆에서 가로질러 찍어 보도가 화면을 좌우로 가로지르는 띠로만 보이면, 점유율이 낮게 나와도 통과시키지 않고 `CROSS_VIEW` 재촬영 요청 (기준값은 임시값, `docs/판정규칙.md`).

### cm 실측 (`server/ground.py`) — 점자블록을 '자'로

1. **블록 사각형 찾기**: 점자블록 띠 마스크에서 블록 사이 이음매(어두운 선)를 찾아 블록 한 장씩 사각형을 만든다. 모델이 `braille_tile`(블록 한 장)을 분할하거나 색 검출로 블록이 따로 떨어져 보이면 그것도 쓴다. 학습 모델이 없을 땐 노란색으로 점자블록을 찾는다(차도 중앙선은 제외).
2. **30cm 격자 맞춤**: 가장 큰 블록 하나로 호모그래피를 만들고, 가까운 블록부터 꼭짓점을 30cm 격자점에 맞춰가며 다시 맞춘다. 이음매를 하나 놓쳐 60cm짜리 칸이 섞여도 같은 격자로 정리된다. 남은 재투영 오차(cm)와 블록 수로 신뢰도(높음/보통/낮음)를 낸다. **낮음이면 cm 판정을 쓰지 않고** 60% 비율 규칙만 쓴다.
3. **조감도 단면**: 바닥(보도+점자블록)을 2cm/px 조감도로 펴고, 보도 방향(점자블록 띠 방향)에 수직인 단면을 10cm 간격으로 잘라 **기기를 피해 연속으로 지나갈 수 있는 폭**을 잰다. 최솟값 = 병목 통행폭. 기기는 바닥에 닿은 점(차체 맨 아래)만 지면으로 펴고 최소 차체 폭 55cm를 보장한다.
4. **등급** (문제정의서 ⑤): 위험 < 120cm 또는 점자블록·횡단보도 점유 → 반납 거절 / 경고 120~150 / 주의 150~200 또는 3대 이상 군집 → 반납은 되지만 안내 / 양호 ≥ 200. 같은 등급 안의 우선순위용 **침해 점수(0~100)** = 통행폭 45% + 점자블록 근접 20% + 횡단보도 15% + 전도 10% + 군집 10% (사람 판정으로 가중치 보정 필요).
5. 점자블록이 없으면: 킥보드를 옆에서 찍었을 때 차체 길이(115cm)로 대략 환산한 값을 **표시만** 한다 (합성 장면에서 실제 235cm를 276cm로 볼 만큼 부정확해서 판정에는 안 씀).

합성 원근 장면(`tests/synth.py`: 폭 3m 보도 + 30cm 점자블록 띠, 높이 1.5m 카메라)에서 축척 오차 1~2%, 잔여 통행폭 오차 ±5cm 이내로 확인했습니다. 실제 사진 정확도는 줄자 실측 데이터로 따로 검증해야 합니다 (아래 6번).

현장 점자블록 규격이 30cm가 아니거나 선형 블록 띠가 2장 폭이면 `Thresholds.tile_cm`, `strip_tiles` 를 바꾸세요.

## 실행

```bash
python -m venv .venv
.venv/Scripts/python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu126
.venv/Scripts/python -m pip install -r requirements.txt
.venv/Scripts/python -m uvicorn server.main:app --host 0.0.0.0 --port 8000
```

http://localhost:8000 에서 반납 흐름을 따라갈 수 있습니다. 첫 실행 때 모델(YOLO 20MB, SegFormer 110MB)을 내려받습니다.

**개발 모드**: `KICK_DEV=1` 로 실행하면 웹 화면에 "테스트 위치로 확인"(GPS 없이 구역 선택)이 보이고, 세션 없이 사진만 판정하는 `/api/analyze` 가 열립니다. 기본값(꺼짐)에서는 둘 다 막혀 있으니 실제 서비스에서는 켜지 마세요.

```bash
# PowerShell:  $env:KICK_DEV="1"; .venv/Scripts/python -m uvicorn server.main:app --port 8000
KICK_DEV=1 .venv/Scripts/python -m uvicorn server.main:app --host 0.0.0.0 --port 8000
```

| 환경변수 | 기본값 | 뜻 |
|---|---|---|
| `KICK_DEV` | `0` | 개발용 기능 켜기 |
| `KICK_OPEN_ANALYZE` | `0` | 세션 없는 `/api/analyze` 만 열기 (안드로이드 앱이 쓰는 API, 테스트 위치 버튼은 계속 숨김) |
| `KICK_MAX_UPLOAD_MB` | `15` | 사진 업로드 최대 크기 (넘으면 413) |
| `KICK_MAX_PHOTO_ATTEMPTS` | `10` | 반납 세션 하나에서 사진 판정 최대 횟수 (넘으면 429, 위치 확인부터 다시) |
| `KICK_SAVE_UPLOADS` | `1` | 반납 사진 저장 (EXIF 를 지우고 다시 인코딩해 저장) |

**휴대폰으로 테스트할 때**: 브라우저는 HTTPS에서만 GPS·실시간 카메라를 허용합니다(HTTP면 카메라 앱 업로드로 대체됨). 자체 서명 인증서로 실행하세요.

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 365 -keyout key.pem -out cert.pem -subj "/CN=kick"
.venv/Scripts/python -m uvicorn server.main:app --host 0.0.0.0 --port 8443 --ssl-keyfile key.pem --ssl-certfile cert.pem
```

테스트: `.venv/Scripts/python -m pytest -q`

## AI 학습시키기

지금은 **베이스라인**(COCO YOLO-seg 자전거·오토바이를 킥보드 대용 + Cityscapes SegFormer 차도/보도 분할)으로 돌아갑니다. 학습 없이 바로 쓸 수 있지만:

- COCO 에는 킥보드가 없어 자전거·오토바이로 잡히는 경우만 인식되고, 넘어진 킥보드도 구분하지 못하며, COCO 바닥 모델은 점자블록·횡단보도를 아예 모릅니다.
- Cityscapes는 유럽 차량 시점 데이터라, 한국 골목의 아스팔트 보도·야간 사진을 차도로 착각합니다. (샘플 사진도 실제로 이렇게 `ON_ROAD`로 오판됨)

그래서 직접 찍은 사진으로 학습한 모델이 필요합니다. `models/kick_seg.pt` 가 생기면 서버가 자동으로 그 모델을 씁니다.

### 0. 바닥 모델: AI Hub 인도보행 영상(189) Surface 로 먼저 학습

edge_walk 프로젝트에 받아둔 AI Hub 189 Surface(보행자 시점 1920x1080, CVAT 폴리곤)에 보도·점자블록·차도·횡단보도가 픽셀 단위로 라벨돼 있어서, **바닥 인식은 직접 라벨링 없이** 학습할 수 있습니다.

```bash
.venv/Scripts/python training/import_aihub189.py          # -> data/aihub189_surface (원본은 하드링크, 수정 안 함)
.venv/Scripts/python training/train.py --data data/aihub189_surface/data.yaml --model models/yolo11n-seg.pt --out kick_surface.pt --name kick_surface --epochs 30
```

- 변환 결과 (영상 2프레임당 1장, 영상 단위 분할): train 19,956 / val 2,360 / test 1,030장, 폴리곤 차도 29,721 · 보도 27,788 · 점자블록 5,337 · 횡단보도 2,252
- 클래스 매핑: sidewalk(재질 무관)→보도, braille_guide_blocks→점자블록, roadway·alley(이면도로)·bike_lane→차도, attribute=crosswalk→횡단보도, caution_zone(맨홀·가로수 구역 등)은 제외. 바꾸려면 `import_aihub189.py` 의 `target_of()`.
- `models/kick_surface.pt` 가 생기면 서버가 SegFormer 대신 자동으로 사용합니다.
- edge_walk 의 기존 학습 가중치(`runs/train/v1_yolo11n_1050ti_e50`)는 쓰지 않습니다: 박스 검출 모델이고 승용차·자전거·킥보드를 `vehicle` 한 클래스로 합쳐서 킥보드만 골라낼 수 없습니다.
- 189 Bbox 의 킥보드 박스(921개)는 폴리곤이 아니라 박스라서 그대로는 분할 학습에 못 씁니다. SAM 에 박스를 넣어 마스크로 바꾸면 보탤 수 있습니다 (아직 스크립트 없음).

**보강 실험 (2026-11-18, 적용 안 함)**: Roboflow lane 데이터셋의 국내 보도 사진 254장 + AI Hub 1,000장으로 `kick_surface.pt` 를 20 epoch 이어서 학습 (`training/build_surface_ft.py`, 사용법은 파일 맨 위).

| 시험 | 지금 모델 (`kick_surface.pt`) | 보강 모델 (v2) |
|---|---|---|
| 국내 보도 사진 (lane test 42장, 보도·차도) | 0.326 | 0.625 |
| AI Hub test 1,030장 (전체 / 점자블록) | 0.842 / 0.873 | 0.801 / 0.842 |

국내 보도 사진 점수는 크게 올랐지만, lane 은 공원·광장의 회색 아스팔트를 차도로 라벨한 사진이 많아서 v2 가 그런 곳을 차도로 보게 됐습니다
(국내 보도 실제 장면 28장 중 보도 가장자리에 세운 사진 1장이 '차도 위'로 바뀜). AI Hub 점수와 점자블록 점수도 떨어져서 지금 모델을 그대로 씁니다.

### 0-1. 킥보드 모델: Roboflow Universe 공개 데이터셋으로 학습

윤곽(폴리곤) 라벨이 이미 있는 공개 데이터셋 세 개를 합쳐 `models/kick_seg.pt` 를 학습했습니다. 받은 zip 의 실제 내용 기준입니다 (페이지에 적힌 장수와 다름).

| 데이터셋 (출처, CC BY 4.0) | 받은 사진 | 킥보드가 있는 원본 사진 | 내용 | `--map` |
|---|---|---|---|---|
| [kickboard (sully)](https://universe.roboflow.com/sully-aenkk/kickboard-a0rbb), v2 | 267 | 76 (97개) | 보도에 세운 공유 킥보드, 점자블록 | `0=drop,1=kickboard` (0 이 점자블록) |
| [kickboard (kickboard)](https://universe.roboflow.com/kickboard-fxlfk/kickboard-7qar2) | 2,151 (증강 포함, 원본 764) | 194 | 점자블록 위주, 킥보드는 제품·웹 사진이 많음 | `scooter=kickboard,guide_block=drop` |
| [kb (jihoon)](https://universe.roboflow.com/jihoon-xw2ya/kb-erubr), v2 | 529 (증강 포함, 원본 184) | 184 | 국내 길거리 킥보드, 주행 장면, 넘어져 쌓인 킥보드(라벨은 kickboard) | `kickboard=kickboard` |
| [lane (kick board)](https://universe.roboflow.com/kick-board/lane-eb6kp), v5 | 473 | 28 | 국내 보도에 세운 공유 킥보드 — **학습에 안 쓰고 실제 장면 시험용** | `kick=kickboard`, 나머지 drop |

1. Roboflow 에 로그인 → 각 데이터셋 Download → 형식 **YOLOv11** → `data/roboflow/<이름>/` 에 압축 해제
   (다운로드 링크를 curl 로 받으면 Cloudflare 확인 페이지가 와서, 브라우저로 받아야 합니다)
2. 합치기 (점자블록은 바닥 모델이 잡으므로 drop. Roboflow 증강본이 train/test 로 갈리지 않게 원본 이름 단위로 다시 나눔):
   ```bash
   .venv/Scripts/python training/import_roboflow.py --src data/roboflow/kickboard-a0rbb --map "0=drop,1=kickboard" --src data/roboflow/kickboard-7qar2 --map "scooter=kickboard,guide_block=drop" --src data/roboflow/kb-erubr --map "kickboard=kickboard"
   ```
   → 2,947장 (train 2,227 / val 471 / test 249), 킥보드 없는 사진 1,794장 포함
3. 학습: `.venv/Scripts/python training/train.py --data data/kick_seg/data.yaml --model models/yolo11n-seg.pt --name kick_seg --out kick_seg.pt --epochs 60`
   (GTX 1050 Ti, batch 8, 60 epoch 약 2.5시간)

**결과** (2026-11-18, `models/kick_seg.pt` = epoch 53 의 best)

| 평가 | 사진 | kickboard mask mAP50 | mask mAP50-95 | P / R |
|---|---|---|---|---|
| 검증 (val, 학습 중 최고) | 471장 | 0.536 | — | — |
| 시험 (test split) | 249장, 킥보드 212개 | **0.671** | 0.362 | 0.727 / 0.679 |
| 실제 장면 (lane, 킥보드 라벨 있는 사진만) | 52장 (원본 28), 킥보드 64개 | **0.812** | 0.519 | 0.803 / 0.828 |

- 목표(0.85)에는 못 미칩니다. 시험 데이터에 제품 사진·주행 장면·작게 찍힌 킥보드가 많아서 낮게 나오고, 우리 촬영 조건에 가까운 lane 에서는 더 높게 나옵니다.
- lane 전체(473장)로 재면 0.217 이 나오는데, 킥보드가 찍혀 있어도 라벨이 없는 사진이 많아서(킥보드 없는 사진 421장 중 261장에서 conf 0.5 이상 검출, 눈으로 보니 대부분 실제 킥보드) 믿을 수 없는 값입니다.
- `fallen_kickboard` 는 학습 데이터가 0개라 점수가 없고, 넘어짐(FALLEN) 판정은 아직 작동하지 않습니다.
- 데이터셋 이미지는 재배포하지 않습니다(`data/` 는 git 에서 제외). 위 표의 출처 링크와 CC BY 4.0 표기를 지워서는 안 됩니다.
- 직접 찍은 반납 사진(`data/eval/approve`, `data/eval/reject`)으로 판정 정확도를 잰 적은 아직 없습니다 (`training/evaluate.py`).

국내 공유 킥보드 모양·넘어진 킥보드·야간 사진에 맞추려면 직접 찍은 사진으로 학습하는 게 좋습니다 (아래 1~5).

1. **사진 모으기** → `data/raw/`. 앱으로 반납 테스트하면 `data/collected/` 에도 쌓입니다. 낮/밤, 보도 폭, 여러 업체의 킥보드, 넘어진 킥보드, 차도·점자블록·횡단보도 근처를 골고루 (최소 300장, 목표 1,500장 이상).
   파일 이름 앞에 장소를 붙이세요 (`북문__IMG_0012.jpg`). 4번 분할 때 같은 장소 사진이 학습·검증에 나뉘어 들어가지 않게 합니다.
2. **사전 라벨** — AI가 먼저 그려두고 사람은 고치기만:
   ```bash
   .venv/Scripts/python training/prelabel.py data/raw
   ```
3. **라벨 수정** — `data/prelabels` 를 CVAT 또는 Roboflow 에 "YOLO segmentation" 형식으로 올리고, 클래스(`training/dataset.yaml`: kickboard, fallen_kickboard, sidewalk, road, braille_block, crosswalk)를 고치고 빠진 것을 추가. 넘어진 킥보드는 `fallen_kickboard` 로 바꿔주세요. SAM 자동 마스크 기능을 쓰면 빠릅니다.
4. **내보내기** → `data/labeled/images`, `data/labeled/labels` 에 넣고 분할:
   ```bash
   .venv/Scripts/python training/split_dataset.py
   ```
5. **학습** (GTX 1050 Ti 4GB 기준):
   ```bash
   .venv/Scripts/python training/train.py --model yolo11n-seg.pt --batch 8 --epochs 100
   ```
   끝나면 `models/kick_seg.pt` 로 복사되고, 서버를 재시작하면 적용됩니다.
6. **판정 평가** — 사람이 판단한 사진을 `data/eval/approve/`, `data/eval/reject/` 에 넣고:
   ```bash
   .venv/Scripts/python training/evaluate.py
   .venv/Scripts/python training/evaluate.py --max-width-occupancy 0.55   # 임계값 실험
   ```
   가장 위험한 오류인 "거절돼야 하는데 통과된 사진"을 따로 보여줍니다. 틀린 사진을 다시 라벨링해서 3번부터 반복하세요.

   지표는 `data/eval/summary.json` 에 남으니 학습할 때마다 비교하세요.

   | 지표 | 뜻 | 목표 |
   |---|---|---|
   | 위험 통과율 | 거절돼야 하는 사진 중 통과된 비율 | 2% 이하 |
   | 잘못 거절률 | 통과돼야 하는 사진 중 거절된 비율 | 낮을수록 |
   | 재촬영률 | 재촬영을 요청한 비율 | 25% 이하 |
   | cm 실측률 | 점자블록으로 cm 실측이 된 비율 | 높을수록 |
   | 통행폭 오차 | 줄자 대비 평균 절대오차 (`width_gt.csv` 있을 때) | 10~15cm |

   **통행폭 오차**: 촬영할 때 줄자로 잰 실제 남은 통행폭을 `data/eval/width_gt.csv` (`file,residual_cm`, 예: `approve/IMG_0012.jpg,185`)에 적어두면 평균 절대오차·편향을 같이 출력합니다 (목표 10~15cm). 조감도는 `data/eval/_vis/*_bev.jpg`.

## 아직 안 된 것 / 다음 단계

- **실제 사진 검증**: cm 실측은 합성 장면으로만 검증됨. 실제 점자블록은 마모·오염·야간 조명·점형 블록 때문에 이음매 검출이 더 어렵습니다. 줄자 실측 사진으로 오차를 재고, 부족하면 `braille_tile` 라벨을 추가해 블록을 모델이 직접 분할하게 하세요.
- **점자블록 외 대체 기준**: 보도블록 격자, 단안 깊이추정은 아직 없음. 점자블록이 안 보이면 60% 비율 규칙만 적용됩니다.
- **촬영 방향**: 실측이 안 될 때(비율 규칙) 보도를 옆에서 가로질러 찍으면 폭 비율이 실제보다 훨씬 작게 나옵니다. 보도가 화면을 가로지르는 띠로만 보이는 사진은 `CROSS_VIEW` 로 재촬영을 요청하지만, 비스듬히 찍었거나 띠가 화면 한쪽에서 끊긴 사진은 걸러내지 못합니다. 촬영 화면에 글자 안내는 있지만, 기울기 센서로 각도를 잡아주는 기능은 아직 없습니다.
- **고정 장애물**(가로수·볼라드·입간판) 클래스가 없어 통행폭에서 빼지 못함. 지금은 보도 마스크의 작은 끊김을 지나갈 수 있는 곳으로 봅니다.
- **얼굴·번호판 블러**: 저장 사진의 EXIF 는 지우지만 얼굴·번호판은 아직 그대로입니다. OpenCV 5 에는 얼굴 검출 데이터가 들어 있지 않아 별도 모델(예: YuNet)이 필요합니다.
- **GPS 신뢰**: 위치는 휴대폰이 보낸 좌표를 그대로 믿습니다. 위조를 막으려면 킥보드 단말기 GPS 와 대조해야 합니다.
- 사용자 인증, 세션 DB 저장(지금은 메모리, 서버 여러 개로 늘리면 세션이 안 맞음).
- 반복 침해 지점 집계(DBSCAN + PostGIS)와 담당자 대시보드.
