# Mac 네이티브 실종동물 검색 실행 소스

이 디렉터리는 macOS arm64/Python 3.12에서 `app.entrypoints.vision_api:app`을 실행하기 위한
의존성 핀과 SAM3 MPS 패치를 보관한다. 일반 Python API와 Photo는 별도 Docker
서비스다. 실제 비밀값, 모델 가중치, 사진, 갤러리, 로그, 가상환경은 Git에 넣지 않는다.

## 운영 코드와 실행 책임

- `LOST_SEARCH_DEVICE`의 기본값은 기존 `cuda`이며 Mac 관리형 실행기는 `mps`를 명시한다.
- DINO FP16 / SAM3 BF16 autocast와 기존 모델·전처리·1024차원 계약을 유지한다.
- CUDA 또는 MPS 모델 오류를 CPU 추론으로 자동 재시도하지 않는다.
- 검증한 Mac 설정은 CPU 스레드 2개, MPS allocator 상한 16 GiB, 모델 프로세스 1개다.
  이 상한은 예약 메모리나 서버 전체의 RAM 사용량을 뜻하지 않는다.
- 실제 운영 진입점과 정상 종료·갤러리 예약은 저장소 루트의
  `deploy/scripts/mac_native_service.py`, `mac_native_supervisor.py`,
  `mac_background_jobs.py`가 관리한다. 이 저장소의 앱 소스만 교체해도 이미 배포한
  실행본이 자동으로 바뀌지는 않는다.
- 시험 전용 `run_lost_mps.py`와 시험 alias/env 예시는 이 게시 범위에 포함하지 않는다.

## 독립 Python 환경 준비

설치는 새 실행본의 별도 가상환경에서 한다. 아래는 준비 예시이며 운영 프로세스
재시작이나 현재 가상환경 변경 명령이 아니다.

```sh
python3.12 -m venv /absolute/path/to/new-runtime/venv
/absolute/path/to/new-runtime/venv/bin/python -m pip install \
  -c deploy/macos/runtime.constraints.txt -r requirements-lost-mps.txt wheel==0.45.1
```

고정 SAM3 원본은 다음 archive다.

- revision: `660a5e9e1b8b4c02c0ad97229b88a09a6e4ff5b7`
- archive: `https://codeload.github.com/facebookresearch/sam3/tar.gz/660a5e9e1b8b4c02c0ad97229b88a09a6e4ff5b7`
- archive SHA-256: `a5de213683abe6e141897f40817f670ea850155916831eda66d315c8c50f1cda`

다운로드한 **동일 archive 파일**의 SHA-256을 위 값과 먼저 대조한 뒤 새 폴더에
안전하게 해제한다. `prepare_sam3_mps.py`의 파일 단위 검증은 archive 전체의 무결성
검사를 대신하지 않는다. 원본의 `pyproject.toml`, README, LICENSE도 같은 archive에서
가져와야 한다.

```sh
/absolute/path/to/new-runtime/venv/bin/python scripts/prepare_sam3_mps.py \
  /absolute/path/to/verified-sam3-upstream /absolute/path/to/new-runtime/sam3
/absolute/path/to/new-runtime/venv/bin/python -m pip install \
  --no-deps --no-build-isolation /absolute/path/to/new-runtime/sam3
/absolute/path/to/new-runtime/venv/bin/python -m pip check
```

준비 스크립트는 upstream 패키지 파일 193개의 해시를 대조하고 새 목적지에 패치를
적용한다. 최종 manifest는 수정·신규 파일 8개를 검증하며 `mps_attention.py`를 포함한다.
기존 목적지나 site-packages를 덮어쓰지 않는다. 패치·최종 파일 해시는
`sam3-manifest.json`에 있다. 가중치는 별도 비공개 위치에서 공급하고 기존 SHA-256
검증을 유지한다.

SAM3 원본과 패치 대상 코드의 이용 조건은 고정 원본의 [SAM License](SAM3-LICENSE)를
따른다. 라이선스 사본은 위 고정 revision에서 가져왔으며, 이 패치의 제공이 upstream
가중치 접근 권한을 부여하는 것은 아니다.

## 배포와 검증 경계

PG·내부 API 인증과 모델·갤러리 경로는 저장소 밖의 제한된 설정 파일로 공급한다.
`deploy/scripts`의 관리형 실행기가 torch import 전에 MPS fallback과 allocator 설정을 적용하고
루프백·단일 모델 프로세스로 시작한다. 실제 기동·중지·갤러리 게시와 별칭 전환은
별도의 운영 배포 절차를 따른다.

실행기는 시작할 때 소스 폴더의 모든 파일(`__pycache__` 제외)을 승인된 receipt와 대조한다.
receipt는 `main` 커밋을 `git archive`로 새 소스 폴더에 푼 뒤 같은 규칙으로 만든다.

```sh
python deploy/scripts/mac_native_service.py --receipt /absolute/path/to/new-source > native-source-receipt-<sha>.json
```

`.py` 파일만 담긴 이전 receipt(`python_files`)도 롤백을 위해 받아들이지만, 이때는
`sam3-manifest.json` 같은 비 Python 파일을 검사하지 않는다.

빠른 회귀 검증 예시:

```sh
python -m unittest tests.test_gpu_device tests.test_dinov3 tests.test_sam3_focus
```

위 단위 테스트는 실제 GPU 추론·DB·전체 검색 품질 검증을 대신하지 않는다. 운영에
사용한 MPS 패치의 재현·실제 검색 근거는 인프라의 운영 출처 기록과 연결한다. 실제
재부팅·정전 복귀, 장기 부하와 앞으로의 예약 실행까지 검증됐다고 확대하지 않는다.

## SAM3 패치가 바뀔 때의 교체 절차

실행기(`mac_native_service.py`)는 기동할 때 소스의 `deploy/macos/sam3-manifest.json`에 적힌
`patched_files` 해시를 실제로 import되는 `sam3` 패키지와 대조하고, 하나라도 다르면
`Pinned SAM3 patch changed`로 멈춘다. 따라서 이 패치나 manifest가 바뀐 소스는 **앱 소스와
SAM3 패키지를 한 번에** 교체해야 한다. 소스만 바꾸거나 패키지만 바꾸면 서비스가 기동하지 않는다.

1. 위 "독립 Python 환경 준비"대로 검증된 원본 사본에 `prepare_sam3_mps.py`로 새 패키지를
   만든다. 목적지는 기존 패키지를 덮어쓰지 않는 새 디렉터리(예: `sam3-package-<패치 후 파일 해시 앞 7자리>`)로
   둔다. 기동 검사와 같은 방법(manifest `patched_files` 해시 대조)으로 새 디렉터리를 먼저 확인한다.
2. launchd plist의 `PYTHONPATH`는 그대로 두고(재적재 불필요), 기존 `sam3-package` 디렉터리를
   `sam3-package-<이전 해시>-before-<시각>`으로 이름을 바꾼 뒤 새 디렉터리를 `sam3-package`로 바꾼다.
   같은 교체 안에서 실행 설정의 소스·receipt 2줄을 새 main 커밋으로 바꾸고 `launchctl kickstart -k`로
   다시 띄운다. 기동 로그에서 `Pinned SAM3 patch changed`가 없고 `/health`가 200인지 본다.
   (2026-11-04 교체가 이 방식이다. 실행 설정 백업 파일과 이전 패키지 디렉터리는 남긴다.)
3. 롤백은 둘을 함께 되돌린다: 디렉터리 이름을 반대로 바꾸고 실행 설정 백업을 복원한 뒤 다시 띄운다.

주의: 가상환경 `site-packages`에는 패치되지 않은 upstream `sam3`가 따로 설치돼 있고, 운영은
`PYTHONPATH`의 패키지로 이를 가린다. plist에서 `PYTHONPATH`를 빼면 패치 안 된 패키지가
import되어 기동 검사에 걸린다.

### MPS 어텐션 직접 구현 (2026-11-04)

`sam3/perflib/mps_attention.py`는 PyTorch 2.10의 MPS `scaled_dot_product_attention`이 이 모델의
호출 모양에서 느린 경로를 타는 문제(SAM 3 한 장 2.4초 중 1.4초) 때문에 마스크 없는 추론 어텐션을
행렬곱·softmax·행렬곱으로 직접 계산한다(SAM 3 한 장 2.37초 → 1.28초, 마스크·벡터는 원래 경로와 같음).
`tests/test_sam3_mps_attention.py`가 패치 조각을 그대로 실행해 계약을 검사한다.

- 되돌리기: 환경변수 `PAWBRIDGE_SAM3_MPS_ATTENTION=sdpa`를 서비스 환경에 넣으면 재빌드 없이
  원래 경로(512 질의 분할 + PyTorch 함수)로 돌아간다.
- 제거 조건: PyTorch 2.16은 같은 경로를 Metal 커널로 바꾼다(개발판 실측에서 직접 구현보다 빠름).
  2.16을 채택할 때 같은 측정으로 재비교한 뒤 이 직접 구현을 제거한다.
