# PawBridge 관측성 스택 (`pawbridge-obs`)

Mac mini 운영 호스트에서 `pawbridge-prod-*` 앱 스택과 **별도 compose 프로젝트**로 도는 Prometheus + Loki + Grafana Alloy + Grafana 번들이다.

## 1. 구성과 동작 원리

```
                 pawbridge-prod-private (external, internal)
 monolith:9464 <------------------------------+
                                              | scrape (/actuator/prometheus)
 +----------------- pawbridge-obs_internal (internal) ----------------+
 |  prometheus ---scrape---> loki / alloy / grafana                    |
 |  prometheus --alerts----> grafana (내장 Alertmanager) --> Slack     |
 |  alloy --push--> loki <--query-- grafana --query--> prometheus      |
 +---------------------------------------------------------------------+
   alloy: /var/run/docker.sock (ro) 에서 pawbridge-prod-* 로그를 tail
   grafana: 127.0.0.1:3000 게시 + pawbridge-prod-egress (Slack 호출)
```

| 서비스 | 이미지(태그) | 네트워크 | 메모리 | 보존/볼륨 |
|---|---|---|---|---|
| prometheus | `prom/prometheus:v3.5.0` | obs, prod-private | 256m | 15일 / `pawbridge-obs-prometheus` |
| loki | `grafana/loki:3.5.3` | obs | 256m | 14일 / `pawbridge-obs-loki` |
| alloy | `grafana/alloy:v1.10.0` | obs | 128m | positions / `pawbridge-obs-alloy` |
| grafana | `grafana/grafana:12.1.0` | obs, prod-egress | 256m | `pawbridge-obs-grafana` |

설계 결정과 이유:

- **네트워크를 하나 더 둔다 (`pawbridge-obs_internal`, internal).** 요청은 "기존 `pawbridge-prod-private` 에 붙는다"였지만, 4개 모두를 거기에 붙이면 앱 컨테이너(침해 가정)가 인증 없는 Loki push API와 Prometheus에 접근할 수 있다. 그래서 `prod-private` 에는 scrape 에 꼭 필요한 **Prometheus만** 붙이고 나머지는 전용 internal 네트워크에 둔다. (Prometheus 9090 은 여전히 `prod-private` 에서 보인다. 인증이 없으니 앱 컨테이너 침해를 가정하면 노출이다. 필요하면 후속으로 Prometheus 도 분리하고 별도 프록시를 둔다.)
- **Grafana 가 `pawbridge-prod-egress` 에 붙는 이유.** (1) Docker 는 internal 네트워크에만 붙은 컨테이너의 포트를 호스트에 게시하지 않는다. (2) Slack webhook 은 외부로 나가야 한다. Alloy/Loki/Prometheus 는 외부 접근이 필요 없으므로 egress 에 붙이지 않는다.
- **알림 경로: Prometheus 규칙 + Grafana 내장 Alertmanager.** Grafana-managed alert rule 은 규칙 하나가 쿼리/조건 파이프라인 YAML 수십 줄이다. 반면 Prometheus 규칙은 표준 형식이라 짧고 `promtool` 로 검증할 수 있으며, Prometheus 가 `/api/alertmanager/grafana` 로 알림을 보내면 Grafana 의 contact point / notification policy(프로비저닝됨)가 Slack 전송을 맡는다. 별도 Alertmanager 컨테이너가 필요 없다. 단점: Prometheus 가 Grafana admin 계정(basic auth)으로 인증한다 (후속 과제: service account 토큰).
- **Slack webhook 은 파일로 주입.** 프로비저닝 YAML 의 `url: $__file{/run/secrets/slack-webhook}` 를 Grafana 가 읽을 때 파일 내용으로 치환한다. 환경변수 값이 `docker inspect` 에 노출되는 것을 피한다.
- **로그 레이블은 `container` / `service` / `compose_project` / `job` 만.** 요청·사용자 ID 같은 값은 레이블로 만들지 않는다 (Loki 카디널리티 폭발 방지). 필터는 `|~` 로 한다.

## 2. 사전 요건

1. Docker Desktop(macOS) 과 `docker compose` v2.
2. 외부 네트워크가 이미 있어야 한다 (`docker network ls | grep pawbridge-prod`): `pawbridge-prod-private`, `pawbridge-prod-egress`. 없으면 `up` 이 실패한다 (새로 만들지 않는다).
3. monolith 쪽 준비 (별도 작업 중인 항목):
   - management 포트 `9464` 에서 `/actuator/prometheus` 노출, 컨테이너가 `pawbridge-prod-private` 에서 `monolith` 이름으로 해석될 것 (compose 서비스명 또는 네트워크 alias).
   - p95 규칙/패널용: `management.metrics.distribution.percentiles-histogram.http.server.requests=true`
   - 결제 보상 알림용: 게이지 `pawbridge_payment_compensation_open`
4. 시크릿 파일 (값은 이 저장소/환경파일에 두지 않는다):

   ```bash
   mkdir -p ~/.pawbridge-obs/secrets && chmod 700 ~/.pawbridge-obs ~/.pawbridge-obs/secrets
   umask 077
   openssl rand -base64 24 | tr -d '\n' > ~/.pawbridge-obs/secrets/grafana-admin-password
   printf '%s' 'https://hooks.slack.com/services/...' > ~/.pawbridge-obs/secrets/slack-webhook
   chmod 644 ~/.pawbridge-obs/secrets/*   # 컨테이너 비루트(prometheus=nobody, grafana=472)가 읽어야 한다. 디렉터리 700 이 보호한다.
   ```
   시크릿 경로가 `~/Documents` 아래이면 macOS 권한(TCC) 때문에 Docker Desktop 이 바인드 마운트를 못 읽을 수 있다 — 그 경우 Docker 에 폴더 접근을 허용하거나 경로를 옮긴다. 파일 권한 때문에 컨테이너에서 permission denied 가 나면 6절 문제 해결 참고.
5. `cp .env.example .env && chmod 600 .env` 후 `OBS_SECRETS`(절대 경로), `DOCKER_SOCK` 수정.
   - Docker Desktop 소켓: `ls -l /var/run/docker.sock ~/.docker/run/docker.sock` 로 확인. 기본 `/var/run/docker.sock` 이 없으면 `~/.docker/run/docker.sock` 의 **절대 경로**를 쓴다.

## 3. 이미지 고정

`compose.yaml` 의 이미지는 태그와 digest 로 고정돼 있다. 버전을 올릴 때는 태그와 digest 를 함께 바꾸고 5절 규칙·프로비저닝 호환성을 다시 확인한다.

## 4. 설정 검증과 기동

```bash
cd deploy/observability
# (1) compose 문법/변수 확인. 외부 네트워크가 있어야 하고 OBS_SECRETS 파일 경로가 해석된다. 이미지는 pull 하지 않는다.
docker compose -p pawbridge-obs --env-file .env config -q && echo OK

# (2) 규칙/설정 검증 (이미지를 pull 한 뒤, 컨테이너는 --rm 일회성)
docker run --rm --entrypoint promtool -v "$PWD/prometheus:/etc/prometheus:ro" prom/prometheus:v3.5.0 \
  check config /etc/prometheus/prometheus.yml
docker run --rm -v "$PWD/loki/loki.yaml:/etc/loki/loki.yaml:ro" grafana/loki:3.5.3 \
  -config.file=/etc/loki/loki.yaml -verify-config
docker run --rm -v "$PWD/alloy:/etc/alloy:ro" grafana/alloy:v1.10.0 validate /etc/alloy/config.alloy   # validate 서브커맨드가 없는 버전이면 fmt 로 문법만 확인

# (3) 기동 / 상태
docker compose -p pawbridge-obs --env-file .env up -d
docker compose -p pawbridge-obs ps

# 중지(데이터 보존) / 재시작
docker compose -p pawbridge-obs stop
docker compose -p pawbridge-obs start
docker compose -p pawbridge-obs down        # 컨테이너/내부 네트워크 제거, 볼륨은 유지
# 주의: down -v 는 pawbridge-obs-* 볼륨(메트릭/로그/Grafana DB)을 삭제한다.

# 설정 변경 반영
docker compose -p pawbridge-obs kill -s HUP prometheus     # prometheus.yml / rules 변경 (lifecycle API 를 켜지 않았다)
docker compose -p pawbridge-obs restart loki alloy         # loki.yaml / config.alloy 변경
# Grafana provisioning(datasource/alerting)은 재시작 시 반영, 대시보드 JSON 은 60초마다 자동 반영
```

Grafana 접속: 맥미니 로컬에서 `http://127.0.0.1:3000`, 원격에서는 `ssh -L 3000:127.0.0.1:3000 <macmini>` 후 같은 주소. 계정 `admin` / `grafana-admin-password` 파일 내용.

## 5. 구성요소별 확인

Prometheus/Loki/Alloy 는 호스트로 포트를 열지 않았으므로 컨테이너 안에서 조회한다.

| 대상 | 확인 방법 | 기대 결과 |
|---|---|---|
| 전체 | `docker compose -p pawbridge-obs ps` | prometheus/loki/grafana `healthy` (alloy 는 healthcheck 없음, `running`) |
| Prometheus targets | `docker exec pawbridge-obs-prometheus wget -qO- 'http://127.0.0.1:9090/api/v1/targets' \| grep -o '"health":"[a-z]*"' \| sort \| uniq -c` | 5개 job 모두 `up` (monolith 는 앱 준비 전이면 `down`) |
| 규칙 로드 | `docker exec pawbridge-obs-prometheus wget -qO- http://127.0.0.1:9090/api/v1/rules \| grep -o '"name":"[A-Za-z0-9]*"'` | urgent 4개 + warning 7개 이름. Loki 규칙 2개(`LogbackErrors`, `ApmsCollectionConsecutiveFailures`)는 Grafana 에만 있다 |
| Alertmanager 연결 | `docker exec pawbridge-obs-prometheus wget -qO- http://127.0.0.1:9090/api/v1/alertmanagers` | `activeAlertmanagers` 에 `http://grafana:3000/api/alertmanager/grafana/api/v2/alerts` |
| 메트릭 존재 | Grafana > Explore > Prometheus: `http_server_requests_seconds_bucket`, `hikaricp_connections_max`, `jvm_memory_max_bytes`, `logback_events_total`, `pawbridge_payment_compensation_open` | 각각 시계열이 있어야 해당 규칙/패널이 동작한다. 없는 메트릭의 규칙은 **조용히** 평가되지 않는다 |
| Loki | `docker exec pawbridge-obs-loki wget -qO- http://127.0.0.1:3100/ready` | `ready` |
| 로그 수집 | Grafana > Explore > Loki: `{container=~"pawbridge-prod-.+"}` | `container` 레이블이 컨테이너 이름. `pawbridge-obs-*` 는 없어야 한다 |
| Alloy | Prometheus `up{job="alloy"}`; 문제 시 `docker logs pawbridge-obs-alloy` | up=1, docker.sock 권한 오류 없음 |
| Grafana | 로그인 후 Connections > Data sources 에 Prometheus/Loki, Dashboards > PawBridge > "PawBridge Monolith" | 두 데이터소스 "Test" 성공 |
| 익명 접근 차단 | 시크릿 창에서 `http://127.0.0.1:3000/` | 로그인 화면 |
| Slack 연결 | Alerting > Contact points > `pawbridge-slack` > Test | Slack 채널에 테스트 메시지 |
| 알림 종단 간 | `prometheus/rules/zz-test-alert.yml.example` 을 `.yml` 로 복사 > HUP > 1~2분 내 Slack 수신 > 파일 삭제 + HUP | `PipelineTest` 수신 후 해소 메시지 |
| 보존 | `docker inspect pawbridge-obs-prometheus --format '{{.Args}}'` 에서 `retention.time=15d`; Loki 는 `loki.yaml` 의 `retention_period: 336h` + compactor `retention_enabled` | 오래된 데이터 삭제는 하루 이상 지나야 눈에 보인다 |
| 포트 | `docker ps --format '{{.Names}} {{.Ports}}' \| grep pawbridge-obs` | `pawbridge-obs-grafana` 만 `127.0.0.1:3000->3000/tcp` |

### 알림 규칙 요약

| 규칙 | 조건 | 심각도 |
|---|---|---|
| ServiceDown | `up{job="pawbridge-monolith"} == 0` 2m | urgent |
| PaymentCompensationOpen | `pawbridge_payment_compensation_open > 0` 1m | urgent |
| DatabaseConnectionTimeouts | Hikari timeout 증가 2m 지속 (DB 다운의 간접 신호) | urgent |
| High5xxRatio | 5xx 비율 > 1% 5m | warning |
| HighP95Latency | p95 > 1s 10m (임계값/기간은 가정치) | warning |
| HikariPoolPending / HikariPoolSaturated | pending > 0 5m / active >= max 2m | warning |
| JvmHeapHigh | heap 사용률 > 85% 10m | warning |
| LogbackErrors | 5분간 ERROR 로그 1건 이상, APMS 수집 실패 줄은 뺀다 (Loki, `grafana/provisioning/alerting/rules-loki.yaml`) | warning |
| ApmsCollectionConsecutiveFailures | 35분 안에 APMS 수집 실패 2번, 즉 30분 주기 연속 2회 (Loki, 같은 파일) | warning |
| MonolithScrapeSlow / ObservabilityComponentDown | scrape 5초 초과 5m / loki·alloy·grafana up==0 5m | warning |

라우팅: 전부 Slack 하나(`pawbridge-slack`). urgent 는 1시간마다, warning 은 12시간마다 재알림.

## 6. 문제 해결

- **Slack URL 이 치환되지 않음 (`unsupported protocol scheme` 등):** Grafana 로그에서 provisioning 오류 확인. 대안: `compose.yaml` 의 grafana `environment` 에 `SLACK_WEBHOOK_URL: ...` 를 넣고 `contact-points.yaml` 을 `url: $__env{SLACK_WEBHOOK_URL}` 로 바꾼다 (단, 값이 `docker inspect` 에 보인다).
- **`/run/secrets/...` permission denied:** macOS 바인드 마운트에서 파일 소유자 매핑 때문에 비루트가 읽지 못하는 경우. 시크릿 파일을 `644` 로 하고 상위 디렉터리를 `700` 으로 유지한다.
- **Grafana admin 비밀번호를 바꿨는데 로그인이 옛 값:** `GF_SECURITY_ADMIN_PASSWORD__FILE` 은 최초 DB 생성 시에만 적용된다. 이후 변경은 `docker exec -it pawbridge-obs-grafana grafana cli admin reset-admin-password '<새 값>'` 로 하고 파일도 같은 값으로 갱신해야 Prometheus 의 알림 전송 인증이 유지된다.
- **Alloy 가 `permission denied` / `Cannot connect to the Docker daemon`:** `DOCKER_SOCK` 호스트 경로를 확인한다 (`ls -l`). 심볼릭 링크 대상이 아닌 실제 소켓 경로를 절대 경로로 지정.
- **Prometheus 에서 Alertmanager 401/404:** Grafana 가 아직 기동 중이거나 admin 비밀번호가 파일과 다른 경우. `api/v1/alertmanagers` 의 `droppedAlertmanagers` 와 `docker logs pawbridge-obs-prometheus` 확인.
- **Loki/Alloy 가 OOM 으로 재시작:** `docker inspect --format '{{.State.OOMKilled}}' <컨테이너>` 확인. 256m/128m 은 소규모 로그 기준이다. 로그량이 늘면 한도 상향 또는 `limits_config` 조정.

## 7. 보안 주의

- **Docker 소켓 `:ro` 는 읽기 전용 보장이 아니다.** 소켓 파일을 읽기 전용으로 마운트해도 그 위의 Docker API 호출(컨테이너 생성/삭제 등)은 막히지 않는다. Alloy 컨테이너는 사실상 호스트 Docker root 권한과 같다. 이미지는 고정(digest)하고, 설정 파일은 `:ro` 로만 마운트했으며, 가능하면 후속으로 docker-socket-proxy(읽기 전용 엔드포인트만 허용)를 둔다.
- Loki 에는 앱 로그가 14일 보관된다. 로그에 개인정보/토큰이 찍히지 않는지 앱 쪽에서 확인한다.
- 모든 컨테이너에 `no-new-privileges` 를 적용했다. Grafana 는 익명 접근/가입/분석/업데이트 확인/플러그인 자동 설치를 모두 껐다.

## 8. 감지하지 못하는 것

- **postgres / redis / cloudflared 컨테이너 다운은 직접 감지하지 못한다.** 추가 이미지 없이 Prometheus 가 볼 수 있는 메트릭이 없고, Loki/Alloy 로는 "로그가 안 나온다"가 "컨테이너가 죽었다"를 의미하지 않는다. 현재는 monolith `up` + DB 커넥션 timeout(urgent)으로 DB 장애를 간접 감지한다. Redis 는 간접 신호도 없다. monolith 의 `/actuator/health` 의 db/redis 컴포넌트는 기본적으로 Prometheus 메트릭이 아니다.
- ServiceDown 은 management 포트(9464)만 본다. 공개 경로(cloudflared > 8080)가 막혀도 감지하지 못한다.
- Prometheus 가 죽거나 Grafana 가 죽으면 알림도 못 간다 (감시자 감시 없음).
- 호스트(맥미니) 디스크/CPU/메모리, Docker VM 상태는 보지 않는다.
- Loki 의 로그 기반 알림(ruler)은 쓰지 않았다. ERROR 알림은 `logback_events_total` 로 대체했다.
