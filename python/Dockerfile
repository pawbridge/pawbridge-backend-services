# Production python-ai-service (chatbot). Lost-search inference runs in the Mac native service, not here.
# Same recipe as the running image (formerly pawbridge-infra environments/mac/images/Dockerfile.python).
FROM docker.io/library/python@sha256:9534e5a8e315485d4061ed659af0fd78a284c015f9b73661b41d6bab25604534
ARG GIT_SHA=unknown
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
LABEL org.opencontainers.image.source="https://github.com/pawbridge/pawbridge-backend-services" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      com.pawbridge.purpose="python-ai-service"
# constraints.txt: every installed package, pinned to the image running on 2026-11-01 (pip freeze).
# packaging comes from the digest-pinned base image.
COPY requirements.txt constraints.txt /app/
RUN pip install --no-cache-dir -c constraints.txt -r requirements.txt && pip check
COPY app /app/app
# 건강 해설 파일이 깨졌으면(JSON 형식, 금지 표현, 출처 참조, id와 파일 이름) 이미지를 만들지 않는다.
# HEALTH_RAG_ENABLED가 꺼져 있으면 기동 때도 읽지 않아, 켜는 순간에야 챗봇 전체가 못 뜨는 일을 막는다.
RUN python -c "from app.chatbot.health.knowledge import load_entries; print('health entries:', len(load_entries()))"
USER 10001:10001
EXPOSE 8000
# --limit-concurrency는 처리 중인 요청이 아니라 열린 연결 수로 센다(백엔드가 재사용하려고 열어 둔 연결 포함).
# 넘으면 기다리지 않고 바로 503이다. 4일 때 실제 동시 처리는 3개였다(동시 5개면 5개 모두 503).
# 챗봇은 모델 응답을 기다리는 시간이 대부분이라 16으로 둔다. 비용은 백엔드의 사용자별 횟수 제한이 막는다.
CMD ["uvicorn", "app.entrypoints.chatbot_api:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--limit-concurrency", "16", "--backlog", "8", "--no-access-log"]
