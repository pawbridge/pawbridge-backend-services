# PawBridge Python AI Service

> 이 폴더는 2026-10-26 `pawbridge/pawbridge-python` 저장소(dev `844facb6`)를 Git 이력째 옮겨 온 것입니다. 원본 저장소는 읽기 전용으로 보관했으며, 이후 Python 코드는 이 저장소의 `python/`에서만 고칩니다.

PawBridge 백엔드(`backend-app`)가 내부 API로 부르는 Python 서비스 모음입니다. 실종동물 사진 검색, 유사 동물 추천, 동물 상담 챗봇, 보호소 사진 압축을 맡습니다.

## 구성

| 진입점 | 하는 일 | 운영 실행 위치 |
| --- | --- | --- |
| `app.entrypoints.vision_api:app` | 실종동물 사진 검색(`POST /internal/animals/lost-candidates`), 유사 동물 추천(`GET /internal/animals/{id}/similar`), 갤러리 자동 갱신 | Mac 네이티브 프로세스(launchd `kr.pawbridge.native`, MPS GPU). 백엔드는 `native-relay`로 보낸다 |
| `app.entrypoints.chatbot_api:app` | 동물 상담 챗봇(`POST /internal/chatbot/messages`, Gemini) | 컨테이너 `pawbridge-prod-python-api` |
| `app.entrypoints.photo_api:app` | APMS 사진 압축(`POST /internal/photos/optimize`), [계약](PHOTO_SERVICE.md) | 컨테이너 `pawbridge-prod-photo` |
| `app.entrypoints.gallery_cli` | 스냅샷과 로컬 사진으로 갤러리를 수동 생성하는 명령줄 도구 | 필요할 때만 수동 실행 |

모든 내부 API는 `X-Internal-Api-Key`를 확인한다.

```
app/
├── entrypoints/        # 실행 진입점: chatbot_api, vision_api, photo_api, gallery_cli
├── chatbot/            # 챗봇 경로(router)와 Gemini·stub 제공자, 프롬프트
├── lost_search/        # 실종 사진 검색 경로(router)와 순위(service)
├── recommendation/     # 유사 동물 추천 경로(router)와 순위(service)
├── gallery/            # 갤러리 빌드(builder)·자동 갱신(runtime, source, pages, inputs, prefetch)·보관(retention)
│                       # PostgreSQL 저장소(storage, pg_store, pg_incremental)
├── vision/             # 모델 추론: dinov3, sam3_focus, animal_focus, coat_color, gpu_device, inference_gate
└── photo/              # 사진 압축(optimizer, 사진 서비스 전용, Torch 없음)
deploy/macos/           # 네이티브 실행본 설치 런북, SAM 3 패치·고정 목록
scripts/                # SAM 3 MPS 준비 스크립트
tests/                  # unittest. tests/integration은 PostgreSQL이 필요한 선택 시험
```

## 실종동물 검색과 추천 (`app.entrypoints.vision_api`)

| 설정 | 운영 값 | 설명 |
| --- | --- | --- |
| `LOST_SEARCH_VISUAL_PROFILE` | `sam3-animal-focus` | SAM 3로 동물 영역을 찾고 DINOv3로 벡터를 만든다. PostgreSQL 갤러리는 이 프로필만 지원한다 |
| `LOST_SEARCH_DEVICE` | `mps` | `cuda` 또는 `mps`. CPU로 자동 대체하지 않는다 |
| `LOST_STORAGE_BACKEND` | `postgresql` | 생략하면 `postgresql`. 다른 값이면 시작하지 않는다(Elasticsearch 저장소는 제거됨) |
| `LOST_PG_DSN`, `LOST_PG_POOL_MAX_SIZE` | 비밀 저장소 | 갤러리 표에 접속한다. 연결 수는 1~4 |
| `LOST_GALLERY_SYNC_ENABLED`, `LOST_GALLERY_PROTOCOL` | `true`, `v2` | 프로세스 안에서 갤러리를 주기적으로 갱신한다 |

GPU당 프로세스 하나로 실행하고 `--reload`나 다중 worker를 쓰지 않는다. 네이티브 실행본 설치·교체는 [macOS 런북](deploy/macos/README.md)을 따른다.

- DINOv3·SAM 3 체크포인트의 SHA-256이 코드의 고정값과 다르면 시작하지 않는다. 실행 중 가중치를 내려받지 않는다.
- 시작할 때 공개 중인 갤러리의 모델 버전과 색 특징 버전을 확인한다. 맞지 않으면, 자동 갱신이 켜져 있을 때만 준비 전 상태로 시작해 갤러리를 다시 만든다.
- 전체 사진 벡터는 항상 보존하고, 동물 하나가 확인된 경우에만 동물 영역 벡터를 따로 만든다. 양쪽에 동물 벡터가 있으면 전체 사진 0.3 + 동물 영역 0.7로 비교하고, 없으면 전체 사진으로 비교한다.
- 추론 오류·메모리 부족은 빈 결과로 숨기지 않고 503으로 돌려준다. 이 점수는 같은 개체일 확률이 아니다.

### 갤러리 갱신

- 같은 사진 해시·종·모델의 벡터는 다시 계산하지 않는다. 메타데이터만 바뀌면 재추론하지 않는다.
- 새 빌드를 따로 작성하고 건수를 확인한 뒤 공개 대상을 원자적으로 바꾼다. 내려받기·추론·쓰기가 실패하면 기존 공개 갤러리를 그대로 둔다.
- 사진은 현재 한 장과 다음 한 장만 임시로 받아 두고, 실패·취소 때도 지운다. GPU 추론은 사용자 검색을 우선한다.
- `v2` 공급 프로토콜은 페이지당 최대 100건·2MiB를 받고, 확인된 커서와 해시만 상태 파일에 남긴다. 서명 URL은 상태 파일에 기록하지 않는다.
- 공개본과 직전본만 남기고, 상태 디렉터리의 기록에 있는 미공개 빌드만 정리한다.

### 갤러리 수동 생성 (`app.entrypoints.gallery_cli`)

APMS·R2 자격 증명을 가져오지 않는다. 입력은 수집 쪽에서 준비한 완전한 스냅샷과 로컬 사진 캐시(`<source_sha256>.image`)다. 같은 상태 디렉터리의 파일 잠금으로 검색 서버의 자동 갱신과 동시에 돌지 않게 막는다.

```bash
export LOST_SEARCH_VISUAL_PROFILE=sam3-animal-focus
export LOST_SEARCH_INDEX=animals-lost-dinov3-sam3-gallery-v1
# 가중치 경로, LOST_PG_DSN 등은 비밀 저장소에서 주입한다.
python -m app.entrypoints.gallery_cli --manifest /path/to/snapshot.json \
  --photo-root /path/to/photos --state-dir /path/to/gallery-state
# 결과를 확인한 뒤 실제로 작성·공개할 때만 --apply를 붙인다.
```

## 로컬 시험

GitHub Actions는 `python/` 전체가 컴파일되는지만 확인합니다. 시험은 병합 전에 로컬에서 돌립니다. 의존성을 새로 설치하지 않도록 운영 중인 `python-ai-service` 이미지 안에서 네트워크 없이, 소스는 읽기 전용으로 붙여 실행합니다. 이 이미지에는 사진 서비스 시험에 필요한 의존성도 들어 있습니다.

```bash
docker run --rm --network none --read-only --tmpfs /tmp -e PYTHONDONTWRITEBYTECODE=1 \
  -v "$PWD/python":/src:ro -w /src --entrypoint python \
  "$(docker inspect pawbridge-prod-python-api --format '{{.Image}}')" \
  -m unittest discover -s tests
```

저장소 루트에서 실행합니다. `requirements*.txt`를 바꿨다면 운영 이미지에 새 의존성이 없으므로, 아래 빌드 절차로 새 이미지를 먼저 만든 뒤 그 이미지로 시험합니다. 모델 가중치·GPU·DB가 필요한 시험은 건너뜀(skip)으로 표시되며, 실제 모델 품질 검증이 아닙니다.

## 운영 빌드와 배포

운영은 Mac mini의 Docker Compose 프로젝트 `pawbridge-prod`와 Mac 네이티브 프로세스로 나뉩니다. 이미지는 레지스트리에 올리지 않고 로컬에서 만듭니다. 원본 저장소의 이미지 발행 CI는 이 저장소로 옮기지 않았습니다.

| 운영 구성요소 | 소스 | 이미지·실행본 |
| --- | --- | --- |
| `python-ai-service`(컨테이너 `pawbridge-prod-python-api`) | `python/Dockerfile` | `pawbridge-mac-python:<커밋 7자리>` |
| `photo-service`(컨테이너 `pawbridge-prod-photo`) | `python/Dockerfile.photo` | `pawbridge-mac-photo:<커밋 7자리>` |
| Mac 네이티브 실종동물 검색(launchd `kr.pawbridge.native`) | `app.entrypoints.vision_api`, `deploy/macos/` | 별도 실행본과 가상환경([macOS 런북](deploy/macos/README.md)) |

컨테이너 서비스 교체 순서:

1. **main 커밋에서만 빌드한다.** dev 병합과 로컬 시험을 거쳐 main에 들어간 커밋을 깨끗한 작업 폴더로 꺼낸다.
2. **이미지를 만든다.** 저장소 루트에서:
   ```bash
   docker build --pull=false --build-arg GIT_SHA=$(git rev-parse HEAD) -f python/Dockerfile -t pawbridge-mac-python:$(git rev-parse --short=7 HEAD) python
   docker build --pull=false --build-arg GIT_SHA=$(git rev-parse HEAD) -f python/Dockerfile.photo -t pawbridge-mac-photo:$(git rev-parse --short=7 HEAD) python
   ```
   두 Dockerfile은 운영 중인 이미지와 같은 레시피다(기반 Python 이미지 digest 고정, 설치 패키지 전체를 `constraints.txt`·`constraints-photo.txt`로 고정, `pip check`, 컨테이너 사용자 고정). 의존성을 바꾸면 새 이미지의 `pip freeze`로 해당 constraints 파일도 갱신한다. 2026-10-27까지는 인프라 저장소의 `environments/mac/images/`에 있었다. 바뀐 서비스만 빌드한다. 빌드는 Python 패키지를 내려받는다. Docker Desktop 자격 증명 도우미를 못 찾으면 `PATH`에 `$HOME/.docker/bin`을 더한다.
3. **새 이미지로 시험한다.** 위 "로컬 시험" 명령의 이미지 자리에 새 `pawbridge-mac-python` 태그를 넣어 실행한다.
4. **교체한다.** 운영 compose 폴더([`deploy/compose`](../deploy/compose/README.md))에서 `compose.env`를 날짜 붙은 이름으로 백업하고, `PYTHON_API_IMAGE` 또는 `PHOTO_IMAGE` 한 줄만 새 이미지 ID(`docker image inspect <태그> --format '{{.Id}}'`)로 바꾼 뒤 그 서비스만 다시 만든다. 실행 명령은 이미지 CMD를 따른다.
   ```bash
   docker compose -p pawbridge-prod -f compose.yaml --env-file compose.env up -d --no-build --pull never --no-deps python-ai-service
   ```
5. **확인한다.** 컨테이너가 재시작 없이 실행 중이고, 백엔드의 동물 추천·사진 기능이 정상 응답하며, 10분 동안 오류 로그와 운영 경고가 없는지 본다.
6. **롤백한다.** 백업한 `compose.env`를 되돌리고 4의 명령을 다시 실행한다. 이전 이미지는 로컬에 남아 있다.

Mac 네이티브 실행본은 앱 소스만 바꿔서는 교체되지 않는다. 새 실행본과 가상환경을 따로 준비하는 절차는 [macOS 런북](deploy/macos/README.md)을 따른다.

## 이전 환경 기록

아래는 Kubernetes·Java MSA·Elasticsearch 시절(DINOv2 384차원 유사도 서비스)의 기술 결정과 문제 해결 기록입니다. 해당 코드(`app/es/`, `routers/similarity.py`, `services/embedding.py`)는 2026-10-27 정리했고, 현재 검색은 위의 DINOv3·SAM 3·PostgreSQL 구성입니다. 당시 VM·WSL GPU 런북은 옵시디언 `Projects/pawbridge/2026-10-27 Python VM 시절 갤러리 런북 보존`에 원문으로 보존했습니다.

### MobileNetV3 → DINOv2 ViT-S/14 교체

초기에 MobileNetV3 Large(1280차원)를 사용했으나 유사도 검색 품질 문제로 교체했습니다.

| | MobileNetV3 Large | DINOv2 ViT-S/14 |
|--|--|--|
| 학습 방식 | 분류(classification) 지도학습 | 자기지도학습(self-supervised) |
| 학습 목표 | 이미지 분류 경계 최적화 | 외형 유사도 직접 최적화 |
| 출력 차원 | 1280 | 384 |
| CPU 추론 | 가능 | 가능 (torch+cpu) |

분류 모델은 "이게 강아지냐 고양이냐"를 판단하는 데 최적화되어 있어, 외형이 비슷해도 종이 다르면 벡터 거리가 크게 나왔습니다. DINOv2는 같은 이미지의 다른 뷰는 가깝게, 다른 이미지는 멀게 학습(DINO)하므로 외형 유사도 검색에 적합합니다.

### species 필터 + min_score 임계값

- `min_score=1.6`: 코사인 유사도 0.6 미만 결과 제외 → 관련 없는 동물을 억지로 채우지 않음
- `species` 필터: min_score만으로는 외형이 비슷한 이종 동물 혼재 방지 불충분 (고양이 검색 시 강아지 포함 문제 해결)

두 규칙(같은 종만, 시각 점수 0.6 이상)은 현재 추천(`app/recommendation/service.py`)에도 이어집니다.

### L2 정규화

DINOv2 공식 권장(retrieval 태스크 기준). L2 정규화된 벡터끼리의 내적 = 코사인 유사도로, 이미지 밝기·크기 차이로 인한 벡터 크기 편차를 제거합니다.

### 트러블슈팅

- **ES dims 불일치(mapper_parsing_exception):** MobileNetV3(1280차원) → DINOv2(384차원) 교체 후 임베딩 저장 실패. 배치가 증분 업데이트 방식으로 바뀌면서 기존 인덱스(dims:1280)가 유지된 것이 원인. 기존 인덱스 삭제 → 인덱스 템플릿(dims:384) 기반 재생성 → 배치 재동기화 → 임베딩 재추출로 해결.
- **임베딩 배치 중단(failed:200 반복):** APMS 만료 URL(404) 동물 200개가 매번 첫 배치에 포함되어 전부 실패 → 루프 탈출 → 나머지 9,000개 미처리. 실패 ID를 다음 배치에서 제외하도록 해결.
- **고양이 검색에 강아지 포함:** 털 색상·자세가 비슷하면 종이 달라도 유사도가 임계값을 넘는 경우가 있어, 같은 종 안에서만 검색하도록 해결.

## 관련 레포지토리

- 이 저장소 [`backend-app/`](../backend-app) — Spring 모듈형 모놀리스 백엔드(이 서비스를 호출하는 쪽)
- [pawbridge-infra-k8s](https://github.com/pawbridge/pawbridge-infra-k8s) — 이전 Kubernetes 인프라(Helm Charts, Vagrant) 기록
