# 안주잘부 — AI 주차 판정 공유 킥보드 반납

반납 순서를 **① GPS 위치 → ② 주차 사진 AI 판정 → ③ 결제** 로 강제합니다. 서버가 세션 단계를 관리해서 앞 단계를 통과하지 못하면 다음 API가 `409`를 돌려줍니다.

| 단계 | 검사 | 결과 |
|---|---|---|
| ① 위치 | `server/zones.geojson` 의 서비스 지역 / 지정 주차구역 / 금지구역, GPS 정확도 | 통과해야 반납 토큰 발급 |
| ② 사진 | 차도에 세움 · 횡단보도 · 점자블록 점유 · 전도 · **보도 가로폭 60% 이상 점유** · **남은 통행폭 120cm 미만** (점자블록으로 실측될 때) | `APPROVE` / `REJECT` / `RETAKE` (+ 경고·주의 안내) |
| ③ 결제 | 사진이 `APPROVE` 일 때만 가능 (지금은 데모 결제) | 반납 완료 |

## 구조

```
server/
  main.py       FastAPI: /api/return/location → /photo → /pay, 웹 화면 서빙
  zones.py      GPS 폴리곤 판정 (point-in-polygon, 오차반경 고려)
  analyzer.py   사진 → 마스크 (YOLO-seg + SegFormer) → 판정 → 근거 이미지
  rules.py      판정 규칙·등급·침해점수 (모델과 독립, 임계값은 Thresholds)
  ground.py     점자블록 30cm 기준 호모그래피 → 조감도 → 단면별 잔여 통행폭(cm)
  zones.geojson 샘플 구역 (경북대 북문 일대, 실제 좌표로 교체 필요)
web/            모바일 반납 화면 (지도, 카메라, 판정 결과, 결제)
training/       데이터셋 설정 · 사전라벨 · 학습 · 판정 평가
tests/          규칙·API 테스트 (모델 없이 실행)
data/collected/ 반납 때 올라온 사진+판정 결과 (재학습용으로 자동 저장)
```

### 판정 방식 (`server/rules.py`)

- **접지부**: 기기 마스크 아래쪽 25%. 그 주변 바닥 픽셀 중 차도가 30% 넘으면 `ON_ROAD`, 점자블록 5%·횡단보도 10% 넘으면 각각 거절.
- **보도 폭 점유율**: 기기 아래쪽 40% 구간의 각 행(가로 단면)마다 `기기가 막은 폭 / 그 행의 보도 폭`을 재고 90퍼센타일이 60% 이상이면 `WIDTH` 거절. 여러 대가 같이 있으면 **합쳐서** 계산(군집 효과).
  - 평평한 지면을 수평으로 찍으면 한 행은 같은 거리의 가로선이라 픽셀 비율 = 실제 폭 비율입니다. 그래서 **보도 진행 방향으로 찍은 사진**에서 정확합니다.
- 보도 양끝이 화면 밖이라 실제 폭을 모르면 거절 대신 `TRUNCATED` 재촬영 요청.

### cm 실측 (`server/ground.py`) — 점자블록을 '자'로

1. **블록 사각형 찾기**: 점자블록 띠 마스크에서 블록 사이 이음매(어두운 선)를 찾아 블록 한 장씩 사각형을 만든다. 모델이 `braille_tile`(블록 한 장)을 분할하거나 색 검출로 블록이 따로 떨어져 보이면 그것도 쓴다. 학습 모델이 없을 땐 노란색으로 점자블록을 찾는다(차도 중앙선은 제외).
2. **30cm 격자 맞춤**: 가장 큰 블록 하나로 호모그래피를 만들고, 가까운 블록부터 꼭짓점을 30cm 격자점에 맞춰가며 다시 맞춘다. 이음매를 하나 놓쳐 60cm짜리 칸이 섞여도 같은 격자로 정리된다. 남은 재투영 오차(cm)와 블록 수로 신뢰도(높음/보통/낮음)를 낸다. **낮음이면 cm 판정을 쓰지 않고** 60% 비율 규칙만 쓴다.
3. **조감도 단면**: 바닥(보도+점자블록)을 2cm/px 조감도로 펴고, 보도 방향(점자블록 띠 방향)에 수직인 단면을 10cm 간격으로 잘라 **기기를 피해 연속으로 지나갈 수 있는 폭**을 잰다. 최솟값 = 병목 통행폭. 기기는 바닥에 닿은 점(차체 맨 아래)만 지면으로 펴고 최소 차체 폭 55cm를 보장한다.
4. **등급** (문제정의서 ⑤): 위험 < 120cm 또는 점자블록·횡단보도 점유 → 반납 거절 / 경고 120~150 / 주의 150~200 또는 3대 이상 군집 → 반납은 되지만 안내 / 양호 ≥ 200. 같은 등급 안의 우선순위용 **침해 점수(0~100)** = 통행폭 45% + 점자블록 근접 20% + 횡단보도 15% + 전도 10% + 군집 10% (사람 판정으로 가중치 보정 필요).
5. 점자블록이 없으면: 기기를 옆에서 찍었을 때 차체 길이(킥보드 115cm, 자전거 165cm)로 대략 환산한 값을 **표시만** 한다 (합성 장면에서 실제 235cm를 276cm로 볼 만큼 부정확해서 판정에는 안 씀).

합성 원근 장면(`tests/synth.py`: 폭 3m 보도 + 30cm 점자블록 띠, 높이 1.5m 카메라)에서 축척 오차 1~2%, 잔여 통행폭 오차 ±5cm 이내로 확인했습니다. 실제 사진 정확도는 줄자 실측 데이터로 따로 검증해야 합니다 (아래 6번).

현장 점자블록 규격이 30cm가 아니거나 선형 블록 띠가 2장 폭이면 `Thresholds.tile_cm`, `strip_tiles` 를 바꾸세요.

## 실행

```bash
python -m venv .venv
.venv/Scripts/python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu126
.venv/Scripts/python -m pip install -r requirements.txt
.venv/Scripts/python -m uvicorn server.main:app --host 0.0.0.0 --port 8000
```

http://localhost:8000 → "테스트 위치로 확인"에서 구역을 골라 흐름을 따라갈 수 있습니다. 첫 실행 때 모델(YOLO 20MB, SegFormer 110MB)을 내려받습니다.

**휴대폰으로 테스트할 때**: 브라우저는 HTTPS에서만 GPS·실시간 카메라를 허용합니다(HTTP면 카메라 앱 업로드로 대체됨). 자체 서명 인증서로 실행하세요.

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 365 -keyout key.pem -out cert.pem -subj "/CN=kick"
.venv/Scripts/python -m uvicorn server.main:app --host 0.0.0.0 --port 8443 --ssl-keyfile key.pem --ssl-certfile cert.pem
```

테스트: `.venv/Scripts/python -m pytest -q`

## AI 학습시키기

지금은 **베이스라인**(COCO YOLO-seg 자전거 인식 + Cityscapes SegFormer 차도/보도 분할)으로 돌아갑니다. 학습 없이 바로 쓸 수 있지만:

- 킥보드는 COCO에 없는 클래스라 잘 못 잡고, 점자블록·횡단보도는 아예 모릅니다.
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
- 189 Bbox 쪽에는 킥보드(scooter) 박스가 921개뿐이고 폴리곤이 아니라 박스라서, **킥보드 인식은 여전히 직접 찍은 사진 라벨링(아래 1~5)이 필요**합니다.

1. **사진 모으기** → `data/raw/`. 앱으로 반납 테스트하면 `data/collected/` 에도 쌓입니다. 낮/밤, 보도 폭, 킥보드·전기자전거, 차도·점자블록·횡단보도 근처를 골고루 (최소 300장, 목표 1,500장 이상).
2. **사전 라벨** — AI가 먼저 그려두고 사람은 고치기만:
   ```bash
   .venv/Scripts/python training/prelabel.py data/raw --vehicle-class kickboard
   ```
3. **라벨 수정** — `data/prelabels` 를 CVAT 또는 Roboflow 에 "YOLO segmentation" 형식으로 올리고, 클래스(`training/dataset.yaml`: kickboard, ebike, fallen_vehicle, sidewalk, road, braille_block, crosswalk)를 고치고 빠진 것을 추가. SAM 자동 마스크 기능을 쓰면 빠릅니다.
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

   **통행폭 오차**: 촬영할 때 줄자로 잰 실제 남은 통행폭을 `data/eval/width_gt.csv` (`file,residual_cm`, 예: `approve/IMG_0012.jpg,185`)에 적어두면 평균 절대오차·편향을 같이 출력합니다 (목표 10~15cm). 조감도는 `data/eval/_vis/*_bev.jpg`.

## 아직 안 된 것 / 다음 단계

- **실제 사진 검증**: cm 실측은 합성 장면으로만 검증됨. 실제 점자블록은 마모·오염·야간 조명·점형 블록 때문에 이음매 검출이 더 어렵습니다. 줄자 실측 사진으로 오차를 재고, 부족하면 `braille_tile` 라벨을 추가해 블록을 모델이 직접 분할하게 하세요.
- **점자블록 외 대체 기준**: 보도블록 격자, 단안 깊이추정은 아직 없음. 점자블록이 안 보이면 60% 비율 규칙만 적용됩니다.
- **촬영 방향**: 실측이 안 될 때(비율 규칙) 보도를 옆에서 가로질러 찍으면 폭 비율이 부정확합니다. 촬영 가이드("보도 방향으로 찍기")가 필요합니다.
- **고정 장애물**(가로수·볼라드·입간판) 클래스가 없어 통행폭에서 빼지 못함. 지금은 보도 마스크의 작은 끊김을 지나갈 수 있는 곳으로 봅니다.
- 실제 PG 결제, 사용자 인증, 세션 DB 저장(지금은 메모리), 얼굴·번호판 블러, 사진 촬영 위치 GPS 재확인.
- 반복 침해 지점 집계(DBSCAN + PostGIS)와 담당자 대시보드.
