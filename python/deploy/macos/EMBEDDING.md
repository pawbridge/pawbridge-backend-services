# 건강 해설 임베딩 서비스 (Mac 네이티브)

챗봇 에이전트의 `search_health` 도구가 쓰는 하이브리드 검색(규칙 + bge-m3 벡터)의 임베딩만 맡는다. 검색 로직(규칙, 합치기, 기준값, 범위 밖 관문)은 python-api에 있고, 이 서비스는 글을 받아 정규화된 1024차원 벡터를 돌려준다. python-api 메모리 한도(512MiB) 안에 bge-m3(가중치 2.1GB)를 올릴 수 없어 따로 띄운다.

- 진입점: `app.entrypoints.embedding_api:app`, launchd `kr.pawbridge.embedding`([예시](../../../deploy/launchd/kr.pawbridge.embedding.plist.example))
- 듣는 곳: `127.0.0.1:18900`만. python-api는 egress 망에서 `http://host.docker.internal:18900`으로 닿는다(Docker Desktop).
- 장치: MPS. CPU로 자동 대체하지 않는다. 가중치는 오프라인으로만 읽는다(`HF_HUB_OFFLINE=1`).
- 실패 처리: 서비스가 죽거나 2초 안에 답하지 않으면 그 질문은 규칙 검색만 쓴다. python-api가 먼저 떠서 기동 때 닿지 못하면 30초마다 색인을 다시 만든다.

## 준비

가상환경은 실종검색 실행본과 따로 둔다.

```sh
<python3.12> -m venv /absolute/path/to/embedding-venv
/absolute/path/to/embedding-venv/bin/python -m pip install \
  -c deploy/macos/embedding.constraints.txt -r requirements-embedding.txt
/absolute/path/to/embedding-venv/bin/python -m pip check
```

소스는 `origin/main`에 들어간 커밋을 `git archive`로 새 폴더에 푼다. 가중치 폴더(`BAAI/bge-m3`의 sentence-transformers 형식, `modules.json` 포함)는 저장소 밖에 둔다.

## 띄우기와 확인

1. 예시 plist의 `@@PYTHON@@`(위 가상환경), `@@SOURCE@@`(풀어 둔 소스), `@@BGE_M3_DIR@@`, `@@RUNTIME@@`(로그 폴더의 부모)를 채워 `~/Library/LaunchAgents/kr.pawbridge.embedding.plist`로 둔다.
2. `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/kr.pawbridge.embedding.plist`
3. `curl -s http://127.0.0.1:18900/health`가 `{"status":"ok","model":...,"dimension":1024}`인지 본다.
4. compose의 python-ai-service에 `EMBEDDING_SERVICE_URL`을 넣고 python-ai-service만 다시 만든다. 로그에 `Health hybrid search ready`가 찍히는지 본다.

소스를 바꿀 때는 새 폴더에 풀고 plist의 `WorkingDirectory`를 바꾼 뒤 `launchctl kickstart -k gui/$(id -u)/kr.pawbridge.embedding`으로 다시 띄운다. python-api는 다시 만들 필요가 없다(질문 벡터는 그때그때 묻고, 해설 색인은 python-api가 기동 때 한 번 만든다).

## 끄기와 되돌리기

1. compose의 `EMBEDDING_SERVICE_URL`을 빈 값으로 두고 python-ai-service만 다시 만든다(규칙 검색만).
2. `launchctl bootout gui/$(id -u)/kr.pawbridge.embedding`

1만 해도 챗봇은 하이브리드를 쓰지 않는다. 2만 하면 python-api는 질문마다 연결 실패 뒤 규칙 검색으로 돌아간다(경고 로그가 남는다).
