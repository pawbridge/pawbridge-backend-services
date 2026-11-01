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
USER 10001:10001
EXPOSE 8000
CMD ["uvicorn", "app.entrypoints.chatbot_api:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--limit-concurrency", "4", "--backlog", "8", "--no-access-log"]
