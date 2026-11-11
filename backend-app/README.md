# PawBridge 업무 통합 실행기

Java 21·Gradle 8.14.3·Spring Boot 3.4.11의 독립 멀티프로젝트다. F2의 게시글 단건 GET·작성자 닉네임 조회, F3의 인증된 일반 게시글 작성, F4의 본인 게시글 삭제, F5의 본인 게시글 수정, F6의 공개 게시글 목록, F7의 공개 게시글 검색, F8의 공개 댓글 목록, F9의 인증된 댓글 작성, F10의 본인 댓글 삭제, F11의 본인 댓글 수정, F12의 인증된 본인 정보 조회, F13의 본인 닉네임 수정, F14의 본인 찜 여부 조회, F15의 공개 홈 영상 조회, F16의 공개 오늘 방문자 합계 조회, F17의 인증된 쪽지 수신자 확인 조회, F18의 표시 작성자 중 본인 차단 여부 조회, F19의 관리자 전체 회원 수 조회, F20의 본인 차단 목록 조회, F21의 본인 쪽지 알림 조회, F22의 본인 쪽지 단건 조회를 편입하는 후보이며 운영 Gateway를 이 실행기로 연결하거나 기존 서비스를 종료하지 않는다.

## F21 본인 쪽지 알림 조회

정확한 `GET /api/v1/notes/notifications?cursor=...`만 추가한다. 현재 회원 active → Clock 시각1회 → 받은함21행 → 표시20행의 null 제외·첫 등장 순서 sender 일괄 조회 → 다음 cursor → 전체 unreadCount 순서를 유지한다. 같은 요청 시각을 쓰지만 여러 조회의 원자 snapshot을 보장하지 않으며 단계별로 기존 RO tx/연결을 반환한다. User 단건·batch와 Clock·단일 pool을 재사용한다.

정렬은 mailbox.created_at/UUID 내림차순이고 응답 createdAt은 note.created_at이다. 만료는 note.expires_at > now이며, 본인 INBOX 커서 anchor에는 만료 조건을 붙이지 않는다. 다른 회원·SENT·없는 cursor는 빈 content와 전체 unreadCount를 반환한다. 결과3필드·알림7필드와 nullable actorId·탈퇴 표시·원문 공백 nickname·Instant 미세초·read·href를 보존하고 본문은 읽거나 반환하지 않는다. 회원/저장소/일반503과 scoped no-store를 구분하며 발송·읽음·SSE·예약·원본 실행기 종료는 추가하지 않는다.

원본 annotated optional UUID의 실제 MVC12 관측을 먼저 수행해 기대값을 고정했다. missing/empty/space/empty-repeated는 null로200, 축약 UUID도 정규화되어200, 반복 값은 첫 값을 사용하고 CSV·잘못된 값·범위 초과 component는 서비스 호출 전400이다. 관측용 임시 클래스는 게시하지 않는다.

## Gateway 기능 이전

Gateway 없이도 운영할 수 있도록 Gateway가 하던 네 가지를 이 실행기로 옮겼다. 원본은 삭제된 구 api-gateway의 `application.yml`과 `LostSearchLimitsGatewayFilterFactory`였다.

- **CORS**: `GatewayCors`가 Gateway `globalcors`와 같은 값(origin 패턴 `localhost/127.0.0.1:3000·5173`, `pawbridge.kr`, `www.pawbridge.kr`, `*.pawbridge.kr`, 메서드 GET/POST/PUT/DELETE/PATCH/OPTIONS, 허용 헤더 Authorization·Content-Type·Accept·x-user-id, 노출 헤더 Retry-After·Authorization, 자격 증명 허용)을 만든다. `ReadSecurityConfiguration`(주 체인), `LocalLoginConfiguration`(로컬 로그인 체인), `GoogleOAuthConfiguration`(Google OAuth 체인)이 모두 이 필터를 보안 체인의 CORS 위치에 넣어 preflight가 인증 전에 응답된다. 로그인·OAuth는 주 체인과 별도의 `SecurityFilterChain`이라 각 체인에 따로 넣어야 하며, 세 체인은 `GatewayCors.ORIGIN_PATTERNS_VALUE`로 같은 속성과 기본값을 읽는다. origin은 `pawbridge.cors.allowed-origin-patterns`(쉼표 구분)로 덮어쓴다. 차이: 허용되지 않은 origin의 preflight가 아닌 실제 요청은 403 대신 CORS 헤더 없이 정상 처리한다(Origin을 보내는 서버 간 호출의 응답이 바뀌지 않게 하며, 브라우저는 헤더가 없으면 응답을 막는다). preflight는 원본처럼 거절한다.
- **유실동물 검색 제한**: `pawbridge.animal-lost-search.enabled=true`일 때만 `POST /api/v1/animals/lost-candidates`에 `LostSearchLimitsFilter`(보안 체인 뒤)를 건다. 속성과 기본값은 원본 그대로 `lost-search.max-request-bytes=6291456`, `max-concurrent=2`, `requests-per-minute=30`, `timeout-seconds=50`. 응답은 413(크기), 415(multipart 아님), 503+`Retry-After: 3`(동시 초과), 429+`Retry-After: 60`(분당 초과), 400(빈 본문), 504이고 본문은 원본과 같은 `{"message":"..."}`다. 하위 호출(Python)은 `PythonLostSearchClient`가 서버 설정의 `X-Internal-Api-Key`만 보내므로 클라이언트의 `Authorization`·`X-User-Id`·`X-Internal-Api-Key`는 전달되지 않고, 필터도 핸들러에 보이는 요청에서 이 헤더와 `Transfer-Encoding`을 숨긴다. **원본과 다른 점**: (1) 서블릿 컨테이너가 multipart를 직접 파싱하므로 본문을 필터에서 버퍼링하지 않는다. 크기는 선언된 `Content-Length`로 판단하고, `Content-Length`가 없는 chunked 업로드는 411로 거절한다(리버스 프록시가 보통 길이를 채운다). 선언 길이는 컨테이너가 강제한다. 적용 여부는 디코딩된 경로(`UrlPathHelper`)로 판단해 `lost-%63andidates` 같은 인코딩 표기로 제한을 우회할 수 없고(실제 Tomcat 시험으로 확인), 동시성 permit은 본문(multipart)을 모두 받은 뒤에 잡아 느린 업로드가 permit을 오래 쥐지 못한다. 별도의 총 업로드 시간 제한은 없다. (2) 전체 50초는 요청을 중단시켜 강제하지 않는다. 하위 Python 호출의 연결 3초·읽기 45초 타임아웃(초과 시 서비스가 503)이 실제 상한이고, 타임아웃이 핸들러 밖으로 새면 504를 낸다. `timeout-seconds`는 값 검증만 한다. AI 큐 도입은 이 범위 밖이다.
- **헬스체크**: `spring-boot-starter-actuator`를 추가하고 `management.endpoints.web.exposure.include=health,info,prometheus`와 프로브 활성(`/actuator/health/liveness`·`/readiness`)으로 노출을 제한한다(관리 포트를 분리한 뒤에는 관리 포트에서만 제공된다). 보안 설정은 `GET /actuator/health`와 `/actuator/health/**`만 permitAll이며 `info`를 포함한 나머지는 기존 `denyAll`에 걸린다. `show-details`는 원본의 `always` 대신 `never`다(인증 없이 DB 등 세부 상태가 보이지 않게). Prometheus 지표는 별도 관리 포트에서만 노출한다(아래 "내부 지표 엔드포인트"). 쿠버네티스 프로브는 세부 지표가 섞이지 않는 `/actuator/health/liveness`·`/readiness`를 쓰고, 전체 `/actuator/health`에서는 메일 지표를 끈다(`management.health.mail.enabled=false`).
- **구 경로**: 프론트가 모든 호출을 `/api/v1`로 바꿔(프론트 #252·#253) Gateway `RewritePath`를 옮겼던 임시 `LegacyApiPathFilter`와 `pawbridge.legacy-api-paths.enabled`를 제거했다. `/api/v1` 밖의 `/api/**`(예: `/api/animals`, `/api/users/internal/**`)는 컨트롤러가 없고 보안 규칙의 `denyAll`이 403으로 거부한다.

원본 Gateway의 경로별 특수 처리 대조: 방문자 경로의 Authorization 제거는 이 실행기에서 방문자 경로가 토큰을 읽지 않으므로 동등하고, SSE 무제한 응답 시간은 `PrivateNoteStreamRegistry`의 emitter가 토큰 수명으로 직접 관리하므로 Gateway 타임아웃에 해당하는 것이 없으며, WebSocket은 `/api/v1/chats/socket`을 보안이 permitAll하고 `pawbridge.chat.allowed-origins`로 origin을 검사하며(구 경로 `/api/chats/socket`으로의 업그레이드는 시험하지 않았다), OAuth 경로는 `GoogleOAuthConfiguration`의 별도 체인이 인증 없이 처리한다. 원본이 외부에서 막던 `/internal/**`는 서비스 간 호출(`/internal/animals/lost-gallery` 등, `X-Internal-Api-Key` 검증)이 같은 포트를 쓰므로 막지 않는다. 외부 노출 차단은 인그레스 쪽 책임이다.

## 내부 지표 엔드포인트

사내(호스트 안) Prometheus가 긁어 가는 지표를 **별도 관리 포트**에서만 제공한다. 공개 포트(`PAWBRIDGE_SERVER_PORT`)에는 Actuator가 전혀 올라가지 않는다.

| 항목 | 값 |
|---|---|
| 관리 포트 | `PAWBRIDGE_MANAGEMENT_PORT`, 기본 `9464` |
| 바인드 주소 | `PAWBRIDGE_MANAGEMENT_ADDRESS`, 기본 `127.0.0.1`. 컨테이너에서만 `0.0.0.0`으로 지정한다 |
| 수집 경로 | `GET /actuator/prometheus` |
| 프로브 | `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` (관리 포트에서 제공, 세부 정보 없음) |

- **호스트에 게시하지 않는다.** compose에서 관리 포트를 `ports:`에 적지 않는다(`9464:9464`, `0.0.0.0:9464` 모두 금지). 같은 docker 네트워크의 Prometheus만 서비스 이름으로 접근한다. 인증이 없으므로 네트워크 격리가 유일한 보호다.
- **보안.** 관리 포트로 들어온 요청은 전용 보안 체인(`ManagementSecurityConfiguration`)이 가장 먼저 받아 `GET /actuator/prometheus`와 `GET /actuator/health`·`/actuator/health/**`만 인증 없이 허용하고 나머지는 모두 403으로 거부한다. 공개 조회 경로, 로컬 로그인, Google OAuth 체인이 허용하는 경로도 관리 포트에서는 열리지 않는다. 공개 포트의 체인은 바뀌지 않았고 `/actuator/prometheus`는 403이다.
- **노출 엔드포인트**는 `health`, `info`, `prometheus`뿐이며 `info`는 보안 체인이 막는다. 관리 포트를 따로 두면 `/actuator/health`는 공개 포트에서 더 이상 제공되지 않는다(404). 쿠버네티스/호스트 프로브는 관리 포트를 보도록 바꾼다.
- **지표.** JVM·프로세스·시스템(`jvm_*`, `process_*`, `system_*`), HikariCP 풀 `pawbridge-db`(`hikaricp_connections*`; `HikariDataSource` 빈이 하나라 Spring Boot가 자동으로 연결한다), HTTP 서버 요청(`http_server_requests_seconds*`, 요청 템플릿 기준 `uri` 태그와 50ms~5s 7개 버킷), Tomcat 스레드(`tomcat_threads_*`, `server.tomcat.mbeanregistry.enabled=true`), Logback 레벨별 건수(`logback_events_total`)가 있다. 모든 지표에 `application` 태그가 붙는다. Spring Security 필터 관측은 시계열이 너무 많아 끈다.
- **결제 보상 기록.** `pawbridge.payments.enabled=true`일 때만 게이지 `pawbridge_payment_compensation_open`을 등록한다. `pawbridge_payment.payment_compensation_records`에서 `resolved_at IS NULL`인 행 수이고, 읽기 전용 트랜잭션 템플릿으로 최대 60초에 한 번만 조회해 캐시한다. 조회가 실패하면 `NaN`을 60초간 보고한다(오래된 숫자로 장애를 가리지 않는다). 앱 계정의 `payment_compensation_records` SELECT 권한이 필요하다. 0보다 크면 수동 취소·정산 대상이 있다는 뜻이다.
- **다른 포트 설정.** `PAWBRIDGE_MANAGEMENT_PORT`를 공개 포트와 같게 하면 관리 포트가 분리되지 않아 `/actuator/health`만 공개 포트에서 열리고 `/actuator/prometheus`는 403이라 수집할 수 없다. 운영에서는 분리된 포트를 쓴다.

compose 예(앱과 Prometheus가 같은 네트워크, 관리 포트는 게시하지 않음):

```yaml
services:
  backend-app:
    image: pawbridge/backend-app:<tag>
    environment:
      PAWBRIDGE_SERVER_PORT: "8080"
      PAWBRIDGE_MANAGEMENT_PORT: "9464"
      PAWBRIDGE_MANAGEMENT_ADDRESS: "0.0.0.0"   # 컨테이너 안에서만; 호스트로는 게시하지 않는다
    ports:
      - "127.0.0.1:8080:8080"                   # 공개 포트만. 9464는 절대 적지 않는다
    networks: [observability]
  prometheus:
    image: prom/prometheus:<tag>
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
    networks: [observability]
networks:
  observability: {}
```

```yaml
# prometheus.yml
scrape_configs:
  - job_name: pawbridge-backend-app
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets: ["backend-app:9464"]
```

## 업무 경계와 호출

| 모듈 | 책임 | 직접 프로젝트 의존성 |
|---|---|---|
| boot | MVC·보안·DB·업무 구현 조립과 실행 JAR | user, community |
| user | 공개 닉네임/연락 회원 조회 계약·내부 본인 정보 조회·본인 닉네임 수정·찜 여부 조회와 JDBC·HTTP 구현 | 없음 |
| community | 게시글 단건·목록·검색·작성·수정·삭제·댓글 목록·작성·수정·삭제·미디어·검색 projection·공개 홈 영상·오늘 방문자 합계·쪽지 수신자·표시 작성자 차단 상태·차단 목록·쪽지 알림·단건 읽기 정책·REST 응답 | user |

```text
boot -> community.internal.post.web -> community.internal.post.application
                                      -> community.internal.post.infrastructure
                                      -> user.api -> user.internal.nickname.infrastructure
```

`api`는 업무 간 Java 계약이다. HTTP는 `internal.<기능>.web`, DB/JSON 변환은 infrastructure, 조회 조합은 application이 담당한다. 읽기 projection에 빈 domain 계층이나 회원 전체 aggregate를 만들지 않는다. F3의 domain은 일반 게시판 작성 가능 규칙과 저장 입력만 담당한다.

Community는 User의 공개 API만 참조한다. User 부재·명시된 조회 실패는 `사용자{id}`로 표시하고, 빈 HTTP 응답이 null로 decode되던 동작은 빈 저장 닉네임의 표시 호환에 반영한다. 공백은 그대로 둔다. 게시글 없음/soft-delete는 404이며 게시글 DB·JSON 오류는 닉네임 fallback로 숨기지 않는다.

공개 게시글 GET은 `/api/v1/posts/read/{postId}` 단건, `/api/v1/posts/read` 목록, `/api/v1/posts/search?keyword=...` 검색이다. 기존 envelope와 data 9필드, 이미지 null/순서/중복과 LocalDateTime 의미를 보존한다. 공개 댓글 GET `/api/v1/comments/posts/read/{postId}`는 아래 F8의 7필드 배열 계약을 따른다. 기존 malformed ID의 generic 500 동작은 유지하며 400 개선을 섞지 않는다. `/api/posts/...` alias rewrite는 기존 Gateway 책임이다. 작성자 닉네임 조회는 업무 간 Java 계약으로 연결한다. 본인 닉네임 수정 HTTP는 아래 F13의 정확한 PUT 경로만 제공하며 로그인·신규 토큰 발급 API는 없다. F3는 같은 기존 경로 `POST /api/v1/posts`의 multipart 작성만 추가한다.

F17은 `GET /api/v1/notes/recipients/{member}`만 추가한다. 서명 JWT의 Long principal로 요청자를 확인한 뒤 자기 자신→양방향 차단→상대 active 순서로 검사하고, User 소유의 `ContactMemberQuery.get(long)`가 제공하는 원문 닉네임·active/deletionPending을 사용한다. 신규 reader 권한은 `contact_deletions.user_id`와 `private_note_blocks.member_id/blocked_id` 세 열뿐이며 본문/메일함/연락처 PII·member/시각 열·writer 권한을 추가하지 않는다. User 조회→차단 조회→상대 조회마다 기존 짧은 RO tx/연결을 반환하며 외곽 TX·Feign·새 pool·event·SSE·탈퇴/차단 쓰기·예약을 편입하지 않는다. 성공은 정확한 4필드, 서비스/바인딩 오류는 message-only와 no-store이며 User 조회 불가503과 차단 저장소503을 구분한다. 인증 오류의 기존 envelope는 유지한다. 이는 후보 코드이며 운영 라우팅이나 원본 서비스 종료를 뜻하지 않는다.

F18은 `GET /api/v1/notes/blocks/status?memberIds=...`만 추가한다. 원래 입력 목록의 1~100개 양수 검증→현재 회원 active→중복 제거→본인이 차단한 outgoing ID 오름차순 조회를 보존한다. F17 User API·세 표·기존 reader 권한을 재사용하며 target User 조회·새 DDL/ACL·writer·page·추가 필드·외곽 TX를 만들지 않는다. 성공 data는 `memberIds` 한 필드이고 reverse-only/미일치는 제외한다. 누락400은 공통 envelope, 변환/서비스 오류는 message-only이며 no-store와 쿠키 없음은 해당 GET에 한정한다. 빈 query 값의 service400과 CSV+반복 혼합의 변환400은 실제 원본 MVC 관측에 맞춘다. F17 수신자 조회와 미편입 notes/chats·쓰기 경로 기본 거부를 유지하며 운영 라우팅은 변경하지 않는다.

F17과 F18의 새 RO TX에서 연결 생성·시스템 완료·deadline 실패는 구체적인 CannotCreateTransactionException/TransactionSystemException/TransactionTimedOutException만 저장소503으로 분류한다. User typed 실패는 회원503, transaction usage와 그 외 Runtime은 일반 쪽지503을 유지한다. 원본 autocommit의 DAO 연결 실패가 후보의 TX 예외로 감싸지는 기술적 차이에 대한 명시적 적응이다.

## DB·실행 설정

### DB 커넥션 풀 (단일 풀)

모놀리스는 HikariCP 풀 하나(`pawbridge-db`)만 만든다. 예전 읽기 풀(`dataSource`)·쓰기 풀(`writeDataSource`)·위시리스트 전용 풀(`storeWishlistWriteDataSource`)은 같은 인스턴스를 가리키는 bean 이름(별칭)으로만 남아 있다. 모든 JdbcTemplate·JdbcTransactionManager·JPA EntityManagerFactory/JpaTransactionManager가 이 풀 하나를 쓴다.

- 풀 설정: 최대 `PAWBRIDGE_DB_POOL_MAX_SIZE`(기본 10, 1~50), 최소 유휴 2(최대가 그보다 작으면 최대값), 연결 대기 5초, 검증 1초, 시작 시 DB 미연결 허용(initializationFailTimeout -1), 풀 readOnly 없음.
- 계정: `PAWBRIDGE_DB_USER`/`PAWBRIDGE_DB_PASSWORD` 하나로 읽기·쓰기를 모두 한다. 운영에서는 앱 계정 하나에 다섯 schema(`pawbridge_user`, `pawbridge_community`, `pawbridge_animal`, `pawbridge_store`, `pawbridge_payment`)의 필요한 권한을 준다. `F3_DB_WRITER_USER/PASSWORD`, `STORE_WISHLIST_DB_WRITER_USER/PASSWORD`, `pawbridge.database.writer.maximum-pool-size`는 더 이상 읽지 않는다(전달돼도 무시). 환경 변수 이름은 `PAWBRIDGE_*`로 바뀌었고 옛 이름(`F2_*`, `F3_JWT_SECRET`, `F3_STORAGE_*`)은 읽지 않는다. 운영 배포 전이라 호환 별칭을 두지 않았으므로 배포 설정에는 새 이름만 넣는다.

| 옛 이름 | 새 이름 |
|---|---|
| F2_JDBC_URL | PAWBRIDGE_DB_URL |
| F2_DB_USER | PAWBRIDGE_DB_USER |
| F2_DB_PASSWORD | PAWBRIDGE_DB_PASSWORD |
| F2_POOL_MAX_SIZE | PAWBRIDGE_DB_POOL_MAX_SIZE |
| F2_PORT | PAWBRIDGE_SERVER_PORT |
| F3_JWT_SECRET | PAWBRIDGE_JWT_SECRET |
| F3_STORAGE_ENDPOINT / REGION / BUCKET / PUBLIC_BASE_URL / OBJECT_PREFIX / ACCESS_KEY_ID / SECRET_ACCESS_KEY | PAWBRIDGE_STORAGE_ENDPOINT / REGION / BUCKET / PUBLIC_BASE_URL / OBJECT_PREFIX / ACCESS_KEY_ID / SECRET_ACCESS_KEY |

- 읽기 보호: 풀이 아니라 트랜잭션 단위로 한다. 읽기 템플릿(`readOnlyTransaction`·`searchReadOnlyTransaction` 등)은 readOnly이고 `transactionManager`는 `enforceReadOnly`라서 PostgreSQL에 `SET TRANSACTION READ ONLY`를 보낸다. 읽기 템플릿 timeout 3초/5초, `writeTransaction` 10초 등 기존 값은 그대로다.
- 중첩 트랜잭션: 읽기 TM과 쓰기 TM이 같은 DataSource를 쓰므로 쓰기 트랜잭션 안의 REQUIRED 읽기 템플릿은 같은 연결에 참여한다(readOnly·timeout은 바깥 값이 적용되고, 안쪽 실패는 바깥을 rollback-only로 만든다). 그래서 실패를 대체값으로 삼키는 조회는 쓰기 트랜잭션 밖에서 한다(`JdbcAnimalReports`의 닉네임). 같은 풀에서 연결을 하나 더 잡는 REQUIRES_NEW 중첩은 두지 않는다: 보호소 승인 안의 `ModuleAnimalShelterReads`는 REQUIRED로 참여하고, `JdbcAnimalMemberReads`의 읽기 템플릿도 REQUIRED이며(유일한 호출처가 트랜잭션 밖에서 호출하므로 동작은 같고, 나중에 중첩되어도 두 번째 연결을 잡지 않는다), `JdbcUserAnimalActivities`의 Animal 조회는 User 읽기 트랜잭션이 끝난 뒤 실행한다. 공유 DataSource에서 JPA 트랜잭션 매니저를 다른 트랜잭션 안에서 시작하지 않는다(JDBC 안의 JPA, JPA 안의 JPA 중첩 금지): 같은 DataSource에 이미 바인딩된 연결 때문에 실패하므로 만들지 않는다.
- `TransactionSynchronizationManager.hasResource(source)`로 "writer 트랜잭션 안"을 확인하는 가드(`JdbcStoreOrderActors`, `JdbcStoreOrderAdministrators`, `JdbcAnimalShelterMembers`, `JdbcAnimalCommandActors`, `SearchDocumentWriter`, `MembershipLockedAnimalShelterReads`)는 이제 같은 DataSource의 어떤 트랜잭션(읽기 TM, 읽기 JPA TM 포함)에서도 통과하므로 예전보다 약하다. 코드는 바꾸지 않았고, 이 가드에 기대는 새 호출은 실제 writer 트랜잭션 안에서만 만든다.
- 앞선 F 단계 설명의 reader/writer 별도 계정·풀 크기(reader 기본 2, writer 기본 1) 문구는 당시 기록이다. 현재 구성은 이 절이 기준이다.

읽기용 transaction manager와 쓰기용 transaction manager를 명시적으로 구분한다(DataSource는 위 단일 풀). 읽기는 `pawbridge_community.posts`, `pawbridge_community.comments`, `pawbridge_community.post_search_documents`, `pawbridge_user.users`, `pawbridge_user.favorites`, `pawbridge_community.home_videos`, `pawbridge_community.daily_visit_count`, `pawbridge_user.contact_deletions`, `pawbridge_community.private_note_blocks`의 필요한 열만 읽는다. Community 읽기 조합은 게시글·댓글 tx를 종료하고 연결을 반환한 뒤 User 닉네임 조회 tx를 시작한다. 이 읽기 조합에는 application 외곽 tx나 REQUIRES_NEW를 두지 않는다. F13 닉네임 수정은 별도로 기존 writer의 application 트랜잭션 하나를 사용한다. SQL의 schema와 parameter binding을 명시한다.

| 환경 변수 | 의미 |
|---|---|
| PAWBRIDGE_DB_URL | 명시적 PostgreSQL JDBC URL. 계정/쿼리 옵션을 URL에 넣지 않음 |
| PAWBRIDGE_DB_USER | 앱 단일 계정(읽기·쓰기). 필요한 schema 권한만 부여 |
| PAWBRIDGE_DB_PASSWORD | 해당 계정 암호. 이미지/소스에 포함하지 않음 |
| PAWBRIDGE_DB_POOL_MAX_SIZE | 단일 pool 상한, 기본 10, 허용 1~50 |
| PAWBRIDGE_SERVER_PORT | loopback HTTP 포트. 기본 0(임의 포트) |

필수 설정이 없으면 main은 Spring 기동/DB 접속 전에 실패한다. 운영 기본 주소나 기존 .env를 자동 탐색하지 않는다. 조회에는 readOnly tx와 SQL READ ONLY를 적용한다. 앱 classpath에 SQL 초기화·Flyway·기존 migration/consumer/스케줄러·Feign·JPA·Redis·Kafka·ES·OAuth를 추가하지 않는다. F3의 S3 SDK는 명시적 endpoint·region·정적 credential만 사용한다. 기본 AWS credential/region 탐색 체인이나 기존 Secret 파일을 사용하지 않는다.

DB의 `TIMESTAMP(6)`와 기존 Hibernate UTC JDBC 추출 의미를 보존하도록 mapper를 비교한다. 문자열에 Z를 붙이거나 무조건 9시간을 더하지 않는다. 이 후보를 운영으로 연결하기 전에는 운영 DB 권한·기존/신규 JVM timezone·총 pool 예산과 라우팅 인계를 별도 검증해야 한다.

## F3 작성 계약과 별도 쓰기 연결

`POST /api/v1/posts`는 기존 `title`, `content`, `boardType`, 선택 `files` multipart 계약과 HTTP 200 envelope·응답 9필드를 유지한다. 검증된 Bearer JWT의 양수 `userId`만 사용하며 클라이언트의 작성자 헤더·파라미터를 신뢰하지 않는다. 기존 발급기의 access token·역할을 수용하고 refresh·위조·만료·잘못된 identity는 업로드·DB 작업 전에 401로 거부한다. 신규 로그인·토큰 발급·갱신은 구현하지 않는다.

일반 게시판 3종만 작성하고 MISSING/REPORT는 업로드 전에 전용 등록 안내로 거부한다. 기존에 적용되지 않던 NotBlank 등 새 입력 규칙은 추가하지 않는다. 작성 순서는 게시판 검증 → S3 업로드 → 독립된 짧은 User 닉네임 조회 → writer 트랜잭션 → 응답이다. 예상 밖 닉네임 오류가 DB commit 뒤에 발생하지 않도록 기존 HTTP 호출 순서를 조정했다. User 부재·명시적 unavailable fallback은 F2와 같으며, 닉네임과 게시글의 동일 시점 snapshot을 보장하지 않는다.

쓰기 JDBC·TM은 위 단일 풀(`writeDataSource` 별칭)을 쓰며 qualifier로 조립한다. F3 게시글 작성 조합에는 application 외곽 트랜잭션·REQUIRES_NEW·XA를 추가하지 않는다. `posts`, `post_search_documents`의 2개 쓰기는 writer의 단일 트랜잭션에 참여한다. 구 시스템의 POST_CREATED `outbox_events` 쓰기는 의도적으로 제거했다(Debezium·Kafka·outbox를 모놀리스에서 제거하기로 결정했고 유일한 소비자였던 community-service ES 색인은 운영에서 쓰지 않았다. 이벤트 큐는 필요할 때 다시 도입한다). 후보 writer 역할에는 `outbox_events` 권한이 필요 없다. Nori 9.12.3·검색 가중치·UTC JDBC 바인딩 의미는 보존한다. F3 범위에는 검색 조회나 이벤트 소비자를 추가하지 않으며 검색 조회는 아래 F7 계약으로 구분한다.

| 추가 필수 환경 변수 | 의미 |
|---|---|
| PAWBRIDGE_JWT_SECRET | 기존 발급기 검증용 UTF-8 HMAC 키. 출력·이미지 포함 금지 |
| PAWBRIDGE_STORAGE_ENDPOINT / PAWBRIDGE_STORAGE_REGION | 명시적인 S3 호환 endpoint와 region |
| PAWBRIDGE_STORAGE_BUCKET / PAWBRIDGE_STORAGE_PUBLIC_BASE_URL | 업로드 bucket과 반환 URL 기준 |
| PAWBRIDGE_STORAGE_OBJECT_PREFIX | 게시글·신고 미디어 객체 key 앞에 붙는 prefix(예: `posts/`). 미설정 또는 빈 값이면 bucket 루트이며 기존 MSA `R2_OBJECT_PREFIX` 빈 값과 같은 key·URL(`posts/images/<uuid>.<확장자>`, `reports/images/<uuid>.<확장자>`)을 만든다. 값이 있으면 영문·숫자·`-`·`_`와 `/` 구분자만 허용하며 공백만 있는 값은 거부한다. 동물·상품 이미지는 이 값과 관계없이 bucket 루트를 쓴다 |
| PAWBRIDGE_STORAGE_ACCESS_KEY_ID / PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY | 해당 저장소에 한정한 실행 시 인증값 |

설정은 Spring/listener/외부 client 기동 전에 검사한다. multipart 한도는 파일 100MB·요청 200MB다. 실제 application.properties를 로드한 boot 시험에서 두 한도의 Servlet 바인딩을 보호한다. 선택 파일의 순서·MIME·확장자·URL 의미를 유지하고 stream을 닫는다. 업로드 일부 실패 또는 뒤이은 DB 실패 시 이미 업로드한 객체가 남는 기존 정책을 유지한다. 자동 POST 재시도·객체 삭제·idempotency·보상 큐를 추가하지 않는다. commit 결과가 불명확한 경우에도 업로드 객체를 임의 삭제하지 않는다. 이 후보의 시험 성공은 실제 R2 연결·운영 전환 증거가 아니다.

예상 밖 작성 오류는 기존 500 응답을 유지하며, 서버 로그에는 예외 종류와 제한된 코드 위치만 남긴다. 예외 메시지·cause/suppressed 메시지·원본 Throwable·요청값·인증값·업로드 파일명·서명 URL은 로그 인자로 전달하지 않는다. 원인은 최대4개, 원인별 코드 위치는 최대6개, 진단 문자열은 최대4096자로 제한한다. 상세 SQL/SDK 메시지를 보존하는 로그가 아니므로 이 메타데이터만으로 장애 원인을 확정할 수는 없다.

## F4 본인 삭제와 파일 실패 경계

`DELETE /api/v1/posts/{postId}`는 기존 JWT의 작성자 본인만 허용한다. ADMIN도 일반 경로에서 다른 작성자의 글을 삭제할 수 없다. 성공은 HTTP/code200·`게시글이 삭제되었습니다.`·data:null이며, 부재/재삭제는404, 타인은403이다. 이 경로의 잘못된 숫자 타입은 기존500을 보존한다. legacy advice의 TypeMismatch import는 Hibernate 타입이므로 Spring 경로 변환 실패를 처리하는400 경로가 아니다. POST 작성 제한과 달리 기존5개 게시판 모두 삭제 대상이며 관리자 전용 삭제·별도 제보 삭제는 추가하지 않는다.

짧은 reader 조회와 소유권 확인이 끝난 뒤 연결을 반환하고, 저장된 URL의 미디어 삭제를 시도한다. writer는 `FOR UPDATE`로 미삭제 상태·소유자를 다시 확인하고 조건부 soft delete·updatedAt·검색 문서 삭제를 한 트랜잭션으로 처리한다(구 시스템의 POST_DELETED Outbox 쓰기는 제거). soft delete는 본문·작성자·생성일·미디어 URL·댓글/쪽지를 물리 삭제하지 않는다. 같은 글의 경쟁 요청은 하나만 DB 삭제를 커밋하고 나머지는404가 된다. 외부 미디어 시도는 경쟁 요청 양쪽에서 먼저 발생할 수 있다.

미디어는 명시 public-base·bucket·object-prefix 안의 DB 저장 URL만 처리한다. object-prefix가 비어 있으면 prefix 소유 범위 검사는 없고 public-base URL 안의 key만 대상이며, 경로·인코딩 우회 방지 가드는 그대로 적용한다. 업로더가 생성하는 `posts/images|videos/UUIDv4`와 마지막 점부터의 suffix는 원문 객체 key로 인식해 SDK에 그대로 전달한다. 이 경우 `%`, `?`, `#`, 구분자·제어문자를 URL로 재해석하거나 디코딩하지 않으며, 일반 key에는 기존 경로·인코딩 우회 방지 가드를 유지한다. URL별 검증/SDK 실패 후에도 다음 URL과 DB 처리를 계속하며 null·중복·순서를 임의 정규화하지 않는다. 파일 삭제 성공 후 DB rollback이 확정되면 파일은 복원되지 않고, 저장소 실패 후 DB 삭제가 성공하면 파일이 남을 수 있다. commit 결과 불명은 원래 오류로 전달하며 새 자동 재시도·객체 복원·보상 큐를 추가하지 않는다. SDK의 기존 같은 key 재시도와 새 애플리케이션 재처리는 구분한다. 오류 로그에는 URL·키·예외 메시지·원본 Throwable을 전달하지 않는다.

기존 reader max2/writer max1과 F3 설정을 재사용한다. 실제 운영 역할/ACL·라우팅·R2 권한은 이 후보가 변경하지 않는다.

## F5 본인 수정과 첨부 교체

`PUT /api/v1/posts/{postId}`는 선택 `title`, `content`, `files` multipart와 기존200 envelope·응답9필드를 유지한다. 검증된 작성자 본인만 허용하고 ADMIN도 타인의 글을 수정할 수 없다. F3 작성 제한을 가져오지 않으므로 기존5개 게시판 모두 수정 대상이다. title/content 생략은 현재 값을 유지하고 명시 빈 문자열은 그대로 전달한다. files 미전달은 유지, 빈 파일 한 개는 기존 첨부를 삭제하고 빈 목록으로 교체하는 요청이다. 새 웹 입력 제약을 추가하지 않으며 DB 길이 위반은 기존500, 명시된 문자 rollback 제약만400으로 매핑한다.

reader 조회·소유권 확인 → 필요할 때 기존 파일 삭제 시도 → 새 업로드 → 닉네임 조회 → 짧은 writer tx 순서다. 외부 미디어·닉네임 I/O 중 두 DB 연결을 점유하지 않는다. writer는 `FOR UPDATE`로 미삭제 상태·소유자를 다시 확인하고 누락 필드는 잠근 현재 행에서 가져온다. 이로써 경쟁 수정의 다른 필드가 사라지거나 삭제된 글이 되살아나지 않는다. 외부 업로드 뒤의 재확인에서404/403이 발생할 수 있으며 이미 삭제한 파일 소실·새 객체 잔류는 기존 한계다. 새 자동 보상·애플리케이션 재시도는 넣지 않는다.

실제 legacy JPA dirty checking·auditing·응답 생성 순서를 PostgreSQL에서 비교한다. 무변경/동일값 PUT은 행과 updatedAt을 바꾸지 않아도 검색 투영을 갱신한다(구 시스템의 `POST_UPDATED` outbox 행은 쓰지 않는다). 값이 바뀐 PUT의 응답 updatedAt은 commit 전의 이전 값이며, 후속 GET은 감사 처리한 새 시각을 반환하는 기존 차이를 보존한다. SQL NULL 이미지는 빈 목록으로 읽는다. 구 시스템에서 JSON literal null 이미지의 text-only 수정이 500으로 rollback한 것은 POST_UPDATED outbox payload 생성(`Map.of`) 때문이었으므로, outbox를 쓰지 않는 후보는 이 경우를 오류 없이 처리하는 의도적 차이가 있다(후보는 outbox를 쓰지 않으므로 이 저장 형태를 만들지 않는다). 첨부 교체가 성공하면 빈 목록 또는 새 URL 목록으로 바꿀 수 있다.

게시글 변경·Nori 검색 투영은 기존 writer 한 트랜잭션이다. reader max2/writer max1·설정·S3 client를 재사용하며 운영 schema/역할/ACL을 변경하지 않는다. 동일 필드/다른 필드 PUT 경합과 PUT/DELETE 양 순서는 독립 PG 연결·실제 blocking PID로 검증한다. 실패 응답과 로그의 인증값·예외 메시지 제외 정책은 F3/F4와 같다.

첨부 파싱은 컨트롤러 선택 전에 실패할 수 있으므로, `MultipartException`만 받는 공통 advice를 boot에서 명시적으로 조립한다. POST/PUT의 잘못된 multipart·용량 초과는 기존500/code/message/data 응답을 유지하고 로그에는 예외 클래스만 남긴다. 일반 업무/인증 오류의 담당,100MB/200MB 한도와 기본 eager parsing은 그대로다. 실제 resolver의 `getParts()` 실패를 주입한 회귀에서 handler=null·무토큰401/파싱0회·인증 후 안전한500·DB 연결0을 확인한다. 이 시험은 실제 대용량 바이트 전송이나 운영 저장소 시험이 아니다.

## F6 공개 목록 계약

`GET /api/v1/posts/read`는 비로그인 공개 API이며 기존 200 envelope의 `data`에 9필드 게시글 배열을 반환한다. 빈 결과는 `[]`다. `page`·`size`·`boardType` 쿼리로 서버 필터나 페이지를 만들지 않는다. 기존 프런트가 전체 배열에서 게시판과 화면 페이지를 계산하므로 응답을 임의로 줄이지 않는다. 관리자 Page·검색 readiness/순위/10건 제한은 이 계약과 별개다.

기존 `JdbcPostReader`는 같은 reader 연결·행 mapper로 미삭제 게시글을 한 번 조회하고 `created_at DESC` 순서로 전부 매핑한 뒤 tx와 연결을 반환한다. 같은 생성 시각의 ID 보조 정렬은 추가하지 않는다. 이후 application이 행 순서대로 `UserNicknameQuery`를 호출하며 같은 작성자도 행마다 조회한다. User 테이블을 Community SQL에 JOIN하거나 nickname cache·새 pool·새 읽기 권한을 추가하지 않는다.

닉네임은 F2의 명시된 부재·조회 실패만 `사용자{id}`로 표시하고 빈 닉네임의 null 호환·공백은 유지한다. 옛 HTTP helper의 넓은 `catch(Exception)`과 같은 예외 범위라고 주장하지 않으며, 예상 밖 nickname 오류나 게시글 SQL/JSON 매핑 실패는 목록 전체의 안전한500으로 처리한다. 실패 행 생략·부분200·오류를 빈 배열로 숨기는 처리는 없다. SQL NULL 이미지→빈 배열·JSON literal null→null과 기존 날짜 의미도 재사용한다.

exact 목록 GET만 보안 허용 목록에 추가한다. 공개 목록은 불필요하게 Bearer 인증을 요구하지 않으며 기존 단건·쓰기 보안과 오류 처리는 유지한다. 실행기 자체는 여전히 F3의 writer/JWT/S3 설정을 요구한다. 전체 배열·행별 닉네임의 운영 데이터량에 대한 성능·메모리 수용성이나 구 서비스 종료는 이 후보의 기능 검증과 구분한다.

## F7 공개 게시글 검색

`GET /api/v1/posts/search?keyword=...`는 기존 공개 검색의 200 envelope·9필드 배열을 유지한다. 전체 게시판에서 `ts_rank DESC`, `created_at DESC`, `post_id DESC` 순서로 최대10건을 반환한다. 기존 프런트가 그 결과에 게시판 필터와 화면 페이지를 적용하므로 서버 필터·Page·count·score 필드를 추가하지 않는다.

필수 `keyword` 누락은 기존 메시지의400이다. 빈 문자열·공백은 길이 검사보다 먼저 빈 배열로 처리하며, Nori 분석 후 토큰이 없는 경우도 검색 트랜잭션·DB·닉네임 조회를 하지 않는다. 비공백 입력의 UTF-16 길이200 초과·NUL·분석 후 중복 제거 단어32개 초과는400이다. Nori9.12.3과 `nori-9.12.3-v1`, 중복 제거·OR 검색·tsquery escaping을 보존한다. writer의 document 토큰·생명주기는 변경하지 않는다.

검색은 모든 미삭제 게시글의 projection 존재·제목/본문 hash·analyzer version을 같은 SQL statement의 readiness/ranked CTE로 확인한다. 매칭되지 않는 글 하나가 stale이어도503이며 삭제 글의 stale 자료는 제외된다. GET은 검색 문서를 재구축하거나 데이터를 보정하지 않는다. SQL/연결 획득·검색 읽기 트랜잭션 시작/시간초과는503, 잘못된 저장 JSON·enum과 예상 밖 오류는500이다. 검색어·본문·URL·인증값·원예외 메시지를 로그에 남기지 않는다.

검색 날짜는 legacy PostgreSQL 검색의 `getObject(LocalDateTime.class)` 의미를 보존한다. 단건·전체 목록의 Hibernate/UTC 호환 mapper를 무조건 재사용하지 않는다. 검색 SELECT 전체를 매핑하고 연결을 반환한 뒤 기존 User 공개 계약으로 닉네임을 순차 조회한다. F2/F6의 명시된 unavailable fallback과 빈닉네임 null 호환을 유지하며 legacy 원격 helper의 넓은 catch와 완전히 같은 예외 범위라고 표현하지 않는다.

검색용 JDBC·읽기 트랜잭션 timeout은 각각5초이며 기존 reader DataSource와 transaction manager를 사용한다. 단건·목록의3초와 기존 단일 pool 구성은 유지한다. 5초는 전체 HTTP 응답시간 SLA가 아니며 Top10도 전역 readiness의 DB 비용 상한을 보장하지 않는다. 실행기는 여전히 기존 writer/JWT/S3 설정이 필요하다.

검색은 새 schema·index·계정·pool·설치와 운영 권한 변경을 만들지 않는다. 운영 후보 인계 시 실제 읽기 계정 권한·전체 데이터 성능·단일 작업 소유권과 Gateway 라우팅은 별도 확인해야 한다.

## F8 공개 댓글 목록

`GET /api/v1/comments/posts/read/{postId}`는 비로그인 공개 API다. 기존 200 envelope의 `data`에 `commentId`, `postId`, `authorId`, `authorNickname`, `content`, `createdAt`, `updatedAt`의 정확히 7필드 배열을 반환하며 빈 결과는 `[]`다. `page`·`size` 등으로 결과를 줄이거나 작성자 헤더로 필터하지 않는다. 댓글 생성·수정·삭제 경로와 다른 메서드는 공개하지 않는다.

같은 reader의 짧은 읽기 전용 트랜잭션에서 해당 `post_id`의 미삭제 댓글을 `created_at DESC`로 모두 조회한다. 같은 생성 시각에서 ID 순서를 새 보장으로 추가하지 않는다. 부모 게시글 존재·삭제 상태를 조회하거나 JOIN하지 않으므로 부모가 soft delete되어도 미삭제 댓글은 보인다. 게시글이 없고 댓글도 없으면200·빈 배열이며, 부모 없는 저장 댓글도 기존 조회처럼 반환한다. 날짜는 실제 legacy Hibernate의 UTC JDBC 추출을 UTC·Seoul JVM에서 비교한다.

전체 댓글 행을 매핑하고 연결을 반환한 뒤 `UserNicknameQuery`로 순차 조회한다. 같은 작성자도 행마다 조회하며 cache·추가 pool·User SQL JOIN은 없다. 명시된 사용자 부재·조회 불가만 `사용자{id}`로 처리하고 빈 닉네임의 null 호환과 공백을 보존한다. legacy 원격 helper의 넓은 `catch(Exception)`을 그대로 재현했다고 주장하지 않는다. 댓글 SQL·행 매핑·예상 밖 닉네임 오류는 전체 안전한500이며, 부분200·오류 행 생략·실패를 빈 배열로 숨기는 처리는 없다.

실행기에는 기존 단일 pool과 writer/JWT/S3 필수 설정이 유지된다. F8은 댓글 쓰기 권한을 추가하지 않으며 운영 스키마·권한을 변경하지 않는다. 전체 댓글·행별 닉네임의 운영 데이터량 성능과 Gateway 전환은 별도 검증 대상이다.

## F9 인증된 댓글 작성

`POST /api/v1/comments/posts/{postId}`는 JSON `content`를 받아 검증된 기존 Bearer JWT의 양수 `userId`로 댓글 한 행을 생성한다. USER·ADMIN·SHELTER를 수용하며 위조 작성자 헤더를 신뢰하지 않는다. 성공은 HTTP/code200·`댓글이 생성되었습니다.`·F8과 같은 정확히 7필드다. identity ID를 사용하며201·연속 ID·중복 방지를 추가하지 않는다. F9 단계의 쓰기는 작성 한 계약이며 삭제는 아래 F10 계약으로 추가한다. F8에서 거부하던 이 POST의 익명 요청은 인증 필요401로 바뀌고 공개 GET은 유지한다.

실제 기존 요청 DTO의 `@NotBlank`만 적용한다. 누락/null/빈 문자열/기존 ASCII blank는400·`content: 내용은 필수입니다`이며 잘못된 JSON·누락 본문은400·`요청 본문 형식이 올바르지 않습니다.`다. 검증 provider의 기존 trim 기반 의미를 유지하여 U+2003·전각 공백·앞뒤 공백·줄바꿈을 임의로 제거하거나 새 길이 제한을 두지 않는다. malformed/long 초과 경로는 기존 안전한500이며0·음수는 부모 조회 결과를 따른다. 버전 관리된 V3 `ck_rollback_charset_comments` 위반만400·`일부 이모지 등 지원하지 않는 문자를 제외해 주세요.`로 구분하고, NUL 등 다른 SQL 실패는 안전한500이다.

활성 부모를 기존 `PostReader`로 확인하고 연결을 반환 → 작성자 `UserNicknameQuery` 연결을 반환 → 기존 writer의 짧은 트랜잭션으로 comments INSERT/RETURNING/commit → 응답 순서다. 부모 부재·삭제는 User 조회보다 먼저404·`게시글을 찾을 수 없습니다.`이며 모든 게시판·타인 글에 댓글을 허용한다. 새 회원 상태·부모 소유자·게시판 정책은 없다. 단일 부모 확인 뒤 삭제가 경쟁하면 작성될 수 있는 기존 경계를 유지하며 새 FK·잠금·재조회는 넣지 않는다.

작성자 부재·명시적 조회 불가는 `사용자{id}`, 빈 닉네임은null, 공백은 원문을 유지한다. 예상 밖 User 오류는 쓰기 전500으로 닫는다. legacy의 INSERT 뒤 Feign 호출·넓은 `catch(Exception)` 순서와 모든 장애를 동등하게 재현했다고 주장하지 않는다. 확정 SQL/commit rollback은 댓글 행을 남기지 않으며 sequence gap은 허용한다. commit 결과 불명·응답 전송 실패를 확정 rollback으로 표현하거나 서버가 POST를 자동 재시도하지 않는다. 즉시 응답 시각의 정밀도와 DB `TIMESTAMP(6)`를 재조회한 정밀도를 억지로 같게 만들지 않는다.

Outbox·검색 projection·S3는 호출하지 않는다. 기존 단일 pool·JDBC URL·writer/JWT/S3 필수 설정을 재사용하며 새 운영 환경 변수는 없다. 기존 JSON Bean Validation에 필요한 `starter-validation`/validation API만 추가하고 버전은 BOM을 따른다.

## F10 본인 댓글 삭제

정확한 `DELETE /api/v1/comments/{commentId}`만 기존 JWT 인증 대상에 추가한다. 검증된 principal만 사용하고 위조 작성자 헤더를 무시한다. USER·ADMIN·SHELTER의 본인 댓글은 허용하며 타인 ADMIN도403·`댓글에 접근할 권한이 없습니다.`다. 미삭제 댓글을 먼저 찾고 부재·이미 삭제는404·`댓글을 찾을 수 없습니다.`다. 익명은 경로 바인딩보다 먼저401이며 malformed/overflow ID는 기존 안전한500,0·음수는 조회 결과를 따른다. 성공은 HTTP/code200·`댓글이 삭제되었습니다.`·`data:null`이다. DELETE 본문은 읽거나 검증하지 않는다. 댓글 PUT은 아래 F11 계약으로 추가하며 인접 경로 거부를 유지한다.

같은 writer 트랜잭션에서 활성 댓글 작성자를 일반 SELECT → 소유권 확인 → 두 시각 생성 → ID 조건의 UPDATE → commit 순서로 처리한다. 물리 행·ID·부모 ID·작성자·본문·생성 시각은 보존하고 `deleted_at`, `updated_at`만 갱신한다. 삭제 시각과 auditing 시각을 새 동일값 불변식으로 만들지 않는다. 부모 존재/삭제·게시판·User 상태와 무관하며 부모·User·S3·Outbox·검색을 호출하지 않는다. 기존 writer pool max1과 단일 pool/JDBC/TM·운영 설정을 그대로 사용한다.

레거시 댓글에는 버전·명시적 잠금이 없으므로 F4 게시글의 단일 승자 정책을 옮기지 않는다. 두 삭제가 활성 상태를 먼저 읽으면 둘 다200일 수 있고 순차 재삭제만404다. `FOR UPDATE`·active/author UPDATE 조건·재조회·자동 재시도를 추가하지 않는다. 조회 뒤 물리 행이 사라져 UPDATE0이면 기존 stale-state와 같은 안전한500이다. 후보의 두 열 갱신은 레거시의 넓은 Hibernate flush와 달리 동시 PUT의 본문을 덮지 않는 의도된 차이다. 모든 수정/삭제 경쟁이 동일하거나 역방향 legacy PUT의 삭제 재활성화까지 해결됐다고 표현하지 않는다.

구조화된 community rollback charset SQLState23514만 기존 메시지400으로 보존하고 다른 CHECK·SQL·commit 오류는 안전한500이다. 이는 DELETE 본문 문자 검증을 추가하는 것이 아니다. 확정 rollback의 행 보존을 확인하며 commit 결과 불명·응답 전송 실패를 확정 rollback으로 표현하지 않는다.

이 기능의 writer 권한은 comments의 `SELECT(author_id,deleted_at)`, `UPDATE(deleted_at,updated_at)`만 추가하며 물리 DELETE·본문 UPDATE·sequence·User 쓰기·schema CREATE 권한은 필요하지 않다.

## F11 본인 댓글 수정

정확한 `PUT /api/v1/comments/{commentId}`는 JSON `content`와 기존 JWT principal을 사용한다. USER·ADMIN·SHELTER의 본인만 허용하며 위조 작성자 헤더를 무시한다. 익명/잘못된 JWT는 경로·본문 바인딩보다 먼저401이다. 실제 기존 요청 DTO의 `@NotBlank`를 유지하여 누락/null/빈 문자열/ASCII blank는400·`content: 내용은 필수입니다`, 잘못되거나 누락된 JSON 본문은400·`요청 본문 형식이 올바르지 않습니다.`다. U+2003·전각 공백·원문·무상한을 보존한다. malformed/overflow ID는 안전한500,0·음수·부재·이미 삭제는404다. 본문 검증은 유스케이스의404/403보다 먼저 실행될 수 있다.

활성 댓글 snapshot 조회와 소유권 확인 → reader 연결 반환 → User 닉네임과 연결 반환 → 최초 content와 요청 원문 비교 → 다를 때만 짧은 writer 트랜잭션 → commit → 응답 순서다. 부재는404·`댓글을 찾을 수 없습니다.`, 타인은 ADMIN도403·`댓글에 접근할 권한이 없습니다.`이며 User·쓰기보다 먼저다. 부모·회원 상태를 새로 확인하지 않는다. 같은 본문도 닉네임은 조회하고 missing/unavailable은 `사용자{id}`, 빈 닉네임은null, 공백은 원문을 유지한다. 예상 밖 User 오류는 쓰기 전500이다. 동일 본문은 modification 호출·쓰기 트랜잭션 자체가 없다.

성공은 HTTP/code200·`댓글이 수정되었습니다.`·기존7필드다. content는 요청 원문이며 createdAt·updatedAt은 최초 저장 snapshot 값이다. 변경 시 DB updated_at은 commit auditing 시각으로 바뀌므로 이후 F8 GET은 새 시각을 보여준다. HTTP 성공 전 commit을 완료한다. 후보 SQL은 `content`, `updated_at` 두 열만 ID 조건으로 갱신해 deleted_at과 다른 저장값을 보존한다. UPDATE0은 안전한500이다. `FOR UPDATE`·active/author/content UPDATE 조건·재조회·version·retry는 없다. 최초 snapshot과 같은 요청은 그 뒤 다른 요청이 바꾼 DB content를 덮지 않고 이전 snapshot을 응답할 수 있다. 변경으로 판단했다면 현재 DB가 이미 요청 content여도 auditing을 수행한다. 먼저 commit된 삭제 상태를 보존하는 두 열 갱신은 legacy 전체열 flush의 재활성화를 피하는 명시된 차이이며 모든 경합이 동등하다고 주장하지 않는다.

구조화된 community rollback charset23514만 기존 고정400이며 NUL·다른 SQL/CHECK/commit은 안전한500이다. 자동 재시도·Outbox·검색·S3 호출은 추가하지 않는다. 기존 단일 pool과 JDBC/TM·writer·필수 운영 설정을 재사용하며 새 runtime 환경 변수는 없다.

이 기능의 writer 권한은 기존 INSERT5·SELECT(id,author,deleted_at)에 `UPDATE(content)` 한 열만 추가한다. UPDATE는 content/deleted_at/updated_at만 허용하며 sequence·물리 DELETE·User 쓰기·schema CREATE는 필요하지 않다.

## F12 인증된 본인 정보 조회

정확한 `GET /api/v1/users/me`만 기존 Bearer 검증 대상에 추가한다. 서명·만료·양수 userId·비null String role 검증을 재사용하고 검증된 principal의 ID만 조회한다. X-User-*·query·본문의 다른 회원 ID를 무시한다. 기존 JWT subject는 email이며 식별자는 userId다. 새 subject 일치·issuer/audience·role allowlist·회원 상태 정책은 없다. 기존 Gateway가 subject/name을 헤더용으로 읽는 것과 후보 검증기의 차이는 유지한다. USER·ADMIN·SHELTER 모두 본인 조회를 허용한다. 기존 공개 GET·인접 경로 거부와 내부 ERROR dispatch 의미는 유지한다.

성공은 HTTP/code200·`요청이 성공적으로 처리되었습니다.`·정확7필드 `userId,email,name,nickname,provider,role,createdAt`다. 전부 현재 DB 값이며 JWT의 email/name/role을 복사하지 않는다. 빈 nickname과 공백은 그대로 반환하고, nullable TIMESTAMP(6)의 createdAt은 명시적 JSON null을 유지한다. 댓글의 nickname fallback/empty→null이나 임의 현재 시각을 가져오지 않는다. 실제 User에는 deleted_at/status가 없으며 pending 탈퇴·로그아웃 blacklist 검사를 추가하지 않는다. 실제 행 부재는 기존400·`존재하지 않는 회원입니다.`·data:null이며 DB/연결/트랜잭션·예상 밖 오류는 안전한500이다. 인증 실패401은 조회보다 먼저 반환하며 SQL 실패를 회원 부재로 숨기지 않는다.

User 내부 `UserProfileQuery.require(id)`가 같은 reader의 3초 read-only 트랜잭션 안에서7열을 schema-qualified 조회하고 부재 application 예외를 발생시킨다. 단순 위임 usecase·미사용 공개 profile API·빈 domain이나 전체 User aggregate는 없다. 기존 UserNicknameQuery와 Community production은 유지하며, 같은 단일 pool/JDBC/TM·writer/JWT/S3 필수 설정을 재사용한다. 새 runtime 환경 변수·쓰기 권한·Outbox·검색·S3 호출은 없다. 실제 NOT NULL·role CHECK가 보장하는 저장 문자열을 신뢰하며 제약을 제거한 손상 데이터의 legacy enum 처리까지 동등하다고 주장하지 않는다.

이 기능의 reader 권한은 기존2열에 email/name/provider/role/created_at만 더한 정확7열 SELECT이며 민감4열 SELECT·User 쓰기·schema CREATE는 필요하지 않다.

## F13 본인 닉네임 수정

`PUT /api/v1/users/me/nickname`은 기존 검증된 JWT principal의 본인만 대상으로 JSON nickname을 처리한다. 실제 요청 DTO의 NotBlank·길이2~30·한글/영문을 최소 하나 포함하는 한글/영문/숫자 패턴을 유지하고 trim·대소문자 정규화·새 회원 정책은 추가하지 않는다. `ChangeUserNickname`의 실제 Spring proxy가 기존 `writeTransactionManager` 한 트랜잭션 안에서 현재 nickname 조회→원문 동일값이면 중복 조회·UPDATE·auditing 생략→DB lower 중복 조회→nickname/updated_at 두 열 갱신을 수행한다. 성공은 동일값도 HTTP/code200·`닉네임이 변경되었습니다.`·data:null이다. 회원 부재400·사전 중복409를 유지한다. 원본의 managed save 이후 UNIQUE 경합23505는 메서드 catch 밖 commit에서 실패하며, 후보도 JDBC UPDATE에서 발생한 실제 DB 경합을 일괄409로 바꾸지 않고 안전한500으로 표현한다. 구조화된 User schema·23514·rollback charset 제약 metadata만 고정400으로 표현한다. 후보의 좁은 UPDATE는 원본 전체행 flush와 달리 동시 변경된 role/비밀번호를 덮지 않는다. 새 pool·환경 설정·잠금·version·retry·Outbox·S3 호출은 없다. 로컬 시험과 JAR 빌드는 운영 배포나 실제 운영 DB 권한 변경의 근거가 아니며, 이 후보는 미배포 상태다.

## F14 본인 찜 여부 조회

`GET /api/v1/favorites/{animalId}/check`는 검증된 JWT principal의 userId와 경로의 원문 Long animalId로 존재 여부만 조회한다. User/Animal 부재 확인이나 양수 검증·외부 호출을 추가하지 않는다. 성공은 HTTP/code200·기본 성공 문구·boolean data이며 찜이 없거나 회원이 없어도 false다. X-User-*·query·GET body로 식별자를 바꾸지 않는다. 기존 reader의 3초 read-only 트랜잭션에서 두 열만 조건으로 사용하며 SQL/연결/트랜잭션 오류를 false로 숨기지 않고 안전한500으로 반환한다. 원본 Controller/Advice의 실제 변환 오류도500을 유지하며 새400 개선을 섞지 않는다. 새 pool/TM·환경 변수·모듈 공개 API·Outbox·S3 호출은 없다. 찜 POST/DELETE와 운영 DB 권한·배포는 이 후보 범위가 아니다.

## F15 공개 홈 영상 조회

정확한 `GET /api/v1/home/videos`는 익명·잘못된 Bearer도 허용하는 공개 조회다. `ReadHomeVideos`가 Clock.instant를 한 번 읽어 정확한 Duration 29일 전 cutoff를 DB 연결 전에 계산한다. reader는 published·available·checked_at > cutoff를 만족하는 영상을 position/created_at/id 오름차순으로 최대3개 읽고, 결과가 없으면 정상 빈 배열을 반환한다. UUID·영상ID·게시/위치·Instant 두 필드·nullable metadata 네 필드·available의 정확11필드와 null/빈 문자열을 보존한다. TSTZ를 Instant로 읽으므로 기존 TIMESTAMP의 Seoul9시간 비교 helper를 사용하지 않는다. DB·연결·TX 시작/완료·예상 밖 Runtime은 원본의 안전한503 메시지로 표현하고 예외 타입만 기록한다.

같은 reader·3초 read-only TX와 단일 pool을 재사용한다. 원본 home()는 명시적 TX 없이 조회하므로 보호 TX 도입은 후보의 기술적 차이다. 독립 Clock.systemUTC만 조립하며 원본 PrivateNoteConfiguration·HomeVideoRefresh·YouTube client·API key·예약/metadata 갱신을 등록하지 않는다. board 권한·새 writer 권한·User/Outbox/search/S3 호출이나 JWT 발급/갱신은 없다. 관리자 편집과 구 실행기의 예약 소유권 이전·운영 배포는 이 후보에 포함되지 않는다.

## F16 공개 오늘 방문자 합계 조회

정확한 `GET /api/v1/visits/today`는 기존 `pawbridge.visits.enabled` 설정과 `DAILY_VISITORS_ENABLED` 환경 변수의 기본 false를 유지한다. 단독 조회 모드(`pawbridge.visits.collection-enabled=false`)에서 true일 때 조회 실행자·reader·controller·advice만 조립하며 서명키·VisitCookie·원본 lifecycle·시작 INSERT·집계 POST·정리/예약 작업은 등록하지 않는다. 공개 GET 보안 matcher는 두 상태에서 같고 false/누락은404다. 이404 본문을 원본 global envelope와 동일하다고 주장하지 않으며 미편입 경로를 열지 않는다.

조회 실행자는 F15의 Clock에서 instant를 한 번 읽고 서울 날짜를 TX/연결 전에 결정한다. DATE 조건·BIGINT 합계만 읽어 정확한 date/count 두 필드와 부재0을 반환한다. Date.valueOf 바인딩, 원문 long JSON number와 최초 날짜를 보존하며 대기 중 자정 재검사·현재 시각 재조회·음수/null 보정은 없다. 익명·잘못된 Bearer·X-User 헤더·Origin·Cookie·bot 여부로 합계를 바꾸지 않는다. 성공과 DataAccessException503은 명시적 no-store이며 쿠키를 발급하지 않는다. DAO503은 `방문 통계를 불러올 수 없습니다.`, 일반 Runtime500은 `서버 에러!`다. 실제 commit 예외가 DAO로 번역되면503이며 TX 실패를 모두500/503으로 묶지 않는다. 일반500에는 별도 캐시 정책을 추가하지 않고 공통 보안 헤더를 구분한다.

기존 reader의 3초 read-only TX는 원본 today()의 autocommit과 구분되는 후보 보호다. 새 pool/TM·JWT 정책/발급·서명키·외부 요청·쓰기·Outbox·이벤트는 없다. 시작 기록·집계·cleanup은 기존 실행기가 계속 소유하고 조회 편입을 운영 배포나 기존 실행기 종료로 표현하지 않는다.

## 구조 검사

`gradle/architecture.gradle`의 `verifyArchitecture`와 `verifyBusinessPackages`를 유지한다.

- 루트는 집계만, boot만 실행 JAR, user/community는 java-library다.
- 세 모듈 DAG·순환/boot 역참조·외부 source/resource/compiler input·class output 소유권을 검사한다.
- api에는 JDK/허용 api만 노출하며 타 업무 internal 접근과 내부 타입 signature 누출을 거부한다.
- domain은 자기 domain과 기술을 제외한 JDK만 허용한다. application은 web/infrastructure/JPA/JDBC/HTTP/Feign/TransactionTemplate을 참조하지 않는다. Spring 선언 예외는 Transactional/Propagation/Isolation뿐이다.
- application 검사는 명시 기술 blacklist이며 임의 외부 라이브러리의 모든 의미를 판정하는 샌드박스는 아니다. 리플렉션·업무 책임도 코드 리뷰한다.
- ArchUnit은 빌드 도구이며 앱 runtime에 포함하지 않는다.
- Kotlin 표준 라이브러리(`kotlin.*`)와 `org.jetbrains.annotations.*`는 kotlinc가 모든 class에 넣는 언어 runtime이라 api·domain에서도 JDK처럼 허용한다. `kotlinx.*`(coroutines·serialization 등)는 일반 외부 기술로 남는다. TestKit의 compiled marker fixture가 두 경계를 검증한다.

### Kotlin 혼합 빌드

- 루트 `build.gradle`이 모든 모듈에 Kotlin 1.9.25(Spring Boot 3.4.11 BOM의 `kotlin.version`), kotlin-spring(all-open), kotlin-jpa(no-arg)를 적용한다. JPA `@Entity`·`@MappedSuperclass`·`@Embeddable`도 all-open으로 Java처럼 non-final이다. JVM 대상은 21이고 `-Xjsr305=strict`와 `-Xemit-jvm-type-annotations`를 쓴다. 뒤쪽 옵션은 `List<@NotNull UUID>` 같은 type-use 애노테이션(Bean Validation 컨테이너 요소 제약)을 javac처럼 class 파일에 기록한다. `kotlin-reflect`와 `jackson-module-kotlin`은 모든 모듈의 `implementation`에 있다.
- 모듈 `build.gradle`에는 Kotlin plugin을 쓰지 않는다. 구조 TestKit fixture가 실제 모듈 스크립트를 Kotlin plugin 없는 루트에 복사해 적용하기 때문이다.
- `.kt`는 `src/main/kotlin` 또는 `src/main/java`에 둘 수 있다. Kotlin이 먼저 컴파일되고 Java가 그 class를 본다. 같은 모듈의 Java Lombok 생성 멤버는 Kotlin에서 보이지 않으므로 Lombok class는 함께 Kotlin으로 옮기거나 일반 Java 접근자로 바꾼다.

## 운영 배포

운영 백엔드는 Mac mini의 Docker Compose 프로젝트 `pawbridge-prod`에서 `monolith` 컨테이너 하나로 돈다. GitHub Actions는 이미지를 만들거나 올리지 않으므로 배포는 Mac mini에서 수동으로 한다. 운영 이미지는 레지스트리에 올리지 않고 로컬에만 둔다.

1. **main 커밋에서만 빌드한다.** dev 병합 → 병합 전 로컬 검증(아래 "검증") → main 병합 순서를 지킨 뒤, 배포할 main 커밋을 깨끗한 작업 폴더로 꺼낸다.
2. **JAR과 이미지를 만든다.**
   ```bash
   cd backend-app
   ./gradlew projects check :boot:bootJar
   docker build --pull=false --build-arg GIT_SHA=$(git rev-parse HEAD) -t pawbridge-monolith:prod-$(git rev-parse --short=12 HEAD) .
   ```
   `Dockerfile`과 `.dockerignore`가 이 폴더에 있다. 빌드 맥락에는 `boot/build/libs/pawbridge-backend-app.jar`만 들어간다.
3. **이미지를 교체한다.** 운영 compose 폴더([`deploy/compose`](../deploy/compose/README.md))에서 `compose.env`를 날짜 붙은 이름으로 백업하고 `MONOLITH_IMAGE` 한 줄만 새 태그로 바꾼 뒤 monolith만 다시 만든다.
   ```bash
   docker compose -p pawbridge-prod -f compose.yaml --env-file compose.env up -d --no-build --pull never --no-deps monolith
   ```
4. **확인한다.** 컨테이너가 `healthy`가 되고, 공개 API(예: `GET /api/v1/posts/read`, `GET /api/v1/animals`)가 정상 응답하며, 10분 동안 ERROR 로그와 운영 경고가 없는지 본다.
5. **롤백한다.** 문제가 있으면 백업한 `compose.env`를 되돌리고 3의 명령을 다시 실행한다. 이전 이미지는 로컬에 남아 있다. DB 마이그레이션을 함께 적용했다면 이전 이미지와 호환되는지 먼저 확인한다.

## 검증

GitHub Actions(`Backend app check`)는 최소 검사만 한다. 모든 source set(main, test, architectureTest, contractTest)을 컴파일하고 실행 JAR을 만든다. 단위·구조·DB 계약 시험은 GitHub에서 돌리지 않고, 병합 전에 PR head에서 로컬로 실행한다.

```bash
cd backend-app
./gradlew projects check :boot:bootJar :boot:architectureTest
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :boot:contractTest :boot:jarSmokeTest
```

- `check`: 모듈별 단위 시험과 `verifyArchitecture`·`verifyBusinessPackages`.
- `:boot:architectureTest`: 빌드 규칙(`gradle/architecture.gradle`)을 TestKit fixture로 검증한다. 빌드 규칙을 바꿀 때 반드시 실행한다.
- `:boot:contractTest`: Testcontainers PostgreSQL 위의 DB 계약 시험(아래 "DB 계약 시험").
- `:boot:jarSmokeTest`: 실제 `bootJar` 산출물을 자식 프로세스로 띄우는 기동·종료 시험(아래 "패키지 JAR 기동·종료 시험"). `:boot:bootJar`에 의존하므로 항상 새 JAR로 돈다.
- `python/`을 바꾼 PR은 위 대신(또는 함께) `python/README.md`의 "로컬 시험" 명령을 저장소 루트에서 실행한다.

## F22 본인 쪽지 단건 조회

F22는 기존 `GET /api/v1/notes/{id}`만 편입한다. caller 활성 확인 뒤 Clock1회→본인 mailbox+strict expiry 단건→nullable counterpart single→active INBOX에만 기존 양방향 block 조회를 수행한다. 응답은 원본 body/닉네임·nullable ID/context/readAt·Instant의 정확10필드이고 차단된 쪽지도200/canReply=false다. read_at/favorite·원문·요청/차단 상태를 쓰지 않으며 SSE·cleanup·새 User API·외곽 TX는 추가하지 않는다. UUID 변환·정적 경로 우선순위는 Boot/MVC가 책임진다. F22 당시 기본거부였던 exact `/stream`은 F26으로 인계하고, `/stream/extra` 인접 경로 거부는 유지한다.

F22가 추가로 읽는 열은 recipient_id/body/context_type/context_id와 favorite의 SELECT5열뿐이며 새 DDL·표·인덱스·스키마·writer/sequence 권한은0이다.

## F23 본인 쪽지 목록 조회

기존 `GET /api/v1/notes`의 받은/보낸 쪽지와 즐겨찾기 페이지를 편입한다. page0..10000 → 정확한 INBOX/SENT → 현재 회원 → Clock1회 → count → 최대10행 목록 → nonnull 상대의 최초 순 distinct batch1회 → active INBOX 행마다 양방향 차단 순서다. count0이어도 목록을 읽고 요청 page를 보존한다. 정렬은 mailbox.created_at와 PostgreSQL UUID DESC, 응답 createdAt은 note.created_at이다. Page는 정확5필드, 행은 기존 단건과 같은10필드이며 raw body/닉네임·nullable ID/readAt/context·Instant를 그대로 돌려준다. 조회로 읽음·즐겨찾기·쪽지·차단을 쓰지 않는다.

기존 F22 single reader SAM·단건 조립·User single/batch·block reader·Clock·단일 pool을 유지하고 목록 전용 두 SQL adapter만 추가한다. count와 list는 각각 기존 RO3초 TX를 반환한 뒤 다음 단계로 간다. 외곽 TX·재시도·pair cache를 추가하지 않으므로 count/list 사이 원래 경쟁 창을 닫았다고 주장하지 않는다. 최대10 active INBOX의 source상 DB 호출 상한은14이다. 기존 exact root GET 거부2항목만 새 목록 계약에 인계했다. F26에 인계한 exact stream/읽음 PUT 외 나머지 쓰기·ADMIN·단건/static 경계와 stream/extra 인접 거부는 유지한다.

F23은 기존 notes8/mailboxes6·blocks3 reader 권한만 쓰며 새 DDL·표·권한·writer·sequence는0이다.

## F24 본인 쪽지 차단 해제

기존 `DELETE /api/v1/notes/blocks/{member}`만 편입한다. current와 target 두 ID가 양수인지 TX 전에 확인하고, 어느 쪽이든0/음수면401이다. TX15초 안에서 sorted distinct ID마다 INSERT 뒤 같은 row FOR UPDATE를 수행한 다음 현재 회원 활성 조회1회와 outgoing DELETE1회를 실행한다. self·없는 차단·탈퇴/부재 target도 성공할 수 있으며 target User 조회는 없다. 성공은 빈200/no-store/no-cookie다. 없는 target의 member 행 INSERT는 허용된 변경이므로 전체 DB 무변경으로 표현하지 않는다. current 검증 실패 때 앞선 INSERT는 rollback되어야 한다.

application의 좁은 동기 callback을 adapter가 잠금 뒤 정확1회 호출한다. User 정책은 application에 두며 callback 저장·비동기·재시도·예외 삼킴은 없다. 기존 writeJdbc와 writeTransactionManager를 재사용하고 이 operation 전용15초 TransactionTemplate만 조립한다. 같은 datasource의 활성 TX 잔여시간이 기존 queryTimeout5를 덮으므로 새 JdbcTemplate·pool·TM를 추가하지 않고 기존 공통10초 설정도 바꾸지 않는다. Clock·SSE·afterCommit·Outbox·새 User API는 없다. 원본 쓰기 오류 정책대로 typed User는 회원503, DAO만 저장소503이며 구체 TX3타입을 포함한 다른 Runtime은 일반503이다. scoped 로그에는 타입만 남기고 throwable·SQL·회원값을 전달하지 않는다.

F24가 추가로 쓰는 권한은 기존 members의 SELECT/INSERT/UPDATE(member_id), blocks의 SELECT(member_id,blocked_id)와 DELETE뿐이며 새 DDL·표·스키마·sequence 권한은0이다.

## F25 본인 outgoing 쪽지 차단 생성 준비

기존 `PUT /api/v1/notes/blocks/{member}` 한 계약의 private 준비다. 두 ID 양수 검증 → positive SELF 거부 → writer TX15 → sorted distinct member마다 INSERT 다음 같은 row FOR UPDATE → current active → target active → Clock.instant 정확1회 → outgoing INSERT ON CONFLICT DO NOTHING → commit 순서를 보존한다. ID0/음수는401, 양수 self는 TX/User/Clock 전에400이며 current 실패는 target/Clock, target 실패는 Clock를 호출하지 않는다. 중복 요청도 잠금·User2회·Clock1회를 수행하고 빈200/no-store/no-cookie로 끝나지만 기존 created_at은 갱신하지 않는다. 없는 target도 성공하는 F24 해제와 달리 F25 target missing/pending은403이며 앞선 member INSERT는 rollback되어야 한다.

application `CreateNoteBlock`이 두 회원 활성 정책과 Clock를 소유하고, `NoteBlockCreation`의 좁은 동기 Supplier가 잠금 뒤 그 순서를 실행한다. 기존 F24 Runnable SAM이나 User single/batch API는 바꾸지 않는다. Boot의4bean은 기존 writeJdbc/writerTM 위 operation 전용 TT15와 기존 readClock를 재사용한다. 공통 TT10/JDBC5·User RO3·단일 pool을 유지하며 새 datasource/TM/Clock·외곽 TX·SSE·afterCommit·Outbox는 없다. typed User는 회원503, DAO만 저장소503, 구체 TX3·usage·Clock/기타 Runtime은 일반503이다. 실제 commit이 DAO로 번역되면 실제 타입대로 처리하며 phase만으로 분류하지 않는다. scoped 로그에는 타입만 남긴다.

F25가 추가로 쓰는 권한은 blocks의 INSERT(member_id,blocked_id,created_at)뿐이다. 기존 members SELECT/INSERT/UPDATE(member_id)와 blocks SELECT두ID/DELETE, reader User7/pending1/blocks3/notes8/mailboxes6은 그대로 쓰며, writer created_at SELECT/UPDATE·새 schema/표/업무 DDL/index/sequence 권한은0이다.

기존 거부 중 PUT target403은 새 성공 계약으로, PUT status403은 새 long binding400으로 옮긴다. F20 PUT blocks root403·stream/extra 기본403·나머지 미편입 mutation은 보존한다. exact stream과 읽음 PUT의 정상 계약은 F26에서 별도로 보호한다.

## F26 본인 쪽지 읽음과 본인 SSE resync 준비

기존 `PUT /api/v1/notes/{id}/read`와 `GET /api/v1/notes/stream?clientId={UUID}`를 같은 사용자 결과로 준비한다. 본인 읽음 commit 뒤 본인의 다른 연결에 resync를 보내고, 기존 알림/단건/목록 REST를 다시 조회해 readAt·unreadCount를 확인한다. rollback·commit 실패에는 알림을 보내지 않는다. 기존 send/withdraw가 다른 JVM에서 실행되면 이 registry에 자동으로 전달되지 않으므로 전체 실시간 알림·Gateway 전환 완료로 표현하지 않는다.

읽음은 TX15 → current member INSERT/FOR UPDATE → callback 안 requireId/active → 본인 mailbox JOIN·strict expiry 조회(clock1) → note lock → 동일 소유/만료 재조회(clock2) → INBOX `read_at=COALESCE(read_at,?)` UPDATE(clock3) → afterCommit resync 순서다. ID 검증을 TX 앞으로 옮기지 않아 비양수 direct call의 member CHECK DAO 선행과 HTTP JWT 양수 guard를 구분한다. 반복 INBOX도 UPDATE/세 번째 시각을 수행하지만 첫 readAt을 보존하고, SENT는 영향0으로 성공·resync한다. 세 번째 expiry 검사나 상대/차단 조회를 추가하지 않는다. app의 좁은 MissingOwnedNote만404로 바꾸며 typed User는 회원503, DAO는 저장소503, 그 밖의 구체 TX/Runtime은 일반503이다. 실제 commit에서 DAO로 번역되면 실제 타입을 따른다. 등록 실패·SQL·TX 예외는 전파하고, afterCommit 내부 hints의 Runtime만 타입 로그 후 성공을 유지한다.

Boot의 정확한 두 matcher를 같은 인증 filter/인가에 넣고 기존 ADMIN 우선·다른 경로 기본거부를 보존한다. 한 번 검증한 같은 JWT Claims의 exp를 JDK Instant details로 전달하며 헤더의 사용자/만료값을 신뢰하지 않는다. 기존 REST의 exp 선택 정책은 보존하고 SSE만 exp를 필수로 한다. 같은 요청의 ASYNC dispatch는 request-attribute context를 복원하며 새 연결은 새 JWT를 검증한다. 세션·raw token 저장·일반 REST context save·전역 ASYNC permitAll은 추가하지 않는다. 같은 registry가 읽음 hints를 구현하고 기존 readClock/writeJdbc/writerTM의 operation 전용 TT15를 조립한다. User API·단일 pool/두 TM·공통 TT10/JDBC5는 유지한다.

registry의 원문14개 선택 메서드와 writeLock 안 expiry 재확인·교체 identity를 보존한다. 연결 TTL은 JWT 잔여와5분 중 작은 값, heartbeat15초, 회원 logical3/pending replacement3·전체 physical500, write2/queue256·completion2/queue500이다. 종료는 서버의 열린 SSE 대기보다 먼저 실행하며 공유 monotonic5초 예산 안에서 소유 executor 종료를 확인한다. `isRunning=false`·daemon·pool 종료만으로 SSE 자원 종료를 선언하지 않는다. 원본 scheduler·expire cleanup·send/withdraw API는 등록하지 않는다.

F26이 추가로 쓰는 권한은 notes의 SELECT(note_id,expires_at)/UPDATE(note_id)와 mailboxes의 SELECT(member_id,note_id,direction,read_at)/UPDATE(read_at)뿐이다. note_id UPDATE 권한은 FOR UPDATE 잠금용이며 실제 note_id 갱신 SQL을 추가하지 않는다. 새 DDL/표/schema/index/sequence는0이다.

전체 결정·계획·문제 해결 기록은 Obsidian `Projects/pawbridge`의 F2·F3·F4·F5·F6·F7·F8·F9·F10·F11·F12·F13·F14·F15·F16·F17·F18·F19·F20·F21·F22·F23·F24·F25·F26 계획과 모듈형 모놀리스 전환 노트를 따른다.

## F27 본인 쪽지 즐겨찾기 값 변경 준비

기존 `PUT /api/v1/notes/{id}/favorite`를 요청한 boolean 값으로 설정하고 commit 뒤 본인 SSE resync와 기존 단건/목록의 favorite·favorites 필터를 연결한다. INBOX/SENT 모두 허용하며 동일 값 반복도 UPDATE·commit·resync를 수행한다. TX15 → member INSERT/lock → current active → find(clock1) → note lock → find(clock2) → favorite UPDATE → hook 등록/commit 순서를 보존한다. 시각은2회로 읽음의 세 번째 Clock 호출을 복제하지 않는다. read_at·unread·상대 mailbox·created_at은 바꾸지 않으며 counterpart/block 조회0이다. 영향 행수0로 새404를 만들지 않고 두 ownership 조회의 엄격한 만료 경계가 부재를 판단한다.

Community의 명시적인 app policy/좁은 port/JDBC/scoped REST 계약과 Boot의 exact PUT matcher·기존 writeJdbc/writerTM/privateTT15/readClock/NoteChangeHints를 조립한다. 새 User API·pool/TM/Clock·SSE registry·범용 mutate 프레임워크는 없다. 원문에서처럼 hook 안 실제 hint Runtime만 타입명 로그 뒤 성공 처리하고 SQL·등록·commit 오류는 전파한다. 인증은 binder보다 먼저이며 기존 일반 REST의 exp 선택 정책·ADMIN/default deny를 유지한다. primitive boolean body의 생략/null/잘못된 JSON 등은 원문 actual MVC 관측 후 비교 provider를 확정한다.

F27이 추가로 쓰는 권한은 기존 mailbox writer의 `UPDATE(favorite)` 한 열뿐이다. reader와 F26 writer SELECT/잠금/read_at·기존 blocks 권한은 유지하고 새 DDL/표/index/schema/sequence는0이다.

## F28 본인 쪽지 메일함 삭제 준비

기존 DELETE `/api/v1/notes/{id}`는 TX15/member INSERT·잠금/current active/find(clock1)/note 잠금/find(clock2) 뒤 본인 mailbox를 지우고, 남은 mailbox가 없을 때만 원본 note를 지운다. 상대 mailbox가 있으면 원본과 상대 row를 보존하고, 마지막 mailbox 삭제 때 원문의 FK로 request.note_id와 자식 note.reply_to가 NULL이 된다. 반복 삭제는404/hint0이며 성공은빈200/no-store/noCookie, afterCommit 실제 hints Runtime만 타입 로그 후 성공을 유지한다. 상대 상태·차단 조회·새Clock/트랜잭션·범용 mutate 추출은 추가하지 않는다.

새 exact DELETE matcher는 원문처럼 `/notes/blocks`·`/notes/notifications`도 인증 뒤 UUID400으로 보낸다. 예약어403 예외를 새로 만들지 않는다. 기존 ADMIN/REST/JWT·read/favorite/stream과 operation 전용TT15/두pool을 유지한다.

F28이 추가로 쓰는 권한은 기존 mailbox/note DELETE 두 권한뿐이며 requests 표의 앱 reader/writer 권한은0이다. 운영 Flyway/DDL/ACL은 변경하지 않는다.

## F29 본인 쪽지 발송 준비

기존 POST `/api/v1/notes`의 입력6필드·strip/hash·멱등 요청키·분/Seoul일 한도·답장·POST/REPORT 문맥을 보존한다. TX15의 정렬 회원 잠금과 현재/수신자 User 조회, 기존 요청의 gone/conflict/replay, 양방향 차단과 한도/문맥 검사 뒤 새 UUID1개로 note1/mailbox2/request1을 저장한다. commit 뒤 수신자에게만 기존 알림7필드 note 이벤트를 전달하고, 같은 키 재전송은 같은 receipt를 반환하며 새 행·알림을 만들지 않는다. 기존 조회·resync·registry 예산을 바꾸지 않으며 발송 SAM만 같은 registry에 연결한다. F27·F28 메일함 상태 관리와 별도 기능 계약이다.

F29가 추가로 쓰는 writer 권한은 notes의 sender SELECT/발송9열 INSERT, mailbox5열 INSERT, requests5열 SELECT/INSERT, reports의 report_id/author_id/deleted_at SELECT로 제한한다. 운영 DDL·migration·reader report ACL·앱 sequence ACL은 변경하지 않는다.

### 쪽지 만료 정리 후보

`pawbridge.jobs.cleanup-enabled=true`에서만 기존 writer/TM/Clock으로 쪽지 만료 정리를 등록한다.
기본값과 false는 off이며 기존 Community의 공통 정리 설정을 끄거나 운영 실행 소유권을 이전하지 않는다.
최초 지연과 이전 완료 후 지연은 각각 5분이다. 각 SQL의 삭제 키를 `MATERIALIZED` CTE로 한 번만 선택해 최대300건을 처리한다.
실제 원문 PG 비교에서301개 쪽지가 삭제된 결함을 확인했으므로, 원문 SQL 자체의 동등성 대신 의도한300건 상한을 보완한 차이다.
원문 pin/SQL은 비교용으로 보존하고 그 사례의 관측을 상한 검증 성공으로 세지 않는다. 후보는 정확한 oldest300·잔류1·18표 전체 변경 범위를 별도로 검증한다.
조건부 ordinary scheduler는 새 예약을 막고 기존 phase 10초 대기 뒤 destroy의 지역 대기 5초를 사용한다.
이는 전체 15초 종료나 JDBC interrupt 보장이 아니다. TX 잔여 timeout은 JDBC query timeout보다 우선할 수 있다.
이 정리는 실행 JAR의 강제 취소나 운영 예약 인수 완료를 보장하지 않는다.

## 관리자 회원 삭제와 Community 정리

기존 ADMIN DELETE `/api/v1/admin/users/{userId}`를 User의 pending 커밋 → TX 밖 Community 공개 호출 → User의 별도 최종 TX로 연결한다. Boot가 콜백을 주입하며 User에서 Community 모듈을 역참조하지 않는다. 기존 단일 pool과 writer TM을 재사용하고 각 작업의 TT15 및 기존 관리자 JWT 인가를 유지한다.

Community 정리는 쪽지 member 잠금과 pending 재검증 뒤 본인 mailbox·차단·발신 요청의 FK 효과, 원본 context/요청 hash 익명화, 영향받은 고아 note 삭제를 수행한다. 설치된 채팅 표의 membership·발신 요청 삭제와 메시지/room 회원 ID 익명화도 같은 TX에서 처리한다. 실패하면 이 Community TX 전체를 원복한다. 커밋 후에만 같은 쪽지 registry의 본인 연결을 닫고 나머지 연결에 resync한다.

첫 인계는 명시적 `pawbridge.chat.enabled=false` 모드다. 미지정 시 기존 `MEMBER_CHAT_ENABLED` 별칭과 false 기본값을 따르며 canonical 속성이 우선한다. false라도 설치된 채팅 데이터 정리는 생략하지 않는다. 후속 회원 채팅 편입에서는 true의 Redis WITHDRAWN 생산·소비와 연결 종료 배선도 조립했다. 실제 Redis·WebSocket과 회원 삭제 연계의 통합 검증 전까지 운영 삭제 인계 완료를 뜻하지 않는다.

Community 실패는 pending을 보존하고503을 반환한다. Community가 커밋된 뒤 User 최종 TX가 실패하면500이며 pending 상태에서 권한 있는 재시도로 마무리한다. 최종 User 조회 시 이미 부재하면 원문 deleteById처럼 성공하고, 조회 후 동시 삭제로 DELETE가0행이면 optimistic 실패로 최종 TX를 원복한다. 모든 단계를 감싸는 새 outer TX나 추가 행 잠금은 없다.

관리자 삭제는 Community rollback503 → User rollback500 → 권한 있는 retry200으로 이어지고, 본인 SSE는 종료되며 생존자에게 resync가 전달되고 원본 본문은 보존된다. JWT 없는401·위조 ADMIN 거부403·pending의 정상 JWT 재연결403도 구분한다.

## 관리자 회원 상세 조회

ADMIN GET `/api/v1/admin/users/{userId}`는 기존 `UserProfileQuery`와 같은 read-only 조회 bean을 소비한다. 기존 회원7필드와 성공200/부재400/예외500 응답을 재사용하고 새 SQL·열 권한·pool·TM·schema를 추가하지 않는다. pending 행을 임의로 제외하거나 비밀번호 등 다른 필드를 반환하지 않는다.

서명된 ADMIN 권한을 경로 바인딩/조회보다 먼저 확인하며 X-User 헤더나 query의 userId로 대상을 바꾸지 않는다. 아직 편입하지 않은 `/users/search`는 이 단건 matcher에서 제외하고 목록·검색·미편입 이웃 경로의 기본 거부를 유지한다. 기존 관리자 DELETE는 그대로다.

이 변화의 가장 가까운 보호는 실제 MVC의 JWT·대상 바인딩·7필드/오류·인접 경로와 명시 조립 시험이다. 기존 F12의 변경 없는7열 SQL/실제 PG 근거를 재사용한다.

## 관리자 가입통계

ADMIN GET `/api/v1/admin/users/stats/daily-signups?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD`는 nullable UTC 저장 `created_at`에 9시간을 더한 날짜를 양끝 포함 집계하고 정렬한다. 빈 날짜를 채우지 않으며 역순 구간은 원문처럼 빈 결과다. GET `/api/v1/admin/users/stats/signup-periods`는 Seoul 오늘을 한 번 구하고 한 read-only 트랜잭션 안에서 오늘·최근7일·최근30일·이번달을 네 번 조회한 뒤 각 구간의 빠진 날을0으로 채운다. 네 결과가 하나의 snapshot이라고 가정하지 않는다.

기존 reader/Clock/열 권한·응답 envelope·ADMIN 선행 인가를 재사용하며 새 schema/권한/pool은 없다. 필수 날짜 누락400과 원문의 날짜 변환 Runtime500을 구분한다.

## 관리자 회원 수정

ADMIN PUT `/api/v1/admin/users/{userId}`는 기존 writer의 한 TX에서 회원을 `FOR UPDATE`로 읽고, 보호소 승인 필요 조건 → 닉네임 중복 → 변경 순서를 유지한다. 관리자 전용 nullable 닉네임 Pattern(2~10자 영문·숫자·한글), 세 역할과 careRegNo의 원문 제약을 보존한다. 같은 raw 닉네임은 중복 조회를 하지 않고, 실제 dirty가 있을 때만 감사시각과 UPDATE를 수행한다. 선행 중복은409, SQL unique 경합과 commit 실패는500이며 새로운 retry나 역할 승격 정책을 추가하지 않는다. 기존 writer/TM·TT15를 재사용하고 무제한 대기까지 원문과 같다고 주장하지 않는다.

## 방문 식별·집계와 관리자 기간 통계

`pawbridge.visits.collection-enabled`는 기본 false다. 기존 `pawbridge.visits.enabled`가 true인 단독 조회는 서명키나 쓰기 lifecycle을 요구하지 않는다. collection=true는 read=true와 유효한 `pawbridge.visits.signing-key`를 요구하며 모순된 설정은 기동에 실패한다.

활성화하면 공개 POST `/api/v1/visits/identity`, `/api/v1/visits/today`와 ADMIN GET `/api/v1/admin/visits/stats/period`를 조립한다. Origin·bot·중복 cookie 검사, host cookie/HMAC·서울 일자 구분과 원본 204/409 응답을 유지한다. 집계는 기존 writer/TM의 10초 트랜잭션으로 날짜별 공유 잠금 후 날짜를 다시 확인하고 식별자 중복 제거와 일별 합계를 함께 반영한다. 기간 조회는 수집 시작 이전 날짜를 만들지 않고 수집된 날짜의 빈 합계만0으로 채운다.

단일 Spring lifecycle이 준비 완료 때 수집 시작을 기록하고 60초 간격으로 정리한다. 서울00:05 이전에는 지우지 않으며 날짜별 배타 잠금·개별 커밋으로 오래된 식별자만 제거한다. 일별 합계와 수집 시작 시각은 보존한다. 기존 writer/Clock을 재사용하고 scheduler는 후보 내부에만 조립한다. 운영의 예약 작업을 인계하거나 실행한 변경이 아니다.

## refresh 회전과 logout

`pawbridge.auth.sessions-enabled`는 기본 false다. true일 때만 POST `/api/v1/auth/refresh`와 POST `/api/v1/auth/logout`을 조립한다. 두 경로 모두 access token 없이 받으며(permitAll), 컨트롤러가 `Origin`이 CORS 설정 목록의 정확한 값(`*` 패턴은 제외)이고 `Content-Type`이 `application/json`일 때만 처리한다. 그 밖에는 403이다. 다른 사이트의 폼이나 단순 요청은 이 두 조건을 만들 수 없고, 쿠키도 SameSite=Lax라 다른 사이트의 POST에는 실리지 않는다. Strict 대신 Lax를 쓰는 이유는 일부 WebKit·인앱 브라우저가 다른 사이트에서 돌아오는 소셜 로그인 callback에서 Strict 쿠키를 버리기 때문이다. 입력 TTL은 `jwt.access-token-expiration`(access), `jwt.refresh-token-expiration`(세션 유휴 만료), `pawbridge.auth.session-absolute-days`(절대 만료, 기본 30일)다.

refresh 토큰은 32바이트 SecureRandom 값을 패딩 없는 base64url로 만든 불투명 값이다. DB `pawbridge_user.auth_sessions`(user-service V4)에는 소문자 hex SHA-256만 저장한다. 토큰은 `__Secure-pb_refresh` 쿠키(Domain 없음, `Path=/api/v1/auth`, HttpOnly, Secure, SameSite=Lax, Max-Age=유휴·절대 만료 중 이른 쪽까지 남은 초)로만 오가며, 응답 본문과 redirect 주소에는 넣지 않는다. 구 경로 `/api/auth/refresh`는 쿠키 Path와 맞지 않아 쿠키가 전송되지 않는다.

refresh는 쿠키만 읽는다(본문 무시). 한 UPDATE로 `token_hash=:old`이고 폐기·만료되지 않은 세션만 새 해시로 바꾸며 `rotated_at`, `last_used_at`, `expires_at=LEAST(now+유휴, absolute_expires_at)`를 갱신하고, 물러난 해시는 같은 트랜잭션에서 `auth_session_token_history`에 남긴다. 시각은 모두 DB의 `CURRENT_TIMESTAMP`다. 현재 토큰이 아니면 이력에서 찾는다. 어느 세대든 60초보다 오래전에 물러난 토큰이면 재사용으로 보고 그 세션만 폐기·커밋한 뒤 401과 쿠키 삭제를 반환한다. 60초 안에 물러난 토큰이면서 가장 최근 회전에서 물러났고 후속 토큰이 한 번도 쓰이지 않았으면(`last_used_at = rotated_at`) 회전 응답이 유실된 것으로 보고, 세션 행을 잠근 뒤 조건을 다시 확인해 한 번 더 회전하고 쿠키를 준다. 동시에 온 다른 요청은 잠금 뒤 조건이 맞지 않아 access token만 받는다. 이력에 없으면 401과 쿠키 삭제다. access token claim은 매번 `users`에서 다시 읽는다. logout은 쿠키의 세션을 폐기하고 항상 쿠키를 지우며 같은 요청을 반복해도 200이다.

로그인(LOCAL·소셜)마다 회원 행을 `FOR UPDATE`로 잠근 같은 트랜잭션에서 세션을 새로 만들고, 활성 세션이 5개를 넘으면 `last_used_at`이 오래된 순으로 폐기한다. 관리자가 역할을 낮추면(ADMIN→SHELTER/USER, SHELTER→USER), 비밀번호를 변경·재설정하면, 회원 삭제를 시작하면(`contact_deletions` 표시) 각각 같은 트랜잭션에서 그 회원의 세션을 모두 폐기한다. 올리는 변경은 폐기하지 않는다. 회원 행 삭제는 FK `ON DELETE CASCADE`로 세션과 이력을 지운다. `pawbridge.jobs.cleanup-enabled=true`이면 1시간마다 만료됐거나 폐기 후 하루가 지난 세션을 1000행씩 지운다. 토큰 값과 해시는 로그에 남기지 않는다. 코드는 `refresh_tokens`를 더 이상 참조하지 않으며(회원 삭제의 마지막 `DELETE`도 제거), 테이블은 `V5__drop_refresh_tokens.sql`이 지운다. 운영에는 이 참조가 없는 코드를 먼저 배포한 뒤 V5를 적용한다.

### DB 계약 시험(`contractTest`)

`boot/src/contractTest/kotlin`의 시험은 앱 전체를 `@SpringBootTest(webEnvironment = RANDOM_PORT)`(실제 Tomcat, `java.net.http.HttpClient`)로 띄우고 Testcontainers PostgreSQL에 비소유 역할 `pawbridge_app`으로 접속한다. JVM마다 컨테이너 하나(이미지는 `-PcontractPostgresImage=...`·`pawbridge.contract.postgres-image`·`PAWBRIDGE_CONTRACT_POSTGRES_IMAGE`로 바꾸고, 기본은 packaged smoke와 같은 digest 고정 `postgres:17.11-bookworm`)와 Spring 컨텍스트 하나를 `ContractTestBase`로 모든 시험이 공유한다. 스키마는 운영 DB에 수동으로 적용하는 마이그레이션 원본 `backend-app/db/postgresql/<모듈>/`(user·community·animal·store)을 그대로 읽어 스키마별 `search_path`로 적용하고, 앱 역할의 열 단위 권한은 `src/contractTest/resources/schema/app-grants.sql`이 정한다. 운영 DDL과 시험 스키마가 같은 파일이라 둘이 어긋날 수 없다. `payment` 스키마는 아직 계약 시험 DB에 적용하지 않는다. 권한 회수 시험은 `ContractPostgres.withRevoked`로 슈퍼유저 연결에서 REVOKE하고 `finally`에서 GRANT한다.

`./gradlew :boot:contractTest`는 JVM 시간대 UTC로 전체(`contractTestUtc`)를, 이어서 `@Tag("timezone-sensitive")` 시험만 Asia/Seoul로(`contractTestSeoul`) 실행한다. `check`에 포함되지 않으며(Docker 필요) 시험이 없거나 건너뛴 시험이 있으면 실패한다. 로컬 Docker Desktop은 `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`과 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`이 필요하다. Testcontainers 1.20은 Docker Engine 29가 거부하는 API 1.32를 요청하므로 시험 JVM에 `api.version=1.44`를 주며 `-PdockerApiVersion=...`으로 바꿀 수 있다.

관리자 회원 삭제(`MemberDeleteContractTest`, 옛 F31 스모크)는 대기 표식·세션 폐기 → 쪽지·채팅 정리 → 계정 삭제의 세 커밋을 실제 DB에서 확인한다. 관리자 전용 접근과 인증 거부의 무부작용, 연쇄 정리와 상대 데이터 보존, 채팅 정리 권한 회수(503)·계정 삭제 권한 회수(500) 뒤 부분 커밋 상태와 복구 후 재시도, 스트림 종료·resync, S3 무접촉을 단언한다.

refresh 세션 계약(`AuthSessionContractTest`)은 원문과 비교하지 않는다. 회전·이력 기록·유실 응답 복구·동시 grace 요청·두 세대 전 토큰 재사용·만료·5개 제한·logout·전체 폐기·정리·권한 회수·회원 삭제 연쇄를 실제 DB에서 확인하고, 쿠키 refresh HTTP 요청 한 건으로 Tomcat 경로를 확인한다.

### 패키지 JAR 기동·종료 시험(`jarSmokeTest`)

`PackagedJar*ContractTest`는 `contractTest` 소스 세트에 두되 `@Tag("packaged-jar")`로 구분한다. `contractTestUtc`는 이 태그를 제외하고, `:boot:jarSmokeTest`가 이 태그만 `:boot:bootJar` 산출물로 실행한다. 별도 작업으로 둔 이유는 일반 계약 시험이 JAR(약 120MB)를 만들 필요가 없고, JAR 시험은 `@SpringBootTest` 컨텍스트 없이 `ProcessBuilder`로 `java -jar`를 실행하기 때문이다. 같은 Testcontainers PostgreSQL(앱 역할)과 `ContractFakeS3`를 쓰며 값은 모두 합성이다. 포트는 모두 127.0.0.1의 빈 포트이고 메일은 아무도 듣지 않는 루프백, 스케줄러는 전부 끈다.

- `PackagedJarRunContractTest`: JAR를 클래스당 한 번 띄운다. 관리 포트 health·liveness·readiness 200과 `/actuator/prometheus` 200, 공개 포트의 `/actuator/prometheus` 403·`/actuator/health` 404, 공개 단건 조회, 인증된 글 작성(파일은 가짜 S3로, 검색 투영 `nori-9.12.3-v1` 생성), 조사가 붙은 제목을 어간으로 찾는 검색(JAR 안 Nori 사전), 틀린 토큰의 401과 무부작용, 로그 크기(1MiB 미만)와 비밀 미노출, 마지막으로 SIGTERM 정상 종료(종료 코드 0 또는 143, `Graceful shutdown complete`, Hikari `pawbridge-db` 풀 종료 1회, `pg_stat_activity`의 앱 역할 연결 0). 종료 시험이 프로세스를 끝내므로 `@Order`로 마지막에 실행한다.
- `PackagedJarRefusalContractTest`: 필수 설정 10개(`PAWBRIDGE_DB_URL/USER/PASSWORD`, `PAWBRIDGE_JWT_SECRET`, `PAWBRIDGE_STORAGE_ENDPOINT/BUCKET/PUBLIC_BASE_URL/ACCESS_KEY_ID/SECRET_ACCESS_KEY/REGION`)을 하나씩 뺀 짧은 시작과 값이 잘못된 시작(자격 증명이 든 DB URL, 풀 크기 51, 허용 안 되는 endpoint, 빈 서명 키)을 네 개씩 동시에 돌려, 종료 코드가 0이 아니고 출력에 설정 이름만 있고 값은 없으며 리스너·DB 연결·저장소 호출이 없음을 확인한다.

## 관리자 게시글 기간·오늘 통계

ADMIN GET `/api/v1/admin/posts/stats/period`와 `/api/v1/admin/posts/stats/today`는 기존 reader와 Boot의 명시적 조립을 재사용한다. 기간 조회는 필수 ISO 날짜·최대366일·서울 날짜 경계와 삭제 제외를 보존하고, 이전 날 비교와 두 집계를 같은 읽기 전용 REPEATABLE_READ 트랜잭션에서 수행한다. 일자별 빈 구간을 새로 채우거나 게시판 결과의 정렬 순서를 만들지 않는다. 오늘 조회는 원문의 JVM 기본 날짜 경계를 사용하며 기간 조회와 같은 기준이라고 단정하지 않는다. 새 풀·스키마·운영 권한은 추가하지 않는다.

## 관리자 게시글 조회·수정·삭제

ADMIN GET `/api/v1/admin/posts`, GET/PUT/DELETE `/api/v1/admin/posts/{postId}`를 한 관리 흐름으로 조립한다. 목록은 원문의 Pageable/PageImpl 계약을 같은 Spring Data Commons 3.4.11로 재사용하고 application에는 순수 페이지 계약만 둔다. 기본20·최대2000·fallback·다중 정렬·전체 건수와 partial-page count 생략, 삭제 제외·9필드·이미지 JSON·닉네임을 보존한다. 단건은 기존 ReadPost를 재사용한다. 원문의 `/read`, `/search` 같은 비숫자 단건 값도 매핑에 포함되며 별도 새 API나 예외 경로를 만들지 않는다.

관리자 PUT/DELETE는 명시적인 AsAdmin 진입점으로 작성자 검사를 생략한다. 개인 수정·삭제의 소유권 검사는 유지하며 실제 게시글 작성자 ID를 기존 writer·닉네임·payload에 사용한다. 기존 JDBC/locking SQL·TX·R2·Outbox·검색 정리를 재사용한다. 첨부의 부분 실패를 자동 보상하거나 외부 I/O와 DB를 새 단일 트랜잭션으로 묶지 않는다. 새 업무표·SQL·권한·풀은 추가하지 않는다.

## 회원 채팅의 REST·실시간 전송 편입

기존 회원 채팅의 업무 메서드와 20개 SQL을 JDBC 구현으로 재사용하고, User의 명시 조회 API와 기존 차단·쪽지·탈퇴 정리에 연결한다. REST는 서명 검증된 사용자와 JWT 만료 시각으로만 본인을 결정한다. 익명 WebSocket handshake 뒤 STOMP CONNECT에서 30초짜리 일회용 Redis ticket을 원자적으로 소비하며, 인증 전 SEND·SUBSCRIBE와 다른 사용자 큐 접근을 거부한다. 기존 Origin·frame·세션 수·만료·중복 전송·본문 보관 정책을 유지한다.

`pawbridge.chat.enabled`는 기본 false다. false에서는 채팅 Redis·소켓·유지관리 scheduler를 만들지 않는다. true일 때 명시 Redis 연결과 namespace를 요구한다. `pawbridge.jobs.cleanup-enabled=false`이면 true 채팅에서도 보관 기한 정리는 실행하지 않으며, 세션 lease 관리는 별도 유지한다. 기존 설치 데이터의 회원 탈퇴 정리는 이 옵션과 별개로 기존 흐름에서 한 번만 처리한다. DB 커밋 뒤 WITHDRAWN 신호로 본인 연결을 닫고 살아 있는 연결에는 재조회 신호를 보낸다.

종료 시 로컬 세션을 먼저 분리해 동기·지연 close callback의 중복 Redis/User I/O를 막고 기존 lease 만료를 사용한다. 이는 모의 callback 검증이며 실제 JVM·소켓 종료 시간의 증거는 아니다. 신규·관련 단위/HTTP·인가·프로토콜·조립과 계층 검사는 수행했으나 실제 Redis Lua/리스너·WebSocket·채팅 DB 통합은 아직 미실행이다.

## 이메일 로그인(기존 계정만)

회원은 Google·Naver로 가입하고 로그인한다. 이메일 로그인 `POST /api/v1/auth/login`은 이미 있는 LOCAL 계정(운영 시험 계정)만 쓰도록 남겨 두었다. 가입·인증 코드·비밀번호 재설정·변경은 없다.

`pawbridge.auth.local-enabled`(환경변수 `PAWBRIDGE_AUTH_LOCAL_ENABLED`)는 기본 false다. true면 `pawbridge.auth.sessions-enabled=true`를 요구하고, 이 정확한 경로에만 별도 보안 체인을 만든다. 요청은 JSON `email`·`password`이며 이메일은 대소문자를 무시하고 BCrypt로 확인한다. 200 본문은 userId·email·name·role·careRegNo·accessToken이고 refresh token은 refresh 쿠키로만 준다. 실패는 401이다. 실패 횟수 제한은 없다.

## Naver 로그인 추가

`pawbridge.auth.naver-enabled`(환경변수 `PAWBRIDGE_AUTH_NAVER_ENABLED`)는 기본 false다. true면 `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`, `NAVER_REDIRECT_URI`(경로 `/login/oauth2/code/naver`)를 요구하고, 없거나 경로가 다르면 시작할 때 실패한다. Google과 Naver는 각자 켜고 끌 수 있으며, 보안 체인은 켜진 공급자의 authorization·callback 경로만 받는다.

Naver는 Spring 기본 제공자가 아니어서 authorize·token·user-info 주소를 직접 등록한다. user-info 응답은 `response` 안에 `id`·`email`·`name`을 담는다. 회원은 `provider=NAVER`와 Naver `id`(애플리케이션마다 다른 값)로 찾는다. 연락처 이메일은 바뀔 수 있고 소유 증명이 아니므로 이메일로는 찾거나 연결하지 않는다. 같은 이메일의 LOCAL 회원이 있으면 Google과 같은 문구로 거절하고, 같은 이메일의 다른 Naver ID 회원이 있으면 별도 문구로 거절한다. 회원 생성·닉네임·세션 생성은 Google과 같은 `JdbcSocialLogin` 경로를 쓴다.

클래스 이름은 공급자 공용 `Social*`로 바꿨다(Google 동작·설정 키는 그대로). 실제 Naver 서버 호출과 운영 키 적용은 이 변경에 포함하지 않는다.

## 기존 Google 로그인 편입

`pawbridge.auth.google-enabled`는 기본 false다. true에서는 auth session 저장소(`pawbridge.auth.sessions-enabled=true`)와 명시 Google client/secret/callback·frontend 설정을 요구한다. 정확한 Google authorization/callback 두 경로만 별도 보안 체인으로 조립하며 REST의 STATELESS JWT 경계를 유지한다. OAuth 요청 state는 기존 Spring의 임시 HttpSession 저장 방식을 사용한다. Google 네트워크 호출·자격증명·운영 설정을 이 구현 과정에서 실행하거나 변경하지 않았다.

Google의 이메일/공급자로 기존 회원을 우선 찾고 LOCAL 충돌은 자동 연결하지 않는다. 신규 회원의 기존 닉네임·ROLE_USER·provider ID와 감사시각을 보존한다. 기존 User nickname 저장소를 재사용하며 Google 외부 I/O 후 회원 생성 TX와 세션 생성 TX를 분리한다. 계정 연결·LOCAL 폐지는 추가하지 않는다. unique 충돌의 원래1회 추가시도는 실패 TX 종료 뒤 별도 TX로 이동하며, 세션 생성은 같은 회원 행 잠금 뒤 수행한다. 실제 JPA flush/23505·동시 가입·DB 감사시각의 동등성은 아직 PostgreSQL 비교로 입증하지 않았다.

성공 redirect는 refresh 쿠키를 설정하고 설정된 frontend 주소로 토큰 query 없이 보낸다. frontend는 이어서 refresh를 호출해 access token을 받는다. 임의 예외/원격 응답을 실패 주소에 복사하지 않고 정해진 문구만 허용한다(실패 처리는 그대로다).

가까운 업무·설정·state/오류·JWT REST·기본 비활성·DEBUG 회귀의 고유30사례는 분할 실행으로 통과했고 계층383class도 검사했다. 실제 Google 인증 교환/브라우저 쿠키·활성 JAR·DB/Redis 통합은 아직 미실행이다.

## 관리자 회원 목록·검색

ADMIN GET `/api/v1/admin/users`와 `/api/v1/admin/users/search`는 기존 reader·읽기 전용 TX·회원7필드 projection을 재사용한다. 기본20건/createdAt DESC와 Page wire·부분 페이지의 count 생략, 원문의 trim/escape 없는 email/name/nickname LIKE와 role AND 필터를 유지한다. JWT ADMIN 판정을 바인딩·DB 조회보다 먼저 수행하며 외부 사용자 헤더를 신뢰하지 않는다.

정렬은 기존 조회 권한의7필드만 허용한다. 원문 JPA가 받을 수 있는 password/careRegNo/providerId/updatedAt4필드는 후보가 명시 거부한다. 이는 알려진 지원범위 차이이며 완전 호환으로 표시하지 않는다. 해당 정렬을 위해 비밀번호 열 조회나 운영 권한을 늘리지 않았다. 실제 운영 전환 전 정렬 정책을 확정해야 한다.

원문·후보 SQL의 실제 PostgreSQL LIKE/collation·정렬·동시 count/JPA 동등성은 아직 미실행이다. 가까운 JDBC/HTTP/명시 조립의 첫 선택41사례/5XML와 계층390class는 통과했으며 모의 transaction을 실제 PG 롤백으로 표현하지 않는다.

## User HTTP 편입 묶음

Google·관리자 회원 목록/검색·보호소 신청/승인·본인 동물 활동을 개별 대기열 대신 User 모듈의 한 편입 PR로 정리한다. 원문53개 앱/시험 파일의 연속 before/after와 최종 활동 사본 바이트를 전수 대조했으며 업무 코드를 다시 작성하지 않았다.

보호소는 `pawbridge.shelter-membership.enabled=false`, 동물 활동은 `pawbridge.user-animal-activities.enabled=false`가 기본이다. true에서는 명시 Animal origin을 요구한다. 보호소는 현재 DB 관리자 확인과 User→신청 재잠금, TX 안 Animal 조회, 승인 역할/등록번호·감사시각, 원문의 RR 기간 통계를 유지한다. 찜 추가/제거는 기존 Favorite와 Outbox를 같은 TX에 저장하고 payload eventId와 Outbox event_id를 별도로 생성한다. 삭제0행에서는 이벤트를 만들지 않는다. 본인 동물 조회의 DELETED fallback과 원문 Page wire도 유지한다.

Kafka 보상 소비/manualACK·재시도·processed 저장·정리 예약의 소유권은 아직 편입하지 않았다. LOCAL 가입/로그인·이메일, Store 활동 조회와 P8 계정 정책도 이 묶음에 없다. 이 상태로 구 User 실행기 종료나 Kafka 종료를 완료했다고 표시하지 않는다.

코드 활성화 전에는 선택한 writer의 실제 DML 권한, Google/Animal/Redis/Kafka·DB 통합 및 JPA flush/감사시각/경합을 격리 환경에서 검증해야 한다. 이 작업은 실제 권한이나 운영 설정을 변경하지 않았다.

새로운 User SQL/외부 연동의 미실증은 명시적으로 남기며 운영 인계 성공으로 확대하지 않는다.

## 실종·목격 제보

`pawbridge.animal-reports.enabled`는 기본 false다. 선택 reader/writer의 실제 제보 조회·DML/행잠금·생성키 권한과 격리 요청을 검증한 뒤에만 명시 true로 활성화한다. false에서는 제보 빈과 보안 matcher를 만들지 않고5경로를 기본 거부한다. 이는 원문과 다른 기동 보호다. 기동 성공은 제보 준비 완료가 아니며 이 gate 전에는 외부 라우팅을 인계하지 않는다.

명시 활성화에서 공개 GET `/api/v1/reports`와 `/{reportId}`, JWT 본인 POST/PUT/DELETE를 조립한다. 기존 목록 필터·Page wire·상세22필드·소유권·soft-delete·사진/종류 변경 제한과 닉네임 fallback을 유지한다. reader/writer·TM·R2·User nickname 조회를 재사용한다. 원문 JPA의 int 범위를 넘는 offset은 읽기 TX 안·SQL 전에 같은 예외로 거부하며 공개 조회를 큰 OFFSET SQL로 넘기지 않는다.

사진은 reports/images 경로를 사용하고 부분 업로드 실패는 앞서 올린 객체를 정리한다. INSERT 본문/saveAndFlush 상당 실패는 업로드를 정리하지만 proxy commit 실패는 원문처럼 별도 보상하지 않는다. soft-delete에서 사진을 지우거나 새 Outbox를 만들지 않는다. JPA preflush 응답의 이전 updatedAt을 보존한다. 이런 원문 동작을 새로운 보상 정책으로 대체하지 않았다.

비작성 서브가 생산8/시험4·변경23메서드를 대조했다. 고유49사례는 분할 선택 성공 합집합이고 초과 offset 신규1은 RED→GREEN으로 SQL0을 확인했다. User와 함께 명시 조립을 합친 뒤 새 조립1·466class 계층 검사도 수행했다. 실제 PG 권한/SQL·JPA 감사시각/경합·R2/활성 JAR은 아직 미실행이며 코드 편입만으로 운영 인계를 완료했다고 표시하지 않는다.

## 홈 영상 관리·갱신

ADMIN 목록/미리보기/등록/수정/게시/전체순서/재검사7경로는 원문의 board revision 행 잠금, 등록100/게시3 제한, 중복ID와 전체순서 집합 검증을 유지한다. 기존 공개 HomeVideo11필드를 재사용하고 공개 GET은 변경하지 않는다. 외부 metadata 조회는 원문처럼 TX 밖이며 갱신은 만료 정리 커밋→최대50개 조회→각 board 잠금 반영 순서다.

`pawbridge.home-videos.management-enabled`는 기본 false다. 관리 true와 기존 canonical `pawbridge.jobs.refresh-enabled` 명시 true가 모두 있을 때만60초 뒤/1시간 주기 갱신을 조립한다. 기존 flag=false는 항상 거부하며 미지정도 후보에서는 off다. 원문 고정990은 canonical true/matchIfMissing=true였으므로 후보 기본 off는 알려진 활성화 차이다. 변경된 working 원문을 무조건 실행으로 본 초기 설명은 정정했고 원본 파일은 수정하지 않았다.

`pawbridge.home-videos.curation-enabled`(기본 false, 관리 true 필요)는 후보 모으기를 조립한다: YouTube 검색 → 규칙(`CandidateRules`) → Gemini 판정 → `home_video_candidates`(V13) 저장. 이 표만 쓰는 JPA 단위가 쓰기 DataSource를 공유한다. 판정 제공자는 `pawbridge.home-videos.judge-provider`(기본 `openai`, 또는 `gemini`)다. OpenAI는 `OPENAI_API_KEY`와 `pawbridge.home-videos.openai-model`(기본 `gpt-6-luna`), Gemini는 `GEMINI_API_KEY`와 `pawbridge.home-videos.gemini-model`(기본 `gemini-3.5-flash-lite`)을 쓴다. 판정 묶음마다 정답 예시 영상(`CurationCriteria.REFERENCE_VIDEO_IDS`)을 섞어 하나라도 탈락하면 아무것도 저장하지 않는다. 확인한 지 29일 지난 줄은 모을 때마다 지운다. 기존 `pawbridge.jobs.refresh-enabled`도 켜져 있으면 1시간 갱신 뒤에 차례로 빈칸 채우기(볼 수 없게 된 게시 영상을 예비 후보로), 사용 기록 재확인(7일 지난 USED를 다시 확인, 사라진 영상 기록은 삭제), 주간 교체(V14 `home_video_curation`의 마지막 성공에서 7일, 실패하면 1일 뒤 재시도)를 한다. 교체 반영은 같은 JPA 단위의 한 트랜잭션에서 `home_video_board`를 잠그고 하며, 계획 뒤 관리자가 표를 바꿨으면 아무것도 바꾸지 않는다. V14 줄의 마지막 성공이 비어 있으면 켠 뒤 첫 갱신에서 바로 교체한다.

비작성 서브가16파일·30시험메서드와 원문 관리15본문/저장소11/YouTube client4본문·URL 변환을 대조했다. 고유69사례는 분할 성공 합집합이다. User/Report와 합친 새 조립1·466class 검사는4초에 통과했다. 실제 PG 잠금/rollback·YouTube·예약 true의 실행/활성 종료·JAR은 미실행이며 운영 스케줄러 소유권이 인계된 상태가 아니다.

## Community HTTP 편입 묶음

제보와 홈 영상 관리25경로를 이미 편입한 User HTTP 위에 묶는다. 독립 검수한22개 업무/시험 파일을 재사용하고 App·Security·조립시험3경로를 병합했다. 이후 무조건 제보 노출을 막기 위해 ReportConfig/HTTP와 공동Security/AppTest4경로의 기본 비활성을 보완했다. User의 Google/보호소/활동 조립·인가와 기존 공개 홈 조회를 보존한다. 새 운영 권한·설정·데이터·스케줄러는 변경하지 않는다.

### Google 실패·경합 보완과 남은 실제 연동 검증

자동 리뷰에서 원문에도 있던 같은 TX의23505 뒤 재시도와 최초 refresh 선조회/저장 경합을 찾았다. 회원 생성은 첫 TX의 rollback·cleanup이 끝난 뒤 새 TX에서 Google/LOCAL을 재조회하고 원래 한도인 추가1회만 시도한다. 동시 요청이 이미 만든 Google 계정은 재사용한다. refresh는 발급 순서를 보존한 뒤 같은 기존 writer TX에서 회원 PK를 FOR UPDATE로 잠그고 refresh를 재조회·저장한다. 사라진 회원·복수 refresh 행은 임의 보정하지 않고 실패한다. 이는 기존 실패/경합 동작의 의도적 보완이며 source byte 동일성으로 표현하지 않는다. 새 DDL/UNIQUE/데이터 보정은 없다.

Google 가입/저장 단위16건은 RED9 실패→GREEN16으로 rollback 종료/유한 시도/잠금 전 조회 금지와 실패 전파를 보호한다. 실제 PostgreSQL23505 복구·동시 row lock·선택 writer의 SELECT/행잠금/DML 권한은 아직 검증하지 않았다. 현재 OAuth 웹 호출에는 outer TX가 없으며, 다른 refresh writer까지 직렬화되거나 모든 isolation에서 동일하다고 주장하지 않는다. Google 활성화 전 실제 DB/격리수준·브라우저 흐름을 확인해야 한다.

오류 redirect에 기존 query가 있으면 두 번째 물음표가 붙는 결함도 URI builder로 보완했다. 새2 RED 실패→기존 포함7 GREEN, 기존 오류 문구·query 없는 응답·토큰 미기록을 유지했다. fragment 시험은 handler 경계이며 Boot의 fragment 거부 정책은 그대로다.

### 제보·홈 관리의 활성화 전 권한 인수

Reader/Writer는 필수 외부 설정으로 선택되며, 기존 최소 권한 역할을 그대로 적용하면 새 조회·저장에 실패한다. 제보 기본 off와 홈 관리 기본 off를 유지하고 선택 계정의 실제 테이블/열·잠금·생성키 권한과 격리 요청·외부 연동을 확인하기 전 true/라우팅/예약 인계를 하지 않는다. 실제 GRANT를 실행하지 않았다.

제보 기존활성 HTTP16과 비활성5경로의403·호출0, 기본off/명시true조립2의 실제23사례는 RED6→GREEN23/3XML/각5초로 검증하고 비작성 검수했다. 변경4파일/11메서드 전수 대조, 새 프로덕션 타입·architecture 반복은 없다. 이 결과는 실제 PG 권한/SQL·R2·활성 서버 검증이 아니다.

### 본인 스토어 내정보 조회

`pawbridge.user-store-mypage.enabled=true`일 때만 `/api/v1/users/me/wishlists`, `/orders`, `/cart`의 GET을 조립합니다. 기본은 비활성이고 활성 요청은 기존 JWT에서 검증한 본인 ID만 사용합니다. 같은 프로세스의 Store 공개 읽기 API(`StoreMemberReads`)를 호출하며 새 SQL·DDL·권한·장바구니 생성·이벤트 발행은 없습니다.

원문 `f06a72a06bd26662c647bde780dfc7a27bf7349c`의 회원 존재 확인 → 같은 읽기 TX 안 Store HTTP 순서를 유지하고, 기존 회원 프로필7열 조회와 기존 reader/TX를 재사용합니다. 원문의 exists 조회보다 넓은 기존 projection이며 응답으로 회원 정보를 내보내지 않습니다. 목록 Page/정렬/DTO·없는 장바구니204·없는 회원400·외부 오류500을 보존합니다. 예외 응답과 진단에는 외부 본문/인증값을 넣지 않습니다.

후보 HTTP는 연결10초·읽기60초·redirect 금지입니다. 기존 읽기 TX의3초는 JDBC 예산이며 HTTP3초 제한이 아닙니다. 실제 Store 응답/Feign 전체 변형·reader 권한·DB 연결 수명·부하/고장 복구는 별도 활성화 전 검증이 필요합니다. 합성 loopback/단위·JWT/MVC·조립/구조 검증을 실제 Store·PG 검증으로 표현하지 않습니다. 이 PR로 원래 User/Store 실행기나 Kafka/CDC를 종료하지 않습니다.

Store의 non-2xx 응답은 본문 변환 전에 세 조회에서 모두 거부합니다. 리다이렉트를 정상 Page나 없는 장바구니204로 받아들이지 않으며 Location도 따라가지 않습니다. 정상200/204와 기존4xx/5xx 실패는 유지합니다.

### Community 검색 재구축

`pawbridge.community-maintenance.enabled`는 기본 false입니다. 전체 기능과 `pawbridge.search.rebuild-enabled`를 명시 true로 지정해야 검색 재구축을 조립합니다. 하위 기능이 꺼져 있으면 스케줄러도 만들지 않습니다. Outbox·Processed 이벤트 보존 작업은 outbox 제거(Debezium·Kafka·outbox를 모놀리스에서 제거하기로 결정)와 함께 삭제했고 `pawbridge.jobs.cleanup-enabled`는 이 기능에서 더 이상 쓰지 않습니다(Note/Chat 정리는 계속 사용).

검색 재구축은 기존 writer의 Nori·해시·upsert를 재사용합니다. 같은 writer TX에서 최대100개의 누락·불일치 문서를 PK 순서로 잠그고 갱신하며 주기 기본값은5초입니다.

명시 활성화된 작업만 단일 소유 스케줄러를 사용합니다. 단위·조립 시험에서는 SQL 바인딩, 조건 분기와 실패 전파를 검증했고 실제 PG 잠금·rollback·예약 실행·종료 대기는 검증하지 않았습니다. 기존 스케줄러 소유권, 대상 표·열과 행 잠금/UPSERT 권한을 확인하기 전 활성화하거나 구 실행기를 종료하지 않습니다. 새 Kafka/ES 런타임·DDL·GRANT·운영 변경은 없습니다.

### Animal 목록·상세·추천 조회 편입

Animal 업무 모듈은 목록/필터 검색·상세와 비슷한 동물 추천을 소유합니다. `pawbridge.animal-discovery.enabled`와 `pawbridge.animal-recommendations.enabled`는 각각 기본 false입니다. 명시 활성화된 기능의 정확한 GET 목록·숫자 ID 상세·숫자 ID 추천만 공개하고, 임의 nested·쓰기·기존 Gateway alias는 등록하지 않습니다. 실제 라우팅/프로젝터/worker 소유권 인계는 포함하지 않습니다.

기존 PG/Nori 검색의 필터·정렬·준비 상태 오류와 Page/DTO를 재사용합니다. 검색은 TX 진입 뒤 검증하고 count/content를 같은 읽기 REPEATABLE_READ/query5·TX5에서 처리합니다. 추천은 원본 조회가 끝난 뒤 Python을 TX 밖에서 호출하고, 다시 짧은 읽기 TX에서 후보를 검증합니다. 원격 순서/최대6 ID/자신·중복·없는 데이터·다른 종·상태 필터와 실패503을 유지합니다.

Boot는 기존 selected reader/TM과 단일 Nori 수명을 공유합니다. 추천 활성화 시 기존 `lost-search.python-url` → `python-ai-service.url` 우선순위를 따르고 둘 다 없으면 구성 실패입니다. 키는 런타임 인자로만 받습니다. URL이 있고 키가 없으면 ETC는 빈 결과, DOG/CAT는 요청503입니다. Feign core13.5의 연결3초/읽기45초·redirect 금지·애플리케이션 재시도 없음 계약을 재사용하며 새 Spring Cloud/legacy Animal 런타임을 넣지 않습니다.

source20·추천30·정책5·새 Boot11은 각 독립 합성 검증 결과이며 한 번의 전체 실행 합계로 표현하지 않습니다. TX 순서 지적의 신규 반례1은 RED→관련3 GREEN으로 닫았습니다. 새 조립은 실제 business525 검사와 일반 독립 검수를 통과했습니다. 실제 PG SQL/최소권한·락/격리·Python/TLS/AI·실행 JAR은 아직 미검증입니다.

### Store 상품 탐색·상세 조회 편입

Store 업무 모듈은 상품 목록/검색/상세·SKU/옵션·카테고리 조회를 소유합니다. `pawbridge.store-catalog.enabled`는 기본 false이며, 명시 활성화된 정확5 GET만 고정 원문 Gateway처럼 공개합니다. 스토어 메뉴의 ADMIN 노출과 실제 조회 API의 공개 정책은 별개입니다. 새 ADMIN 제한을 도입하거나 기존 Gateway 별칭/쓰기 경로를 등록하지 않습니다. 실제 운영 라우팅/활성화는 포함하지 않습니다.

기존 PG 단일 CTE·Nori/준비 상태·정렬/Page·상태 필터와 금액/시간/NULL DTO를 재사용합니다. 상세는 원문처럼 status 필터나 view 증가 없이 조회하고, SKU/옵션·빈 그룹·카테고리 트리를 기존 값 구조로 반환합니다. JPA 조회는 기존 pawbridge_store 스키마의 명시 SELECT로 구현했습니다. Redis cache/lock을 재사용하지 않는 직접 조회이므로 성능·freshness·Redis 장애 경계의 동등성을 주장하지 않습니다.

동일 selected reader/TM을 사용하되 검색의 원문5초 query 예산을 위해 전용 JdbcTemplate을 조립합니다. readOnly/default isolation/default TX timeout을 사용하고, Nori 빈을 소유 종료합니다. 기존 공용3초 TX 예산을 강제하지 않습니다. 검색에도 읽기 TX를 추가한 알려진 후보 차이가 있습니다. 새 풀/계정/GRANT·DDL/JPA/Redis/Kafka는 추가하지 않았습니다.

원문 source8은44사례와 독립 검수를 통과했습니다. 후보 ADMIN 제한을 철회한 변경 HTTP36사례는4초에 성공했고 원본과 구39사례를 보존했습니다. 원문 유지의 최종 고유51은 분할 성공 합집합으로 단일 실행/75사례가 아닙니다. Animal과 공통8 조립은 새 TestKit3+기본 App1의4사례/12초 및 actual business542 검사에 성공했습니다. 기존44/36/11/전체 architecture는 반복하지 않았습니다.

Root는 공통 Gradle/Boot/정책/인가 합집합을 독립 대조했습니다. 실제 PostgreSQL CTE/FTS·권한/view security·락/시간/타입/timeout·Redis/실행 JAR은 미검증입니다. 최소 reader 권한과 라우팅/단일 소유권을 확인하기 전 활성화하지 않습니다.

### User 내정보의 Store 모듈 호출

기존 `pawbridge.user-store-mypage.enabled`는 기본 false입니다. 활성화하면 User의 회원 확인 트랜잭션을 끝낸 뒤 Store 공개 API로 본인의 위시리스트·주문·장바구니를 조회합니다. 구 Store 서비스 HTTP 경로와 `pawbridge.user-store-mypage.backend`·`service.store.url` 설정은 제거했습니다. 본인 JWT와 기존 3GET·cart 없음204 계약을 재사용합니다.

Store는 동일 reader/TM에 별도 readOnly REPEATABLE_READ·TX5/query5초를 조립합니다. 주문 헤더·상품 조회를 같은 스냅샷에 두기 위한 후보 선택이며 원문 기본 격리/timeout과 완전히 같다는 주장은 아닙니다. User→Store.api만 허용하고, Community→User→Store의 실제 resolved artifact는 허용하면서 Community의 Store 직접 의존/실제 API 참조/임의 파일 우회는 계속 거부합니다.

Store 공개 API/JDBC 최소30사례와 User 어댑터25사례, 새 Boot4/TestKit4의8사례·12초 및 실제 business556 클래스 검사가 통과했습니다. 성공 사례는 반복하지 않았고 실제 SQL/권한·동시성·NULL/collation/시간·GRANT/라우팅·운영 활성화는 미검증입니다. Wishlist의 페이지/옵션은 단일 CTE로 처리합니다. 임의 JPA 중첩 sort 전체 호환은 주장하지 않으며, 기존 User의 sort 쉼표 flatten 동작과 고정 Store Feign serializer의 배열 순서를 비교해 보존합니다.

### Animal 보호소·통계 조회 편입

`pawbridge.animal-shelters.enabled`와 `pawbridge.animal-statistics.enabled`는 각각 기본 false입니다. 보호소는 정확 일반4GET과, 명시 `pawbridge.animal-query.backend=postgresql`에서만 discovery/observations2GET을 등록합니다. 통계는 정확공개3GET·ADMIN2GET만 기존 JWT/role 보호로 연결합니다. Gateway legacy alias나 임의 인접경로·쓰기·배치는 등록하지 않습니다.

기존 보호소 조회·날짜/페이지·관측/미발견/오류 계약과 통계의 happen_date/APMS수정일/created_at+9h의 서로 다른 집계 날짜를 보존합니다. 통계 기간은 공개 default와 관리자 intake-trend366일 검증을 분리하고, 빈 날짜/상태/지역을 임의 보충하지 않습니다. 입양 통계는 실제 입양 이벤트 수가 아닌 기존 현재ADOPTED+오늘APMS수정 의미입니다.

동일 reader/TM을 사용하고 보호소 discovery만 기존 RO/RR·5초를, 일반보호소/통계는 원문 RO/default isolation/timeout을 유지합니다. 새 풀/TM/analyzer/외부요청/예약을 추가하지 않습니다. 보호소 source56·새조립12/8초, 통계 source36·새조립7/7초와business578 검사가 통과했습니다. 성공시험은 반복하지 않았으며 실제PG집계/SQL·최소GRANT·격리/콜레이션/시간·실행JAR/운영활성화는 미검증입니다.

User→Store 모듈 연결과 Animal 보호소/통계를 실제 함께 조립한 뒤, 변경된 조합의 기존 기본 App 시험 클래스8사례와 생산 컴파일·business592 검사를8초에 확인했습니다. 신규8계약으로 세지 않습니다. 원문 source/HTTP/전체 구조 시험은 반복하지 않았습니다.

### Store 위시리스트 명령 편입

`pawbridge.store-wishlist.enabled`는 기본 false입니다. 명시 활성화 시 정확 POST/DELETE `/api/v1/wishlists` 두 경로만 기존 JWT 인증에 연결하며 인증 principal의 회원 ID만 사용합니다. body/query/임의 X-User 헤더는 회원 ID로 신뢰하지 않습니다. 원래 소유권 검사가 없던 ID 삭제 HTTP는 등록하지 않습니다.

전용 계정·풀 없이 공유 단일 풀(`storeWishlistWriteDataSource` 별칭)을 쓰고 자체 JdbcTemplate·TM으로 query5/TX10초·Clock.systemDefaultZone을 명시합니다. 실제 비밀값/계정/GRANT는 변경하지 않습니다. 회원/SKU 중복 선검사·현재 상품/옵션 응답·JVM시간 auditing·원래 오류 순서를 보존합니다. 유일제약 없는 동시 중복 추가는 기존 한계/활성화 gate이며 무조건 안전하다고 주장하지 않습니다.

source25사례·일반 독립검수와 새 합집합 Boot22사례·production compile/Business600 검사가8초에 성공했습니다. 원래 Boot22와 합집합22를44고유사례로 합산하지 않습니다. 위시리스트 source12 조립 단계에서 공통 App/보안/Storebuild 외 다른803부모파일과 source6을 보존했습니다. ID삭제 소유정책·실제PG/RETURNING/시간정밀도·writer 최소권한/identity sequence·commit/rollback·동시성·실제pool 종료는 미검증이며 활성화 전 확인해야 합니다. 운영·DB·결제/재고/Outbox/Redis·기존 데이터 변경은 없습니다.

별도의 새 PG17.11 ARM64 컨테이너1에서 실제 SELECT/CTE·INSERT RETURNING·owner DELETE의 합성 SQL12사례가 psql 단일 세션으로 성공했습니다. networknone/포트0/hostmount0/volume0·tmpfs·512MiB/CPU1 한도였고 해당 컨테이너만 정상 종료·삭제했습니다. 이는 JDBC/ResultSet 매핑·최소권한·인가·rollback/동시성의 증거가 아니며, source SQL의 schema/placeholder를 합성 값으로 바꾼 구문·결과 형태 확인입니다. 기존 서비스·DB·자격증명을 사용하지 않았습니다.

### 보호소 회원승인의 Animal 모듈 조회

기존 `pawbridge.shelter-membership.enabled`는 기본 false입니다. 활성화하면 승인 중 보호소 등록번호를 Animal 공개 API로 조회하며 외부 origin/client를 만들지 않습니다. 구 Animal 서비스 HTTP 경로와 `pawbridge.shelter-membership.animal-backend`·`animal-base-url` 설정은 제거했습니다. User→Animal.api만 추가하고 Animal→User나 Community의 직접 Animal 의존/참조는 계속 차단합니다.

Animal API는 기존 전체 ShelterReads를 그대로 위임하고 같은 named reader/TM/ObjectMapper를 사용합니다. 기존 ShelterReads 빈이 없으면 동일 구현을 내부용으로 생성하며 공개 Shelter 경로를 켜지 않습니다. Reader RO REQUIRES_NEW와 기존 User writer는 분리합니다. 원문의 관리자/회원/잠금/상태검증·null/id/care 검증 및 원문 missing500→User503 분류를 보존합니다. 실제 Reader 권한·트랜잭션 suspend/resume·연결/잠금은 미검증입니다.

동결 Shelter source7사례, 같은구조 guard6 성공과 독립 검수를 보존했습니다. 활동 module을 제외한 새조합은2사례·production compile/Business605를6초에 확인했습니다. 기존 source/전체구조 성공시험은 반복하지 않았습니다. 활동조회의 특정 collection sort는 원문200/후보500 차이가 있어 해당 API/JDBC/어댑터/시험/선택 설정을 게시에서 전부 제외하고 별도 준비본에 보존했습니다. 기존 User 활동조회는 HTTP를 유지합니다. 활동 모듈·전체 HTTP 동등성·운영 활성화 완료를 주장하지 않습니다.

### 본인 찜상품 항목 관리

`pawbridge.store-wishlist.enabled=true`에서만 JWT 회원의 `DELETE /api/v1/wishlists/{wishlistId}`를 허용합니다. body·query·헤더의 회원 ID 대신 검증된 principal을 사용하며, 항목 ID와 회원 ID를 같은 DELETE 조건에 묶습니다. 없음·다른 회원 소유·반복 삭제는 동일한 204이고 DB/TX 오류는 성공으로 숨기지 않습니다. 기존 POST와 SKU 기반 DELETE는 유지합니다.

회원 찜 조회 정렬의 `skuPrice`·`sku_price`는 기존 `sku.price`와 같은 컬럼을 사용합니다. Store의 전체 재귀·컬렉션 JPA 정렬 호환성은 아직 미완료입니다. 이 변경과 합성 시험은 운영 배포 또는 전체 회원 조회 전환 완료를 뜻하지 않습니다.

## User 동물 조회와 찜 단일 트랜잭션

`pawbridge.user-animal-activities.enabled=true`이면 Animal의 순수 Java `AnimalMemberReads`로 본인 동물·찜 목록을 읽는다. 구 Animal 서비스 HTTP 경로와 `pawbridge.user-animal-activities.animal-backend`·`service.animal.url` 설정은 제거했다. 보호소 API와 같은 reader를 사용하지만 공개 Animal 경로를 추가로 활성화하지 않는다. reader는 별도 읽기 manager의 read-only REQUIRES_NEW이며 User writer와 분리된다. 원문 MANUAL 목록, 입력 ID 순서·중복/부재 처리, nullable 필드, JPA 속성 경로 정렬과 페이지 중복 제거 의미를 유지한다. 후보 SQL의 owned PostgreSQL 관측과 원문 JPA 실행 비교는 구분한다.

찜 추가·삭제는 `pawbridge.user-animal-activities.enabled=true`일 때 User writer 트랜잭션 하나에서 `pawbridge_user.favorites` INSERT/DELETE와 `pawbridge_animal.animals.favorite_count` 증감(`updated_at` 갱신)을 함께 처리한다. 증감은 Animal 공개 API `AnimalFavoriteCounts.adjust(animalId, +1/-1)`가 같은 `writeJdbc`·`writeTransactionManager`로 수행하며 자체 트랜잭션을 열지 않는다. 감소는 기존 값이 양수일 때만 수행한다. Outbox(`FAVORITE_ADDED`/`FAVORITE_REMOVED`)·전달기(DB 폴링)·`processed_events` 영수증·`FavoriteCompensation` 보상과 `pawbridge.favorite-delivery.*` 설정(`user-outbox-floor`/`animal-outbox-floor` 포함)은 제거했다. 이벤트 큐가 필요해지면 다시 도입한다.

관심 동물 알림 `GET /api/v1/users/me/favorite-animal-notifications`(같은 플래그, `Cache-Control: no-store`)는 관심 등록 뒤에 상태가 바뀐 동물만 최신순으로 준다. 한국 날짜로 바뀐 날을 포함해 15일 동안 보인다. 상태 변화는 `animals.previous_status`·`status_changed_at`(animal V10)에 마지막 1건만 남긴다. 규칙은 `AnimalStatusChange.between` 하나이고, 보호소 상태 변경(`Animal.updateStatus`)과 APMS Writer(묶음의 현재 상태를 읽은 뒤 JPQL 갱신)가 같이 쓴다. 미상(`UNKNOWN`)으로 가는 변화는 남기지 않는다. V10은 운영 DB에 수동으로 적용해야 한다.

동작: 이미 찜했으면 409, 존재하지 않는 동물을 찜하면 404(`동물을 찾을 수 없습니다`)이며 찜 행도 같은 트랜잭션에서 롤백한다(UPDATE 영향 행 0). 이전에는 성공 응답 뒤 최대 4회 재시도 끝에 보상으로 취소했다. 찜하지 않은 동물의 삭제는 기존처럼 404이다. 이미 사라진 동물의 찜은 계속 삭제할 수 있다(영향 행 0이어도 삭제는 커밋하고 증감만 건너뛴다). 찜 수는 커밋 즉시 일관된다(이전: 폴링 1초 이상+재시도 지연). 회원 삭제가 `favorite_count`를 줄이지 않는 기존 갭은 구 시스템과 같고 이번에 바꾸지 않았다.

락 순서: Animal 명령은 User 행 `FOR UPDATE` → Animal 행 순서이고, 찜은 `favorites` INSERT(users FK의 `FOR KEY SHARE`) → Animal 행 UPDATE 순서이며 한 트랜잭션이 Animal 행을 하나만 잡는다. 코드 검토상 순환은 찾지 못했다. 같은 회원의 명령과 찜은 users 행에서 먼저 직렬화되고, 다른 회원끼리는 서로 다른 users 행을 쥔 채 Animal 행 하나를 기다릴 뿐이며, Animal 모듈에는 users 행을 요청하는 경로가 없다. 단 실제 PostgreSQL 동시 실행으로는 검증하지 않았다(불확실). 인기 동물은 Animal 행 UPDATE에서 짧게 직렬화된다. `animal_search_changed` 트리거는 breed 등 검색 열이 바뀔 때만 동작하므로 찜 증감은 검색 dirty를 만들지 않는다.

활성화 전 조건: 선택한 User writer에 `pawbridge_animal` 스키마 USAGE와 `pawbridge_animal.animals`의 `SELECT(id, favorite_count, updated_at)`·`UPDATE(favorite_count, updated_at)`가 필요하다(WHERE·SET·CASE가 세 열을 읽는다). `pawbridge_user.outbox_events` INSERT는 더 이상 필요 없다. owned PostgreSQL 증분 SQL과 시험은 추가하지 않았으므로 운영 역할에 직접 부여하고 확인한다. 이 단일 트랜잭션 SQL·권한·락은 단위·조립 시험(mock JDBC)으로만 확인했고 실제 PostgreSQL 시험은 하지 않았다. 이전 전달기·Debezium 소비자(구 user/animal-service)를 먼저 멈추고 미전달 `Favorite` Outbox 행이 없는 것을 확인한 뒤 켠다. User Outbox·Processed 보존 작업(`UserMaintenanceConfiguration`, `pawbridge.user-maintenance.*`)은 outbox 제거와 함께 삭제했다. 컷오버 때 community/user Debezium 커넥터와 `outbox_events`/`processed_events` 테이블 정리는 이 변경의 범위 밖이다.

파일 기반 합성 PG 시험(P6~P19)은 모두 저장소 계약 시험으로 옮기거나 삭제했다. `:boot:ownedPostgresqlTest`와 `owned-postgresql` 태그, 그 시험용 설정 규약(`PAWBRIDGE_OWNED_SETTINGS_ENV`, `Pn_PG_SETTINGS`)은 제거했다. 실제 PostgreSQL 검증은 `:boot:contractTest`(Testcontainers, `boot/src/contractTest`)의 저장소 계약 시험이 맡는다. 이후 설명에 나오는 P7~P19 합성 PG 시험 호출 방법은 더 이상 쓰지 않는다.

합성 PG 시험의 `owned-postgresql` 태그와 전용 태스크는 제거했다. 동물 회원 조회는 `AnimalMemberReadRepositoryContractTest`, 이벤트·그 밖의 저장소 SQL은 `boot/src/contractTest`의 해당 계약 시험이 확인한다. 실제 계정·접속값은 예시나 Git에 넣지 않는다.

## Store 회원 목록의 원문 JPA 재사용

`pawbridge.user-store-mypage.enabled=true`일 때 주문·찜 목록은 원래 Store의 model/enum/base13개와 DTO2개를 패키지·import만 바꿔 재사용한다. Repository2개는 ID 페이지 조회와 선택한 부모의 상세 graph 조회로 확장한다. 정렬은 이전 모듈 JDBC가 지원한 찜25·주문12 scalar 속성을 모두 보존하고, 찜에는 한 단계 부모 카테고리 id/name만 추가해 정확27/12개를 허용한다. underscore/camel alias는 기존 Spring Data 속성 해석을 사용한다. 재귀 경로와 컬렉션 정렬, 정렬5개 이상·size2000 초과는 Store 읽기 트랜잭션과 SQL 전에 고정 오류로 거부한다. 이번 PR 초기에 추가했던 컬렉션 정렬은 부모 페이지를 증폭하므로 의도적으로 제외한다. 기존 User Runtime 오류 응답·순수 Store API를 유지하고 Cart는 기존 JDBC 읽기를 사용한다.

Store EMF는 entity9와 mapped superclass1만 명시하고 공유 단일 pool(`pawbridge-db`)을 쓴다. 별도 명명 JpaTM의 RO/RR/5초 안에서 entity→DTO 변환까지 마친다. 전역 JPA/entity/repository 자동스캔, OSIV, schema 생성·변경·validate는 켜지 않는다. schema는 pawbridge_store, 물리 naming과 UTC를 고정하며 bootstrap JDBC metadata 조회도 끈다. 기존 JdbcTM은 다른 업무에서 그대로 유지한다. 같은 DataSource에서 JPA 트랜잭션 매니저를 다른 트랜잭션 안에서 시작하지 않는다.

부모 ID의 closed projection만 먼저 SQL LIMIT/OFFSET으로 조회하고, 같은 RO/RR 트랜잭션에서 memberId와 선택 ID에 해당하는 graph만 가져온다. ID 페이지가 비면 graph를 조회하지 않으며, 부모 ID 페이지의 순서·total·Sort로 DTO를 조립한다. 선택 ID가 상세 결과에서 빠지면 부분 성공으로 반환하지 않는다. 새 임의 ID tie-break나 자식 MIN/MAX 정렬은 추가하지 않는다. 선택한 부모의 자식 전체는 여전히 로드하므로 고정 메모리 사용을 보장한다고 표현하지 않는다. 읽기5초 제한은 기존 모듈 경계이며 원문 무제한 실행과 구분한다.

## Animal JSON 명령과 현재 회원 권한

`pawbridge.animal-commands.enabled`는 기본 false이며 대소문자를 구분하지 않는 명시 `true`만 허용한다. 이 값이 없거나 `on`인 경우 명령과 보안 경로는 비활성이다. 기존 동물 조회·추천은 각자의 flag를 유지한다.

| 메서드 | 경로 | 결과 |
| --- | --- | --- |
| POST | `/api/v1/animals` | 원문 동물 생성, 201 |
| PATCH | `/api/v1/animals/{id}/status` | 원문 상태 변경, 200 |
| PATCH | `/api/v1/animals/{id}/description` | 원문 설명 변경, 200 |
| PUT | `/api/v1/animals/{id}` | 직접 등록(MANUAL) 동물의 정보 항목 전체 교체, 200. APMS 동물은 409 |
| DELETE | `/api/v1/animals/{id}` | 직접 등록(MANUAL) 동물 삭제, 204. APMS 동물은 관리자도 409 |

서명 검증한 JWT의 회원 ID만 사용한다. User 모듈은 같은 writer 트랜잭션에서 현재 회원 행을 잠그고 삭제 대기를 조회한다. Animal은 현재 DB의 `ROLE_ADMIN` 또는 보호소 등록번호가 일치하는 `ROLE_SHELTER`만 허용한다. JWT의 오래된 역할이나 `X-User-*` 헤더로 이 검사를 대체하지 않는다. 기존 조회·ADMIN 경로의 인가는 바꾸지 않는다.

선택한 원문 JPA 모델·저장소·mapper·업무 로직을 infrastructure 안에서 재사용한다. 명시 writer EMF는 Animal/Shelter/BaseTimeEntity만 관리하고 전역 스캔·DDL·새 pool을 만들지 않는다. 원문 감사 listener와 서비스의 REQUIRED 경계를 유지하고 writer 격리는 READ_COMMITTED로 명시한다. JpaTM의 기본 제한은 15초다. 조회용 reader와 구분하고, User 조회와 검색 SQL도 같은 writer DataSource에 참여해야 한다. Nori 분석기는 조회·추천·명령 사이에서 하나를 공유하고 context와 함께 닫는다.

생성과 설명 변경은 원문 동물·검색 투영을 함께 커밋한다. 비MANUAL 출처의 공고번호가 null/빈 값이면 쓰기 전에 고정 400으로 거부하며, MANUAL의 기본값·번호 생성과 정상 외부 번호는 유지한다. 상태 변경은 flush한 감사 시각으로 응답을 만든다. 정보 수정(PUT)은 MANUAL 동물만 받는다. APMS 동물은 다음 동기화가 덮어쓰기 때문이다. 축종·성별·중성화·공고 기간·품종·나이·체중·색·특징·발견 일자·장소·이미지·설명을 요청 값으로 통째로 바꾸고(null이면 지움), 상태·출처·공고번호·보호소는 바꾸지 않으며 검색 투영을 함께 커밋한다. 삭제는 MANUAL 동물만 받는다. APMS 동물은 다음 동기화가 다시 만들고 챗봇 기록이 외래키로 묶여 있어 관리자도 409를 받는다. MANUAL 삭제의 FK 동작은 유지한다. 이 명령들은 더 이상 Animal outbox(`animal.created`·`animal.status.changed`·`animal.updated`)를 쓰지 않는다. 이 토픽의 소비자가 없었고 Debezium·Kafka·outbox는 모놀리스에서 제거하기로 결정했기 때문이며, 이벤트 큐는 나중에 필요할 때 다시 도입한다. APMS/Tour/사진 보관 작업, R2·AI 실행과 운영 활성화는 이번 명령 편입에 포함하지 않는다.

사진 칸(`imageUrl`, `imageUrl2`) 규칙은 생성과 정보 수정에 같다. 칸마다 null, 그 동물의 지금 값, 또는 아래 업로드 경로가 만든 사진 중 저장소에 지금 있고 어느 동물도 쓰지 않는 주소만 받고, 그 밖에는 400 `등록할 수 없는 사진 주소입니다. 사진을 다시 올려 주세요.`를 준다. 저장소 확인(HeadObject)은 쓰기 트랜잭션 전에 하며, 수정에서는 잠금 없이 읽은 지금 값과 다른 주소만 확인하므로 사진을 그대로 둔 수정은 저장소에 의존하지 않는다. 잠금 뒤 지금 값이 바뀌어 확인하지 않은 새 주소가 되면 400이다. 수정으로 빠진 사진과 삭제된 동물의 사진은 커밋 뒤에 어느 동물도 쓰지 않을 때만 지우며, 정리 실패는 주소 없이 경고 로그만 남기고 응답을 바꾸지 않는다. `pawbridge.animal-images.enabled`가 꺼져 있으면 새 사진을 받지 않고 정리도 하지 않는다. 알려진 한계: 오래된 폼이 방금 교체된 사진을 되돌려 보내면서 그 교체의 정리보다 먼저 저장소 확인을 통과하면, 다시 붙은 사진이 정리로 지워질 수 있다(같은 동물의 동시 수정, 그 교체의 정리가 끝나기 전까지의 짧은 창). 업로드만 하고 등록·수정하지 않은 사진은 지우는 경로가 없어 남는다(후속: 버킷 수명주기나 청소 작업).

초기 보완에서는 소유 합성 PostgreSQL 17.11의 실제 보안→MVC→JPA 16계약과 당시 코어 단위 48사례를 확인했다. 이후 동시성 보완의 현재 검증은 아래에서 구분한다. PG는 최초 실행의 15성공과 UTC Calendar로 비교를 교정한 상태 응답 1사례의 수정 전 RED·수정 후 GREEN으로 검증했으며, 비교 방식 교정 뒤 성공한 나머지를 반복하지 않았다. 서버의 시간대·값·허용 오차는 변경하지 않았고 응답/DB 감사 시각 차이는 1µs 미만이어야 한다. 현재 DB 역할·보호소 소유권·삭제 대기, 네 명령·감사 시각·검색 revision, V7 charset 거부와 FK 실패의 rollback, READ COMMITTED·쓰기 트랜잭션·단일 pool/현재 회원 행 잠금, 응답 검증 실패 후 정확한 요청 표식으로 정리하는 경로를 포함한다. User 표는 actor용 열만 갖는 축소 fixture이며 전체 User/Animal migration이나 운영 권한의 검증을 뜻하지 않는다. 기본 off와 비소유 flag alias의 HTTP8은 별도 통과했다.

실제 PostgreSQL 검증은 `AnimalCommandJpaContractTest`와 `AnimalJpaConcurrencyContractTest`(`:boot:contractTest`)가 한다. 이전의 합성 PG 시험(`OwnedPostgresqlAnimalCommandTest`)과 `:boot:ownedPostgresqlTest`는 제거했다. 기존 운영의 DML·행 잠금·감사 및 단일 실행기 소유권을 인계하기 전 flag와 공개 라우팅을 활성화하지 않는다.

### 동시 Animal 명령과 자동 공고번호

현재 회원 행을 먼저 잠근 다음 같은 writer TX에서 Animal을 PESSIMISTIC_WRITE로 조회해 상태·설명 수정과 삭제를 직렬화한다. 두 사용자의 동시 수정이 서로의 필드를 덮어쓰지 않으며 기존 소유권 검사·검색 투영의 원자성은 유지한다.

자동 MAN 번호는 실제 쓰기 TX와 명시 READ_COMMITTED 메타데이터가 있을 때만 생성한다. 날짜를 한 번 캡처하고 날짜별 PostgreSQL transaction advisory lock SQL을 실행한 다음 별도 statement로 정확한 6자리 suffix MAX를 읽는다. 중간 행 삭제 후 현존 최대값을 재사용하지 않고 999999를 초과하는 번호는 거부한다. 잠금과 MAX를 한 SQL/CTE로 합치지 않는다. 명시 제공 공고번호는 원문상 고유 식별자가 아니므로 기존 허용을 유지한다. 모든 번호의 전역 UNIQUE, 비협력 writer·임의 제공번호와의 경합, 삭제된 최고번호의 영구 재사용 금지를 보장하는 계약은 아니다.

이번 수정의 단일 오프라인 호출 10초에서 코어 단위 59사례와 신규 동시성 PG3·영향 기존 PG2가 모두 통과했다. 서로 다른 ADMIN/SHELTER와 실제 두 PG PID의 잠금 대기를 관찰해 동시 상태·설명 보존과 동시 자동번호의 분리를 검증했고 중간 번호 삭제도 재현 후 회복했다. 3개 신규 계약은 기존 코드에서 먼저 RED였다. 다른 성공 PG/JAR와 전체19사례를 반복 실행한 결과가 아니다. 시험은 pool 한도를 임시2로 늘린 뒤 worker 종료가 확인됐을 때만1로 복귀하고 정확한 소유 행을 정리한다. worker 종료가 불확실하면 후속 시험·행 삭제·pool close를 거부해 근거를 보존하며 실패 경로의 전체 자원 정상 종료까지 주장하지 않는다. 이 시험과 mock TX metadata 단위 검증은 운영 또는 실제 AI 검증이 아니다.

READ_COMMITTED 명시 변경 뒤 기존 Boot 구성 검증의 기본 격리 수준 기대값 한 곳을 같은 RC 계약으로 맞췄다. REQUIRED·기본 timeout·writer 연결·readOnly 단언은 유지했다. 해당 구성 클래스12사례를 별도7초 호출로 통과했고 PG·업무 단위·JAR를 반복하지 않았다.

## 바로구매와 본인 주문 상세 편입

`pawbridge.store-direct-orders.enabled=true`에서만 `POST /api/v1/orders/direct`를 조립한다. `GET /api/v1/orders/{orderId}`는 Direct 또는 Cart flag가 명시적으로 true일 때 공유 컨트롤러 한 개로 조립한다. 기본값과 `on` 별칭은 비활성이다. 검증된 JWT 회원 ID를 사용하고 현재 회원·삭제 대기를 확인한다. 주문·상품/SKU 잠금·전체 SKU Outbox·PostgreSQL 검색 반영은 같은 writer JPA 트랜잭션에 참여한다. 주문 상세는 같은 한정 persistence unit의 별도 읽기 전용 트랜잭션에서 조회한다. 기존 MyPage reader는 그대로 유지한다.

기존 주문 응답과 구매 당시 상품명·가격·수량 스냅샷을 보존한다. 수량/금액 overflow는400, 기존 재고/상품 상태 오류는500이며 rollback charset CHECK에 한정한 원문400/C007도 유지한다. overflow·검색·주문 저장 실패 시 재고까지 함께 롤백한다(SKU_UPDATED outbox는 쓰지 않는다). 재요청은 새 주문이며 멱등키나 PENDING 자동 만료를 추가하지 않았다. 장바구니·Payment 및 외부 결제는 이 편입에 포함하지 않는다.

실제 PostgreSQL 검증(동일 상품 동시 구매·실제 행 잠금·전체 롤백·구매 스냅샷)은 `StoreOrderJpaContractTest`와 `StoreJpaConcurrencyContractTest`가 한다. 이전의 합성 PG 시험(`OwnedPostgresqlDirectOrderTest`)과 `:boot:ownedPostgresqlTest`는 제거했다. 이 근거는 합성 환경의 것이며 운영 검증이 아니다.

운영 활성화에는 새 실행기의 DB 권한·라우팅·이벤트 소비자 및 기존 Store 실행기와의 소유권 인계가 별도로 필요하다. 현재 운영·Kafka/CDC·장바구니·결제·복구 자료는 이 변경으로 제어하지 않는다.

## 새 사진 기반 실종동물 후보 검색 편입

`pawbridge.animal-lost-search.enabled=true`에서만 공개 multipart `POST /api/v1/animals/lost-candidates`를 조립한다. 기본값과 `on` 별칭은 비활성이다. `lost-search.python-url`이 `python-ai-service.url`보다 우선하며 서버 내부 키는 `python-ai-service.internal-api-key`에서 런타임에 주입한다. 사용자 헤더를 내부 키로 사용하거나 응답·키·원격 오류 본문을 로그에 남기지 않는다.

Python 응답을 기다리는 동안 DB 트랜잭션과 연결을 열지 않는다. 최대20개 후보를 검증한 뒤, SELECT 전용 reader의 짧은 읽기 전용 JPA 트랜잭션에서 현재 동물의 종·상태와 보호소 정보를 다시 조회한다. 원격 순서·중복 첫 evidence·기존 공개 DTO를 보존하며 내부 유사도 점수는 공개하지 않는다. 사진5MiB/추론 동시1 제한과 원문 오류·Retry-After·permit 복귀를 유지한다.

공개 활성화 전에는 기존 Gateway admission 계약(실제 요청 전체6MiB, 동시2, 인스턴스당 분당30회, 전체50초, 외부 인증·내부 키 헤더 제거)을 별도로 인계해야 한다. 서비스의5MiB 검사와 permit1은 multipart 파싱 전 메모리·전송 제한의 대체물이 아니다. 이 구성의 로컬 합성 성공만으로 공개 운영 준비 완료라고 판단하지 않는다.

실제 PostgreSQL 검증은 `LostSearchCurrentStateJpaContractTest`가 한다(원격 Python 응답만 로컬 대역이며 현재 동물·상태·보호소 정보는 실제 표에서 읽는다). 이전의 합성 PG 시험(`OwnedPostgresqlAnimalLostSearchTest`)과 `:boot:ownedPostgresqlTest`는 제거했다. 실제 AI 품질·R2·운영 검증은 포함하지 않는다.

## 장바구니 관리와 주문 생성 편입

`pawbridge.store-carts.enabled=true`에서만 다음 흐름을 조립한다. 기본 off와 `on` 별칭은 닫혀 있고, Direct flag는 Cart API를 열지 않으며 Cart flag도 바로구매 POST를 열지 않는다.

| 메서드 | 경로 | 계약 |
|---|---|---|
| GET | `/api/v1/carts` | 기존 배열·품목 ID null 응답 |
| POST | `/api/v1/carts/items` | 기존 SKU 수량 누적, 200 빈 본문 |
| PATCH | `/api/v1/carts/items/{skuId}?quantity=...` | 수량 설정, 비양수는 제거 |
| DELETE | `/api/v1/carts/items/{skuId}` | 품목 제거, 200 빈 본문 |
| DELETE | `/api/v1/carts` | 전체 품목 제거, 부모 Cart 보존 |
| POST | `/api/v1/orders` | 저장된 Cart로 PENDING 주문 생성, 배송 4필드·기존 201 응답 |
| GET | `/api/v1/orders/{orderId}` | 현재 회원의 본인 주문·구매 당시 스냅샷 조회 |

신뢰할 수 있는 JWT principal과 현재 회원·삭제 대기를 확인하고, 같은 writer 트랜잭션의 회원 행 잠금으로 해당 회원의 Cart 변경과 주문 소비를 직렬화한다. Cart 두 표는 기존 migration 모델을 사용하며 Redis dirty 동기화 대신 DB에 즉시 반영한다. 기존 MyPage 조회의 Cart 없음과 저장된 빈 Cart 구분을 유지한다. 새 pool·DDL·전역 JPA scan은 만들지 않는다.

주문은 SKU ID만 먼저 추린 뒤 기존 상품/SKU 잠금·재고 차감·검색 반영을 재사용한다(SKU_UPDATED outbox는 제거). 잠금 후의 같은 현재 가격으로 합계와 OrderItem 스냅샷을 만든다. 이는 원문의 잠금 전 합계와 잠금 후 스냅샷 가격이 달라질 수 있던 경계를 강화한 변경이다. 주문 저장과 Cart 비우기까지 하나의 writer 커밋이며, Cart 삭제 후 커밋 실패도 재고·주문·검색과 함께 롤백한다. 원문 배송 4필드에 새 필수·길이 규칙을 넣지 않는다.

누락 SKU만 남은 Cart는 400과 함께 보존한다. 유효·누락 SKU가 섞이면 기존처럼 유효 품목을 주문하고 전체 Cart를 비운다. 중복 SKU 저장 상태는 임의 보정 없이 실패하며, 금액·수량 overflow는 400이다. 새 멱등키나 PENDING 만료, 결제·환불 정책을 만들지 않는다. 동시에 같은 Cart를 주문하면 한 번 소비하지만, 두 주문 사이에 새 추가가 있으면 별도 주문이 생길 수 있다.

실제 PostgreSQL 검증은 `StoreCartJpaContractTest`와 `StoreJpaConcurrencyContractTest`가 한다. 이전의 합성 PG 시험(`OwnedPostgresqlStoreCartOrderTest`)과 `:boot:ownedPostgresqlTest`는 제거했다. 기존 `P11_PG_SETTINGS` Cart 전용 최소권한 기록은 이력으로만 남는다.

공개 활성화 전에는 기존 Redis 미동기화 Cart 인계, 기존 Cart API·동기화 실행기와 새 실행기의 배타적 소유권, DB 권한과 라우팅을 별도로 확인해야 한다. 이 소스 편입은 기존 운영·Kafka/CDC·외부 결제나 데이터·복구 자료를 제어하지 않는다.

## 관리자 주문 조회·배송 관리

`pawbridge.store-order-administration.enabled=true`일 때 관리자 주문 목록·필터/상세 조회와 배송 상태 변경의 세 API, 그리고 아래 주문 상태 변경 API를 등록합니다. 기본값은 비활성이며 `on` 등 별칭은 허용하지 않습니다. 기존 Store writer EMF·DataSource·트랜잭션과 auditing을 공유합니다. 관리자 구성만으로 개인 주문 조회, 장바구니 또는 결제 API를 활성화하지 않습니다.

서명 검증된 관리자 JWT와 현재 DB의 정확한 `ROLE_ADMIN`을 모두 요구하며 삭제 대기·누락 계정은 거부합니다. 조회는 짧은 읽기 전용 트랜잭션, 변경은 같은 writer의 User 행 잠금 뒤 Order 행 잠금으로 실행합니다. 목록 크기는 1~100, 정렬은 명시된 Order의 scalar 필드로 제한합니다. 배송 변경은 주문의 결제 상태·금액·품목·재고·Outbox를 바꾸지 않습니다. 원문 계약인 단건 없음 404, 배송 변경 대상 없음 400, null 배송 상태의 500과 rollback을 유지합니다. null은 같은 쓰기 트랜잭션에서 현재 관리자와 주문을 확인한 뒤 DB 변경 전에 고정 오류로 거부해, DB 제약 오류에 수취 정보가 기록되는 경로를 막습니다.

새 합성 DB 검증은 `P13_PG_SETTINGS`의 비공개 설정을 사용하는 `OwnedPostgresqlStoreOrderAdministrationTest`로 분리합니다. 이 소스 편입은 운영 활성화나 Payment·취소·환불·재입고 계약 완료가 아닙니다.

### 관리자 주문 상태 변경

같은 `pawbridge.store-order-administration.enabled=true`에서 원문 `AdminOrderController`의 PATCH `/api/v1/admin/orders/{orderId}/status`(`{"status"}`)를 추가합니다. 원문은 목록·상세·주문 상태·배송 상태를 한 컨트롤러로 묶었으므로 별도 flag를 두지 않습니다. Gateway의 `PATCH:/api/admin/orders/*/status` ADMIN 전용 규칙과 같이 서명 관리자 JWT를 요구하고, 배송 변경과 같은 현재 DB `ROLE_ADMIN` 확인→User 행 잠금→Order 행 잠금 순서로 실행합니다. 응답은 원문 `OrderResponse` 형태와 200이며 대상 없음 400, null 상태 500과 rollback, 알 수 없는 enum·깨진 JSON의 500 family는 배송 변경과 같은 원문 계약입니다.

원문 그대로 `status` 열 하나만 바꿉니다. 허용 전이 검사, 결제·환불·재고·검색·Outbox·이벤트 연동이 없어 PAID→PENDING, 결제 없이 PAID, CANCELLED 전환 시 재고·결제 미복구 같은 임의 전이가 가능합니다. 이 원문 결함은 고치지 않고 활성화 전 확인 항목으로 둡니다. 변경 로그(원문 info)는 남기지 않습니다. Jackson 기본 설정이라 `{"status":1}`처럼 숫자 enum도 받아들입니다(원문과 같음). 주문을 잠그지 않는 결제 결과 처리(`JpaStorePaymentEvents`)와 동시에 실행되면 오래된 스냅샷이 관리자 변경을 덮거나 그 반대가 될 수 있으며 원문에도 잠금·`@Version`이 없습니다.

활성화 전 writer에는 기존 관리자 주문 기능과 같은 권한(`pawbridge_store`·`pawbridge_user` USAGE, 대상 표 SELECT, `users` UPDATE(user_id) 행 잠금용, `orders` 표 단위 UPDATE — `Order`에 `@DynamicUpdate`가 없어 JPA가 전체 열을 UPDATE하므로 열 단위 grant로는 실패합니다)이 필요합니다(`admin-order-owned-schema.sql`과 같음). 공유 단일 pool(기본 최대 10)을 다른 쓰기와 함께 씁니다. 단위·MVC·Security 시험은 mock 저장소 기준이며 이 경로의 실제 PostgreSQL 검증은 추가하지 않았습니다. 금전 상태를 바꾸므로 CHECK 제약·표 단위 UPDATE grant·행 잠금을 배송 변경의 `OwnedPostgresqlStoreOrderAdministrationTest`와 같은 방식으로 검증한 뒤 켜야 합니다.

### 관리자 주문 목록의 품목 일괄 조회

주문 페이지를 먼저 조회하고 해당 페이지의 ID에만 품목을 한 번에 연결한다. 컬렉션 fetch에 페이지 제한을 적용하지 않으며, 응답 순서와 전체 건수는 원래 Page를 유지한다. 비어 있는 페이지는 품목 조회를 생략한다. User 권한 조회는 별도 JDBC이며, 주문 조회는 페이지 SQL·필요한 count SQL·품목 SQL로 제한한다.

소유 P13의 새 회귀 1건은 수정 전 쿼리 상한 실패와 수정 후 성공으로 확인했다. 두 주문을 size100으로 조회할 때 Hibernate SQL2, 빈 첫 페이지 SQL1을 관측하고 품목·역순·total·15표 불변을 확인했다. 이 데이터의 count는 추론되므로 count SQL을 실제 실행했다는 근거로 확대하지 않는다. 기존 null 배송의 안전한500과 로그 차단은 유지한다.

## 공개 여행 장소 조회

`pawbridge.animal-travel.enabled=true`와 `pawbridge.animal-query.backend=postgresql`을 함께 지정할 때 `/api/v1/places/regions`, `/api/v1/places`, `/api/v1/places/{contentId}`의 세 GET만 활성화합니다. 기본 비활성이고 기존 reader JDBC·트랜잭션 관리자를 공유합니다. 지역·상세는 읽기 전용, 목록의 count와 page는 원문과 같이 같은 읽기 전용 REPEATABLE_READ 트랜잭션으로 조회합니다. 별도 pool·EMF·DDL은 추가하지 않습니다.

원문 PostgreSQL의 여행 카탈로그 7개 테이블과 응답·가용 상태·이미지 이용 조건을 사용하며, 응답 중 TourAPI·R2·AI 호출을 하지 않습니다. 수집·스케줄러·갱신 API는 이 구성에 포함하지 않습니다. 새 합성 DB 검증은 SELECT 전용 reader와 `P14_PG_SETTINGS`를 사용하는 `OwnedPostgresqlAnimalTravelTest`로 분리합니다. 기존 수집기의 실행 소유권 인계와 실제 운영 활성화는 별도 미완료 항목입니다.

### Animal 이미지 업로드

`pawbridge.animal-images.enabled=true`에서만 원문 `ImageController`의 업로드 두 경로를 조립한다. 기본 off이며 `on` 등 true 이외 별칭은 비활성이다. 저장소 client·bucket·공개 URL은 Post 소유 설정을 빌려 쓰고 닫지 않는다. 새 pool·DDL·의존성은 없다.

| 메서드 | 경로 | 결과 |
| --- | --- | --- |
| POST | `/api/v1/animals/images` (`file`) | `{"imageUrl"}`, 200 |
| POST | `/api/v1/animals/images/multiple` (`files`, 최대5) | `{"imageUrls","count"}`, 200 |

허용 대상은 이전 Gateway 규칙을 따르되 판정 근거는 기존 Animal 쓰기와 같은 현재 DB 역할이다. 단건·다중 업로드 모두 서명 검증한 회원 ID로 기존 writer 트랜잭션에서 현재 회원을 조회해 ADMIN·SHELTER만 허용하고(403 `권한이 부족합니다.`), 조회를 커밋한 뒤 저장소를 호출한다. 원문 Gateway는 JWT 역할 claim으로 판정했으므로 토큰 발급 뒤 역할이 바뀐 회원, 회원 행이 없거나 삭제 대기인 회원(403), writer DB 장애(500)에서 결과가 다르다. 역할 검사는 인자 바인딩 뒤에 실행되므로 USER의 형식 오류 요청은 원문 403 대신 400/500을 받을 수 있고, 비-multipart 요청은 공통 Post multipart advice의 500 `서버 에러!`를 받는다(원문 500 `서버 내부 오류가 발생했습니다.`). 원문 Gateway에서 다중 업로드는 역할 규칙에 걸리지 않던 2단 경로라 인증만 요구했지만, 지금은 단건과 같은 역할 검사를 거친다. `/api/animals/...` alias는 Gateway 책임으로 남긴다.

원문 검증·메시지(빈 파일, 5종 MIME, 파일10MB, 0개·6개 이상)와 부분 업로드 실패 시 앞 객체 유지를 유지한다. 원문의 요청10MB 한도는 파일 합계10MB 초과를 413 `사진 크기 제한을 초과했습니다`로 재현한다. 단건은 역할 검사 뒤에 적용한다. 앱 전체 multipart 한도(100MB/200MB)를 넘는 파싱 실패는 기존 공통 advice의 500이며, 실제 전송·메모리 제한의 대체물이 아니다. 키는 `animals/{uuid v4}.{jpg|png|gif|webp}`이고 확장자는 원본 파일명이 아니라 검사한 MIME에서 정한다.

원문의 공개 삭제 `DELETE /api/v1/animals/images?imageUrl=`는 없앴다. 버킷을 게시글·제보·상품 미디어와 함께 쓰는데 이 경로는 역할만 보고 키 모양이나 소유를 확인하지 않아, 보호소·관리자 계정이 버킷의 아무 객체나 지울 수 있었다. 프론트는 이 경로를 쓰지 않았다. 대신 동물 명령이 사진을 정리한다(위 "Animal JSON 명령과 현재 회원 권한" 절의 사진 칸 규칙).

이제 Post 저장소 자격증명을 쓰므로 활성화 전 원문 Animal bucket·공개 URL·자격증명 범위와 같은지 확인한다. 역할 조회는 기존 Animal 쓰기와 같은 `JdbcAnimalCommandActors`이므로 선택한 writer에 `pawbridge_user` USAGE, `users`·`contact_deletions` SELECT, `FOR UPDATE`용 `users` UPDATE(user_id)가 필요하다(`animal-command-owned-schema.sql`과 같음). 선택한 writer에 이 권한이 없으면 업로드가 권한 오류 500으로 실패한다. 활성화 전 권한을 별도로 부여·검증한다. 단위·구성·실제 Security/JWT MVC 시험은 mock 저장소 기준이며 실제 R2·운영 활성화 검증이 아니다.

### 보호소 관리 쓰기와 회원 승인 직렬화

`pawbridge.shelter-commands.enabled=true`에서 보호소 POST·PUT·DELETE만 추가한다. 기본 off·true 이외 별칭은 거부하며 기존 Animal 쓰기와 같은 named writer/EntityManager/트랜잭션·auditing을 공유한다. 새 pool·DDL·원격 호출은 없다.

서명 검증 사용자 ID 뒤 현재 DB의 가용성·삭제 대기·역할을 다시 확인한다. ADMIN은 등록/전체 수정/삭제, SHELTER는 현재 careRegNo와 일치하는 자기 보호소의 5필드만 수정한다. PUT은 nullable5를 그대로 덮어쓰며 등록번호·이름·주소·기관은 바꾸지 않는다. 연결 동물 또는 역할에 관계없이 careRegNo가 연결된 회원이 한 명이라도 있으면 삭제는 고정409로 차단한다. 실제 삭제는 기존 검색·공개정보 FK cascade만 따르고 관측 이력은 유지한다.

회원 승인과 삭제는 User→Shelter 순서 및 실제 PostgreSQL KEY SHARE/FOR UPDATE로 직렬화한다. 보호소 쓰기는 REQUIRED/READ_COMMITTED이며 기존 RR·RO에 참여하려 하면 거부한다. 신규 보호소 쓰기와 회원 승인이 함께 활성화되면 회원 Animal backend는 module이어야 한다. 쓰기 기능이 꺼진 기존 module 승인은 원래 delegate를 사용하여 추가 잠금·권한 요구를 만들지 않는다.

실제 PostgreSQL 검증은 `AnimalShelterCommandJpaContractTest`가 한다(User→Shelter 잠금 순서, 보호소 행 `FOR UPDATE`와 `KEY SHARE`의 배타). 이전의 합성 PG 시험(`OwnedPostgresqlShelterCommandTest`)과 `:boot:ownedPostgresqlTest`는 제거했다.

시험 전용 fixture는 축소 User 모델과 원문 Animal 8표로 구성되며 실제 운영 migration·권한 변경 근거가 아니다. 실제 API 활성화·기존 수집기/회원 승인 경로의 배타 인계는 별도 미완료 항목이다.

보호소 수정은 감사 리스너의 `updatedAt`을 반영하도록 DTO 변환 전에 같은 writer 트랜잭션을 flush한다. 신규 격리 PUT 계약은 이전 시각 및 기존 NULL 감사값을 모두 다루며 응답과 커밋된 값의 일치를 PostgreSQL 마이크로초 정밀도로 확인한다. JDBC 관측은 기존 Animal 명령 시험과 같은 UTC Calendar를 사용한다. 실제 운영 데이터나 API는 변경하지 않았다.

### 내부 AI 갤러리 입력 자료 조회

`lost-gallery-feed.enabled=true`와 명시적 내부키·R2 로컬 서명 설정이 있을 때만 원문 `/internal/animals/lost-gallery`와 snapshot 생성·조회·페이지·해제 5개 API를 등록한다. 기본값은 비활성이며 정확한 경로도 controller의 내부키 검사를 통과해야 한다. 일반 JWT는 내부키를 대체하지 않는다.

기존 reader DataSource/transaction manager를 공유한다. v1은 read callback 종료 후 fingerprint·서명·응답을 만들고, v2는 repeatable-read source callback에서 행을 읽어 파일에 기록한다. describe/page/release는 DB를 열지 않는다. snapshot의 원문 크기·행·페이지·TTL·requestId·cursor·동시 생성·파일 소유권 제한을 보존한다. 원문 statement 20초 설정은 Spring의 트랜잭션 잔여시간으로 덮일 수 있으므로 물리 20초 상한을 보장한다고 해석하지 않는다.

서명은 객체 전송 없이 정확한 R2 HTTPS endpoint와 `pawbridge-animal-originals`의 1시간 GET URL을 로컬 생성한다. 갤러리 벡터 추출·게시, 모델·R2 객체 다운로드, AI 배치와 예약을 실행하는 기능은 아니다. 실제 활성화 전에는 원래/새 feed의 단일 소유권, reader 권한, 내부키·relay·source URL, snapshot 전용 경로를 별도 확인한다. 기존 스냅샷 디렉터리에 임의로 연결하지 않는다. 원문 파일 IO 실패 시 정리 한계도 남는다.

아카이브의 저장 SHA와 객체 경로의 SHA가 일치해야 하며 불일치는 서명·항목 발행 전에 거부한다. snapshot-directory는 비어 있지 않은 절대 정규화 경로여야 하고 파일시스템 루트나 현재 작업 디렉터리는 거부한다. 경로 검증은 스냅샷 저장소의 chmod·잠금·정리가 시작되기 전에 수행한다. 유효한 절대 경로도 실제 활성화 전에는 전용 위치·소유권을 확인해야 한다.

## Animal APMS 번호 상세·공고 종료 임박 조회

기존 Animal discovery 설정의 `true`와 PostgreSQL backend에서 `GET /api/v1/animals/apms/{apmsDesertionNo}` 및 `GET /api/v1/animals/expiring-soon`을 공개한다. 기존 목록·숫자 ID 조회의 설정 계약은 유지한다. 새 두 경로는 off, boolean 별칭(on/yes), 다른 backend 및 다른 HTTP 메서드에서 열리지 않는다.

APMS 번호는 원문 번호 그대로 검색하며 기존 Shelter JOIN과 NON_NULL 상세 28필드를 사용한다. 미존재는 기존 404다. 종료 임박은 JVM 오늘부터 3일 뒤까지 양끝을 포함한 PROTECT 동물이며 noticeEndDate·id 오름차순이다. 단일 사용자 정렬은 기존 동작대로 고정 순서로 처리하고 기존 페이지 상한·응답 7필드를 유지한다. 새 풀·스키마·수집·외부 호출·운영 활성화를 추가하지 않는다.

보안과 실제 컨트롤러를 연결한 영향 HTTP 10메서드 및 기존 P10 전용 DB를 재사용한 실제 PostgreSQL 2메서드를 검증했다. P10 시험은 기존 reader5 설정과 같은 폴더의 fixture7 설정을 확인하고 최대 6개 소유 행만 등록·정리한다. 기존 seed·8표·marker·허용 sequence를 유지하며 새 권한·스키마를 추가하지 않는다. 해당 합성 시험과 `:boot:ownedPostgresqlTest`는 제거했다. 공개 운영 활성화는 별도 단계다.

## 여행 수집에서 기존 여행 조회까지

`tourapi.enabled`와 `tourapi.schedule-enabled`는 기본 off다. 기존 여행 GET 조회 플래그와 독립이며 조회만 켜도 외부 클라이언트·수집 Job·예약은 생기지 않는다. 수집은 원문 PostgreSQL 사업 흐름과 별도 API 키·일별 operation 예산·페이지와 checkpoint 원자성·실패 재개·RUNNING 고아 기록 거부를 재사용한다. 새 requestId 실행은 Batch restart와 다르며 Batch COMPLETED만으로 catalog PARTIAL·QUOTA를 전량 성공으로 보지 않는다.

기존 공유 writer DataSource·JdbcTemplate·JdbcTransactionManager를 사용하고 새 pool을 만들지 않는다. 공유 단일 풀(기본 10)을 쓴다. 세션 advisory lock이 연결 하나를 유지하므로 풀 최대는 최소 2여야 하며(시작 시 검사) 이 최소값은 전체 동시 업무 용량 보장이 아니다. PostgreSQL Batch 메타는 `pawbridge_animal.BATCH_`를 명시하고 DB metadata 탐색·자동 DDL/스키마 초기화·시작 시 Job 자동 실행을 사용하지 않는다.

수집과 예약을 모두 선택하면 Asia/Seoul 기준 기본 30분 cron을 단일 scheduler가 소유한다. 원문 NOT_SUPPORTED tasklet과 짧은 Catalog 쓰기 TX를 유지한다. 정상 종료 설정은 새 지연·주기 실행을 막고 실행 중 작업을 기다리도록 구성하지만 Spring scheduled 취소·실제 외부 요청·프로세스 강제 종료를 포함한 무조건 완주/복구는 보장하지 않는다. 모든 HTTP 뒤 저장에 lock 재확인이 있다고 주장하지 않는다. 실제 활성화에는 이전 실행기 중단·단일 소유권·풀 용량·키/제공자 사용 조건·lock 상실과 고아 기록 대응을 별도로 확인해야 한다.

기존 Main의 Gallery·Shelter 설정 등록을 보존하고 Travel 수집 설정만 명시적으로 추가한다. 첫 준비 원고의 기존 두 import 누락을 부모 전체 등록 목록의 합집합으로 수정했다. 실제 Main의 @Import가 Gallery·Shelter·Travel 세 설정을 유지하는 단일 회귀는 기존 원고에서 RED1(.026초), 수정 후 GREEN1(.024초)로 확인했다. 기존 구성13(.854초)과 별도 실행한 근거이며 전체14를 한 번에 통과했다고 표현하지 않는다.

선택 구성 시험은 13사례(.854초), 새 전용 PostgreSQL fixture의 수집·Batch·기존 조회·session lock·정상 scheduler 종료·실패·quota/orphan 계약은 5사례(.954초)로 각각 성공했다. 두 실행과 기존 business 단위38은 분리된 근거다. provider는 대역이며 실제 외부 HTTP/API 키·운영 수집·배포는 검증하지 않았다. 첫 PG 실행의 provider 관측은 빈 Spring ConnectionHolder binding까지 금지해 실패했다. 원시 active borrow1/TX0와 cached 동작을 확인해 이 과잉 조건만 고쳤으며 실제 TX0·추가 borrow0·동일 lock PID/autocommit/pg_locks·최종 자원 반환 관측은 유지했다. Catalog 11메서드 TT 추가안은 적용하지 않았다. 첫 실패 DB·RUNNING 가능 기록·claim과 원시 근거는 보존하고 재사용하지 않았다.

이전의 합성 PG 시험(`OwnedPostgresqlTravelCollectionTest`)과 `:boot:ownedPostgresqlTest`는 제거했다. 같은 저장소 동작은 `TravelCollectionCatalogRepositoryContractTest`가 Testcontainers PostgreSQL로 확인한다.

## APMS 예약 수집에서 동물·보호소 검색 반영까지

`apms.batch.schedule-enabled=true`를 명시할 때만 예약·수집 Job·HTTP transport를 함께 조립한다. 기본 off이며 수동 실행 API는 아래 별도 flag로만 추가한다. 원문 `query-v2`/`updated-v1` 계획·30일 재조회 overlap·부분 성공·COUNT_MISMATCH·10분 stale 회복과 세션 advisory lock key1을 보존한다. 계획 시계는 Asia/Seoul, Batch heartbeat 비교 시계는 실제 Batch metadata의 JVM 기본 시간대에 맞춰 분리한다.

기존 Animal writer DataSource·EntityManagerFactory·JpaTransactionManager·검색 writer를 공유하며 새 pool·엔티티·DDL·Outbox 발행을 만들지 않는다. 외부 snapshot fetch는 실제 TX 밖에서 수행하고 보호소 준비는 짧은 별도 JPA TX, Animal upsert·검색과 Batch chunk checkpoint는 같은 JPA TX를 사용한다. 같은 DataSource라도 서로 다른 JDBC/JPA transaction manager를 혼용하면 metadata 쓰기가 chunk를 조기 commit할 수 있으므로 Repository·Explorer·회복도 같은 Animal JpaTransactionManager를 사용한다. 원문 chunk1000/retry3와 준비 step의 독립 commit 의미는 유지한다. 원문 fault-tolerant recovery가 정상 항목을 별도 TX로 커밋한 뒤 최종 Step이 FAILED일 수도 있다. 실제 반영량·source/search 일치와 실패 구간의 다음 재조회 계획을 함께 확인한다.

worker 기본값1/범위1~4와 writer 용량 `workers+2` 이상을 명시 검사한다. 전역 writer 기본 용량1과 전역 TX timeout은 변경하지 않는다. APMS 단계 timeout만 양수로 주입한다. 이 하한이나 합성 시험의 용량3은 TourAPI·일반 쓰기까지 포함한 운영 용량 보장이 아니다. 활성화 전 이전 실행기의 중단, 단일 예약 소유권, 동시 업무 용량·실제 API 사용 조건을 별도로 확인해야 한다.

HTTP transport는 명시한 HTTPS 제공자만 사용하고 redirect를 따르지 않는다. 연결2초·요청5초·응답 대기6초와 본문2MiB 상한을 적용하며 실패에 URL·서비스키·응답 본문을 노출하지 않는다. 예약 정상 정지는 실행 중 흐름을 기다린 뒤 자신이 소유한 worker·HTTP client를 종료한다. 프로세스 강제 종료·lock 연결 상실 이후 모든 쓰기의 fencing이나 외부 API 정상 동작을 보장하지 않는다.

이전의 합성 PG 시험(`OwnedPostgresqlApmsCollectionTest`)과 `:boot:ownedPostgresqlTest`는 제거했다. 같은 저장소 동작(chunk 쓰기의 insert·직접 update·검색 문서와 롤백, `pawbridge_animal.BATCH_` 메타데이터 표, stale 회복, 세션 잠금)은 `ApmsCollectionJpaContractTest`가 확인한다. 실제 APMS 호출과 운영 활성화는 별도다.

APMS 선택 단위100사례, 최초 Boot18사례와 필수 수정 뒤 선택2사례, 최종 새 합성 PG5사례(1.392초)는 서로 다른 실행 근거다. 최종 PG는 실제 source/search/Batch metadata DML 후 강제 rollback·physical commit 증가0, 원문 부분 복구·기존 행 보존, 두 실제 PID의 slot, stale10분, 정상 예약 종료를 확인했다. 제공자는 대역이며 실 APMS/운영 검증은 하지 않았다. 최초 SQL 개행 관측 오류와 원문 부분 commit을 전체 실패 원자성으로 오해한 두 시험 실패는 보존하고, 새 fixture에서 최소 정정 후 검증했다. 기존 DB를 reset하지 않았다.

### APMS 수동 실행 API

`pawbridge.apms-manual.enabled=true`를 명시할 때만 원문 `BatchController`의 POST `/api/v1/batch/apms/sync`를 조립한다. 기본 off이며 `on` 등 true 이외 별칭은 비활성이다. 이 flag만 켜도 위 APMS Job·HTTP transport·`apms.api.*` 필수 입력 검사를 함께 조립하지만, 앱 내부 예약(`apmsCollectionSchedule`)은 `apms.batch.schedule-enabled=true`일 때만 만든다. 원문도 수동 API는 항상 두고 예약만 `schedule-enabled`로 켰다. 두 진입점은 같은 `ApmsBatchRunner`와 세션 advisory lock을 공유하므로 겹치면 뒤 요청은 409 `ALREADY_RUNNING`이다.

원문처럼 동기 실행이다. 요청은 Job이 끝날 때까지 HTTP 연결과 요청 스레드를 붙잡는다. 응답 필드(`jobExecutionId`, `status`, `startTime`/`endTime` `yyyy-MM-dd HH:mm:ss`, `skipCount`, 수집 요약 4개, `searchSyncStatus`)와 상태 코드(완료·skip0 200, 실행 중·UNKNOWN 503, 그 밖 500, lock 경합 409, lock·metadata 확인 불가 503 `GUARD_UNAVAILABLE`, 예외 500 고정 문구)를 유지한다. `searchSyncStatus`는 원문 `elasticsearchIndexStep` 이름을 그대로 찾으므로 PostgreSQL Job에서는 원문 PostgreSQL 실행과 같이 항상 `NOT_STARTED`다.

권한은 원문과 다르다. 원문 animal-service에는 자체 인증이 없었고(정기 수집은 원문부터 앱 내부 Spring 예약 `ApmsCollectionSchedule`이며 저장소·운영 Docker compose에 이 API를 부르는 CronJob은 없다), Gateway의 `/api/v1/batch/**` 보호 경로는 JWT 로그인만 요구했지만 `/api/v1/v1/...`로 다시 써 실제로는 404였다. 모놀리스는 공개 앱이므로 무인증 개방 대신 기존 관리자 경로와 같이 서명 JWT의 ROLE_ADMIN만 허용한다(USER·SHELTER 403 `관리자 권한이 필요합니다.`, 토큰 없음·오류 401). 따라서 원래 CronJob 호출 방식(무토큰)은 이 경로로 옮길 수 없으며, 정기 수집은 앱 내부 예약을 쓰고 이 API는 관리자 재실행용으로 둔다.

활성화 전 확인 항목: 이전 animal-service의 앱 내부 예약·수동 API와 모놀리스 앱 내부 예약 중 단일 실행 소유자, APMS writer 권한과 Batch metadata 권한, writer 용량 `workers+2` 이상(수동 요청도 같은 pool을 쓴다), 프록시·Ingress의 요청 timeout이 Job 실행 시간보다 긴지, 종료 유예 시간(모놀리스 `server.shutdown=graceful`·`timeout-per-shutdown-phase=10s`, 원문 animal-service는 330초)이다. 동기 수동 요청이 수 분 걸리는 도중 재배포나 종료가 오면 10초 뒤 공유 pool이 닫히고 Job이 STARTED로 남아 10분 stale 회복에 의존한다. 단위·구성·실제 Security/JWT MVC 시험은 mock runner 기준이며 실제 APMS·PostgreSQL 실행 검증이 아니다.

## 보호소 디렉터리 예약 수집

원문 키 `shelter-directory.schedule-enabled=true`를 명시할 때만 원문 `ShelterDirectoryClient`·`ShelterDirectoryStore`·`ShelterDirectoryCollector`·`shelterDirectoryJob`과 단일 예약을 조립한다. 원문도 기본 false였으며 수동 실행 API는 없다. 서비스 키는 원문 `shelter-directory.service-key`(비어 있으면 실행 시 `SHELTER_DIRECTORY_UNAVAILABLE`), 주기는 원문 `shelter-directory.collection-cron`(기본 `0 0 4 * * *`, Asia/Seoul)이다. 이 스위치는 검색 분석기 조립 조건에도 추가했다. 다른 모듈과 공유하는 키는 없다.

원문 동작을 그대로 옮겼다. 공공데이터 `shelterInfo_v2`를 1000행씩 최대30쪽 조회하고 빈 쪽에서 끝내며, 같은 등록번호의 다른 행·반복 쪽·빈 첫 쪽·형식 오류는 전체 실패로 처리한다. redirect를 따르지 않고 연결5초·요청10초·대기12초, 본문4MiB 상한을 둔다. 전체 snapshot을 받은 뒤에만 한 연결의 한 JDBC 트랜잭션으로 보호소 upsert·보호소 검색 문서·공개정보 JSON patch를 쓰고, 행마다 저장된 `sourceUpdatedDate`가 더 새로우면 건너뛰며 주소가 바뀌었는데 좌표가 없으면 기존 좌표를 지운다. 세션 advisory lock은 APMS 동물 수집과 같은 key1이라 서로 배타적이며, 이미 잡혀 있으면 `SHELTER_DIRECTORY_BUSY`로 실패한다.

기존 writer DataSource·JdbcTransactionManager를 쓰고 새 pool·DDL은 없다. Batch metadata는 `pawbridge_animal.BATCH_`를 명시하고 metadata 탐색·자동 DDL·시작 시 Job 실행을 쓰지 않는다. 검색 writer는 기존 Animal 쓰기가 타입으로 주입받는 단일 bean과 충돌하지 않도록 store 내부 인스턴스로만 만든다. 원문 `SearchDocumentWriter.shelter(Connection, careRegNo)`를 추가해 수집 연결 밖의 두 번째 연결을 빌리지 않는다.

활성화 전 확인 조건:
- 공유 단일 pool(기본 최대10·연결 대기5초)을 쓴다. 수집은 lock 획득부터 외부 조회(최대30회×12초)와 저장까지 그 연결을 계속 쥐므로 그만큼 다른 요청이 쓸 연결이 줄어든다. 원문에서는 shelter·여행(`0 */30`)·보호소 관측(`0 0 4`)이 Boot 기본 `@Scheduled` 스레드 하나를 나눠 순서대로 실행됐지만, 모놀리스는 기능별 scheduler를 두므로 04:00 Asia/Seoul에 동시에 시작할 수 있다. 함께 켜는 수집기의 연결 합계(shelter 1 + 여행 2 + 관측 1 + HTTP 쓰기)에 맞춰 pool을 잡거나 cron을 분리해야 한다.
- writer 권한: `pawbridge_animal.shelters` SELECT·INSERT·UPDATE(검색 trigger 열 포함), `shelter_public_information` SELECT·INSERT·UPDATE, `shelter_search_documents` INSERT·UPDATE, Batch 6표·sequence, advisory lock 함수.
- 이전 animal-service 예약을 먼저 멈춰 단일 소유권을 확보하고, 서비스 키·제공자 사용 조건을 확인한다. APMS 동물 수집과 같은 시각이면 한쪽이 BUSY로 실패한다.

원문 결함은 고치지 않고 기록한다. 충돌 갱신은 `phone`을 바꾸지 않는다(최초 INSERT만 기록, 공개정보 JSON에는 반영). snapshot에서 사라진 보호소는 지우지 않는다. 전체 snapshot을 한 트랜잭션에서 모든 대상 행 `FOR UPDATE`로 잠근 채 쓰며 upsert 문에는 query timeout이 없다. 실패하면 Job이 FAILED로 끝나고 다음 예약까지 재시도하지 않으며, step 예외는 Spring Batch가 ERROR 로그와 `BATCH_*_EXECUTION` 종료 메시지로 남기고 launcher는 정상 반환한다. 종료 시 실행 중인 수집이 끝날 때까지 shutdown이 대기하므로 짧은 종료 유예 시간에서는 강제 종료될 수 있다. 원문 환경 변수 `SHELTER_DIRECTORY_CRON`은 `shelter-directory.collection-cron`에 연결되지 않아 기본 04:00이 적용된다. 원문은 `animal-query.backend=postgresql`일 때만 검색 문서를 썼지만 모놀리스 검색 writer는 분석기를 항상 사용하므로 검색 문서 쓰기가 새로 생긴다. 30쪽(3만 행)을 넘는 목록은 전체 실패다.

단위 시험은 client 9·collector 6·store 4사례와 구성 시험(기본 off·`on` 별칭·같은 writer·Job/step·scheduler 계약)이다. 저장소·HTTP·DB는 mock이며 실제 공공데이터 API·PostgreSQL 잠금·권한·운영 활성화는 검증하지 않았다.

## APMS 사진 보관 예약 경로 편입

`apms-photo-archive.enabled=true`를 명시할 때만 APMS 사진 발견·재시도·보관 작업을 조립한다. 기본 off에서는 archive writer/HTTP/S3/scheduler를 만들지 않으며, APMS 수집 자체나 Gallery 공개·내부 제공 기능을 자동 활성화하지 않는다. 수동 사진 업로드/삭제 API는 이 범위에 없다.

원문의 두 사진 slot·신규/과거 발견 cursor·generation/lease token·최근/장기대기 교대와 PostgreSQL 분기를 선택 재사용한다. 같은 기존 writer DataSource/JdbcTemplate/AnimalJpaTransactionManager의 짧은15초 TX를 쓰고, 원문 이미지 다운로드·Photo 최적화·S3 GET/PUT/GET 무결성 확인 동안 DB TX를 유지하지 않는다. 기존 Animal writer와 검색 분석기를 공유하며 새 pool/EMF/스키마 자동생성은 없다. animals의 공개 원문 URL·자체 상태는 변경하지 않는다.

필수 전용 입력은 `apms-photo-archive.bucket`, `optimizer-url`, `internal-api-key`와 `apms-photo-archive.storage.endpoint`, `region`(기본 auto), `access-key-id`, `secret-access-key`다. 공유 AWS 자격·환경 기본 탐색으로 대체하지 않는다. bucket은 기존 Gallery signer와 같은 `pawbridge-animal-originals`만 허용한다. 인증값·원문 URL·서명 URL을 로그나 예시에 넣지 않는다.

R2 객체는 항상 raw 원본인 것이 아니라 Photo 계약의 원본 fallback 또는 fullsize WebP 결과다. stored SHA 기반 key와 실제 재조회한 bytes·MIME·해시가 일치해야 READY를 기록하고, lease/generation 및 현재 Animal 원문 URL이 다르면 완료를 거부한다. 기존 객체는 실패·URL변경·동물변경 때문에 자동 삭제하지 않는다. 보관 후 DB 완료가 실패하면 원문처럼 lease/재시도로 복구하며, 이를 원격 객체와 DB의 분산 원자성으로 표현하지 않는다.

기본 scan50/maxPhotos25/maxRun300초/lease300초/recheck7일, 최초 지연60초·주기15분을 유지한다. 종료는 새 claim을 막고 현재 항목을 기다린다. `apms-photo-archive.shutdown-timeout-seconds`는1..300/기본15이며, 초과·중단이면 interrupt와 고정 미완료 경고를 남긴다. scheduler의 isRunning=false나 context close 반환은 모든 원격 I/O/worker 완주를 보장하지 않는다. 실제 운영 인계에는 기존 scheduler 중단·배타 소유·전용 권한·키·Photo/R2 실제 검증이 별도로 필요하다.

최초 업무·HTTP·Root 구성/종료 단위 35사례와 새 P19 합성 PostgreSQL 전체 6사례를 검증했다. P19는 source SELECT-only·전 열 불변, 외부 대역 callback의 실제 TX/활성 연결0, READY→기존 Gallery slot1, 실제 세션 잠금/SKIP LOCKED와 claim·lease·재시도·협력 종료를 다룬다. 두 claim SQL의 동시 중첩, 동시 source UPDATE, 전체 Spring context 종료와 실 APMS/R2/Photo·운영 활성화는 검증하지 않았다. P19는 정확한 전체 OwnedPostgresqlApmsPhotoArchiveTest와 전용 P19_PG_SETTINGS/PAWBRIDGE_OWNED_SETTINGS_ENV를 선택하고 XML6/완료6을 확인한다. 성공한 fixture를 자동 초기화·재실행하지 않는다.

## 동물·보호소 검색 문서 수선 예약

Servlet 실행기에서 `pawbridge.animal-query.projection-schedule-enabled=true`, `pawbridge.animal-query.backend=postgresql`, `pawbridge.animal-query.analyzed-text=true`를 모두 명시할 때만 검색 투영 수선 작업을 조립한다. 기본 off이며 일반 조회·명령·APMS flag만으로 예약을 켜지 않는다. 기존 분석기 하나와 writer DataSource/JdbcTransactionManager를 공유하고 새 pool·EMF·TM·생산 DDL을 만들지 않는다. projection-only 구성도 기존 분석기를 명시 import하며 다른 기능과 함께 켜도 중복 생성하지 않는다.

원문처럼 dirty 수선은 기본 2초, 별도 무결성 감사는 기본 60초 지연·최초 60초다. 각 tick은 보호소 다음 동물 순서이고, 첫 보호소 처리 실패 시 그 tick의 동물 처리는 하지 않는다. 기본 page50/허용1..100, source ID 순서·source 선잠금·SKIP LOCKED와 타입별 독립 TX5초/statement4초/lock1초를 보존한다. 기존 업무 TX 안에서 호출하면 거부한다. source revision/분석기 버전·문서 유실 감사는 dirty-only 처리와 구분하며 기존 static 검색 writer로 문서와 dirty=false를 함께 커밋한다. source의 업무 값·상태·URL이나 Outbox는 변경하지 않는다.

기존 검색은 관련 dirty source가 있으면 준비 미완료를 반환하며 수선 후 다시 조회할 수 있다. dirty=false 문서 유실이 항상 준비 미완료 응답을 만드는 것은 아니다. 한 page가 0행이어도 다른 작업이 source를 잠갔을 수 있으므로 전체 준비 완료를 뜻하지 않는다. page 상한은 수선 행 수 상한이며 전체 감사의 스캔 비용 상한 증명이 아니다. 예외는 해당 page를 롤백하고 다음 tick에 남기며 로그에는 예외 타입과 수선 개수만 쓴다.

Boot는 `animal-search-projection-` 이름의 단일 daemon scheduler를 소유하고 종료 시 새 주기·지연 작업을 취소하며 진행 작업을 최대 15초 기다린다. daemon은 이 스레드가 JVM을 붙잡지 않게 하는 설정이며 모든 DB I/O·전체 context의 유한 종료나 진행 page 완주를 보장하지 않는다. 실제 활성화 전에 기존 projector와 새 실행기의 배타 소유·writer 최소 권한·연결 예산·자료 크기와 lock 실패 대응을 따로 확인해야 한다. 이 개발 편입은 운영 예약 활성화가 아니다.

## 본인 주문 상태별 이력

기존 Direct 또는 Cart 기능을 명시적으로 true로 조립한 Servlet 앱에서 `GET /api/v1/orders`가 검증된 현재 회원의 주문 목록을 반환한다. status 생략 또는 기존 OrderStatus 필터, 기본 size10·직접 Page 형식과 저장된 주문/품목 snapshot을 보존한다. 기존 `/api/v1/users/me/orders`의 envelope·size20 계약을 바꾸지 않는다. 외부 회원 헤더·query를 소유자로 사용하지 않는다.

기존 동일 writer의 RO 트랜잭션에서 현재 회원 적격성을 확인한 뒤 본인 조건의 부모 Page를 조회하고, 선택된 부모 ID들의 품목만 한 번 일괄 조회한다. 부모 순서·Page metadata·기존 단건과 같은 snapshot 형식을 유지한다. 빈 Page는 품목 조회를 생략하며 collection fetch에 pageable을 직접 적용하지 않는다. 지원 scalar 정렬 최대4개·부모 size1..100·offset 정수 범위를 검증한다. 부모 상한은 각 주문의 품목 수·전체 응답 bytes의 절대 상한을 뜻하지 않는다.

기본 off·alias off와 익명/이웃 경로 차단을 유지한다. 새 pool/EMF/table·생산 DDL·주문 상태 전이·결제·상품 CUD는 추가하지 않는다. 실제 운영 활성화와 결제 전체 흐름 인수는 별도 작업이다.

## 실제 Main 코어 조립 검증

`PawBridgeCoreIntegrationTest`는 실제 `PawBridgeApplication`에서 User의 Animal/Store module 조회와 Animal 명령·Store 바로 주문 writer를 함께 활성화한다. 업무·actor·facade·DataSource 대역 없이 단일 pool1(`dataSource`·`writeDataSource` 같은 인스턴스), JPA factory3와 transaction manager5의 실제 소비자 연결, 인증·역할·메서드 거부7경로, 소유 writer TX 밖 actor 거부, DB borrow0과 정상 close를 검증한다.

기존 각 기능의 별도 DB 검증은 보존한다. 이 코어 조합 시험은 정상 인증 DB 응답·실 Redis/OAuth/R2·외부 수집·예약 인수·운영 준비 완료를 의미하지 않는다. 외부 기능과 예약은 이 시험에서 비활성이다.

## Animal remaining flows

`pawbridge.animal-chatbot.enabled=true` plus `pawbridge.animal-query.backend=postgresql` explicitly assembles the original anonymous POST `/api/v1/animals/{animalId}/chat/messages`. It shares the existing Animal writer, entity manager, transaction manager and analyzer. Only repository phases use short transactions; Redis and Python are outside them. Original cookie/IP hash, five quotas, guard and message ordering are preserved. This feature defaults off.

`pawbridge.shelter-observations.schedule-enabled=true` enables the original daily 04:00 Asia/Seoul observation. One writer SQL snapshot counts PROTECT animals including zero for empty shelters, preserving the first observation of each day. The existing shelter observation GET consumes these rows. Scheduling defaults off and does not run immediately at startup.

Unit, configuration, actual Security/JWT and architecture checks cover development contracts. Real Redis/Python, production scheduling and operating data are not validated or changed. Original forwarded-header trust, quota/message-cap races, post-AI persistence failure and retention policies remain deferred activation gates.

## Store catalog management

`pawbridge.store-catalog-management.enabled=true` explicitly assembles the original product create/update/delete, option group/value writes and image upload. The canonical write handlers require an ADMIN JWT. Product, SKU, option and search writes share the existing Store writer transaction (the SKU_UPDATED outbox is no longer written) and entity manager; the existing catalog read flag remains separate. Image uploads borrow the explicitly configured Post-owned S3 client and storage settings. This feature defaults off.

The selected 30 unit cases, 12 configuration cases and 101 Security/JWT MVC cases verify local business, shared-resource and request-boundary contracts. Mocked service/repository/storage results do not validate real PostgreSQL or R2 success. The Animal-parent Main/Security merge preserves the chatbot and observation configuration; a combined actual-Main runtime check is a separate final integration gate.

Original DELETED patch/cart bypass, ignored SKU update fields, option mutation races and legacy cache coordination remain deferred activation gates. No payment functionality, live storage operation or production activation is introduced by this change.

The same flag also assembles the original category POST `/api/v1/categories`, PUT `/api/v1/categories/{categoryId}` and DELETE `/api/v1/categories/{categoryId}` behind the same ADMIN JWT boundary. They use the existing Store writer transaction and entity manager, build the original recursive response inside that transaction and keep the original 400 input/500 state error mapping. Public category GET stays under the separate catalog read flag. Unit, configuration and Security/JWT MVC cases use mocked repositories and do not validate real PostgreSQL. Original unvalidated null names, duplicate names, deletes of categories still referenced by products and unlocked concurrent re-parenting or child insertion surface as server errors or races; they remain deferred activation gates. Original create also stores blank names, and PUT clears an omitted `description` and moves the category to the root when `parentId` is omitted while ignoring a null or blank `name`. Concurrent opposite re-parenting can persist a cycle, after which the descendant check and recursive responses, including the public category tree GET, can fail with stack overflow. Non-admin denials return the generic monolith 403 message instead of the Gateway's admin message, unsupported methods such as PATCH return 403 instead of the original 405, and the writer transaction inherits the Store writer default timeout.

### Store 검색 재구축 예약

`pawbridge.store-maintenance.enabled=true`와 원문 `pawbridge.search.rebuild-enabled=true`를 둘 다 명시할 때만 Store 상품 검색 문서 재구축 작업과 `store-maintenance-` 단일 scheduler를 조립합니다. 기본 off이며 원문도 `SEARCH_REBUILD_ENABLED` 기본 false였습니다. `pawbridge.search.rebuild-enabled`와 주기 `pawbridge.search.rebuild-delay-ms`(기본 5초)는 Community 재구축과 같은 원문 이름이므로 두 모듈이 공유합니다. Community는 `pawbridge.community-maintenance.enabled` 하나로 정리와 재구축을 함께 조립하므로, 두 모듈 flag가 모두 켜진 상태에서 `rebuild-enabled=true`를 주면 Store와 Community 재구축이 함께 돌고 주기도 따로 정할 수 없습니다. 원문은 서비스별 프로세스여서 이 결합이 없었으며, 활성화 전 확인 조건으로 둡니다.

원문 `SearchRebuildScheduler`→`rebuildPage(100)`을 그대로 옮겼습니다. 한 writer TX(원문 timeout 10초) 안에서 ACTIVE 상품 중 문서가 없거나 SKU·이름·옵션·분석기 버전이 다른 행을 PK 순서로 최대100개 `FOR UPDATE OF p SKIP LOCKED`로 잠그고, 행마다 `product_search_source`를 다시 읽어 기존 Store 검색 문서 upsert와 Nori 분석기를 재사용합니다. 기존 writer DataSource/JdbcTransactionManager를 쓰고 JDBC 쿼리 timeout 5초는 원문 값입니다. 새 pool·EMF·DDL·GRANT는 없습니다. 공유 단일 pool(기본 최대 10, 연결 대기 5초)이므로, 재구축 TX가 5초마다 최대 10초 연결을 점유하는 동안 다른 쓰기 요청이 연결 대기 시간 초과로 실패할 수 있습니다(원문은 Store 전용 pool 기본 5). 활성화 전 pool 크기를 확인합니다.

단위·조립 시험은 SQL 문구·바인딩·TX 경계·조건 분기·실패 전파만 검증합니다. 실제 PG 잠금, writer 권한(`pawbridge_store` 스키마 USAGE, `products` SELECT·행 잠금용 UPDATE, `product_search_source` SELECT, `product_search_documents` SELECT·INSERT·UPDATE), 예약 실행·종료 대기는 미검증입니다. 구 Store 실행기의 재구축과 동시에 켜지 않으며, 활성화 전 권한과 실행기 소유권을 확인해야 합니다. 운영·DB 변경은 없습니다.

## 결제 확인과 Store 결제 결과 처리

`pawbridge.payments.enabled=true`는 POST `/api/v1/payments/confirm`을 조립하고, 결제 결과를 Store 주문에 **직접 호출로** 반영한다(Kafka·outbox 없음). 요청·응답 형태는 원래와 같다. 이 기능은 기본 비활성이다.

흐름:
1. 트랜잭션 밖: reader로 주문을 조회해 **로그인 회원이 주문자인지(아니면 403)** 먼저 확인한다. 그다음 짧은 읽기 TX로 이 주문의 DONE 결제를 찾아 있으면 DONE으로 다시 응답하고(금액이 다르면 기존 오류), 없으면 금액이 같은지, **주문이 PENDING인지(아니면 409)** 확인한다. 다른 회원은 기존 결제의 paymentKey를 받을 수 없다. 이 검사는 모두 Toss 호출 전이다.
2. Toss 승인 호출은 트랜잭션·쓰기 연결을 잡지 않은 상태에서 한다.
3. 결과 반영은 Store writer 트랜잭션 하나다. 주문 행을 `SELECT ... FOR UPDATE`로 잠그고 PENDING·금액·이 주문의 기존 DONE 결제 여부를 다시 확인한 뒤 Payment INSERT와 주문 PAID를 함께 커밋한다. 일간 랭킹(Redis ZINCRBY)은 커밋과 writer 연결 반환이 끝난 뒤 트랜잭션 밖에서 실행하며, 실패해도 로그만 남기고 결제 응답·환불에 영향을 주지 않는다(느린 Redis가 writer 연결을 잡지 않는다).

| 경우 | 동작 |
| --- | --- |
| 승인 성공 | 결제 행 + 주문 PAID 한 번에 커밋, Toss 응답 그대로 200 |
| 고객 결제 거절(아래 허용목록 코드) | 주문이 PENDING이면 취소·재고 복구(검색 문서 포함) 후 200 `ABORTED`. 그 사이 다른 요청이 이미 결제했으면 그 DONE을 응답 |
| 요청 미처리(401, 429, 인증·가맹점 설정 코드) | 결제가 시도되지 않았으므로 주문 PENDING·재고 유지, 보상 기록 없음, 500 |
| 결과 모름(타임아웃·연결 오류·5xx, `ALREADY_PROCESSING_REQUEST`, `PROVIDER_ERROR`, 목록에 없거나 코드가 없는 4xx) | Toss 결제 조회 1회: 같은 주문의 DONE이면 성공 처리, 같은 주문의 ABORTED/EXPIRED/CANCELED면 거절 처리, 그 밖(다른 주문 포함)은 주문 PENDING 유지 + `UNKNOWN_RESULT` 기록 + 500 |
| S008(`ALREADY_PROCESSED_PAYMENT`) | 짧은 읽기 TX로 DB의 DONE 결제를 찾아 DONE 응답. 없으면 조회 1회: 같은 주문의 DONE이면 성공 처리, 그 밖은 주문을 건드리지 않고 `UNKNOWN_RESULT` 기록 + 500 |
| 동시 요청 | 주문 행 잠금으로 직렬화. 같은 paymentKey가 이미 DONE이면 멱등 성공, 다른 결제로 이미 PAID면 이 결제를 Toss 취소 |
| 반영 실패·재검사 거부 | 트랜잭션 밖에서 Toss 취소 후 500. 단 그 paymentKey가 DB에 DONE이면 취소하지 않고 DONE 응답. DB 확인이 안 되면 취소하지 않고 보상 기록 |
| 쓰기 연결 획득 실패 | 100ms·200ms 간격으로 최대 3회 시도한 뒤 위 행으로 |
| Toss 취소 실패 | 보상 기록(`CANCEL_FAILED`) + 500 |

분류는 응답 본문의 Toss `code` 허용목록이다(공식 오류 코드 문서의 결제 승인 항목 기준, 2026-10-22 확인, `JpaPayments.classify`). 주문을 취소하는 고객 거절은 `REJECT_*`·`INVALID_CARD_*`·`EXCEED_*`와 `INVALID_REJECT_CARD`, `INVALID_STOPPED_CARD`, `BELOW_MINIMUM_AMOUNT`, `NOT_ALLOWED_POINT_USE`, `NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT`, `NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN`, `RESTRICTED_TRANSFER_ACCOUNT`, `INVALID_ACCOUNT_INFO_RE_REGISTER`, `INVALID_PASSWORD`, `FDS_ERROR`, `NOT_AVAILABLE_BANK`, `NOT_AVAILABLE_PAYMENT`, `NOT_FOUND_PAYMENT_SESSION`뿐이다. 요청 미처리는 401·429와 `INVALID_API_KEY`, `UNAUTHORIZED_KEY`, `FORBIDDEN_REQUEST`, `INCORRECT_BASIC_AUTH_FORMAT`, `INVALID_AUTHORIZE_AUTH`, `NOT_FOUND_TERMINAL_ID`, `INVALID_UNREGISTERED_SUBMALL`, `NOT_REGISTERED_BUSINESS`이다. 문서 설명이 "잘못된 요청" 한 줄뿐인 `INVALID_REQUEST`는 승인 여부를 배제할 수 없어 "결과 모름"(조회 1회)으로 둔다. 새 코드는 기본적으로 "결과 모름"으로 처리되어 주문을 취소하지 않는다.

보상 기록은 `pawbridge_payment.payment_compensation_records`에 별도 짧은 TX로 저장하고(실패해도 응답 흐름은 그대로), 동시에 ERROR 로그 한 줄 `PAYMENT_COMPENSATION_REQUIRED paymentKey= orderId= amount= userId= stage= reason= recordId=`을 남긴다(저장 실패 시 `recordId=none`). Toss 원문·카드 정보·비밀값은 남기지 않는다. paymentKey·orderId는 영숫자·`_`·`-` 외 문자를 `_`로 바꾸고 200자로 자른다(Toss paymentKey 최대 200자). 기존 `payments.payment_key`는 VARCHAR(100)이라 긴 키의 INSERT가 실패할 수 있다(기존 한계, 이 경우 Toss 취소 경로).

원래 MSA와 다른 점: 주문 소유자·상태 검사를 추가했다. 승인 호출 중 트랜잭션을 잡지 않으며 타임아웃·5xx는 조회로 확인한다(원래는 모든 예외를 실패로 보고 주문을 취소). 반영 실패 시 주문은 PENDING으로 남는다(원래는 PAYMENT_FAILED 이벤트로 취소). 랭킹은 커밋·연결 반환 뒤 실행한다. Payment와 Store의 outbox(`PAYMENT_*`, `ORDER_PAID`, `SKU_UPDATED`)는 더 이상 쓰지 않는다.

구조: Payment 엔티티는 별도 JPA 단위 없이 Store writer 단위(`storeDirectOrderEntityManagerFactory`)에 `pawbridge.payments.enabled`일 때만 추가된다(`@Table(schema="pawbridge_payment")`). 같은 `storeDirectOrderJpaTransactionManager` 하나만 쓰므로 공유 pool 최대 1에서도 트랜잭션이 중첩되지 않는다. Store의 `completePayment`/`failPayment`는 호출자 트랜잭션을 필수로 요구한다(`PROPAGATION_MANDATORY`). `completePayment`는 랭킹 작업을 실행하지 않고 돌려주며 Payment가 커밋 뒤에 실행한다. 모의 결제의 `processPayment`는 자체 트랜잭션을 유지한다.

활성화 조건:
- `pawbridge.store-payment-events.enabled=true`가 함께 필요하며 없으면 시작이 실패한다. 이 flag는 랭킹용 Redis(`spring.data.redis.host` 또는 `memberChatRedis`)를 요구한다.
- 운영은 앱 계정 하나로 `pawbridge_store`와 `pawbridge_payment`를 모두 쓴다. writer 계정에 `pawbridge_payment` 스키마 USAGE, `payments` SELECT·INSERT, `payment_compensation_records` SELECT·INSERT와 `pawbridge_store.orders` 행 잠금·UPDATE, 재고·검색 문서 권한이 필요하다. reader는 `orders.user_id`를 읽는다.
- 공유 단일 pool(기본 최대 10)을 쓴다. 운영 동시 결제량에 맞춰 `PAWBRIDGE_DB_POOL_MAX_SIZE`를 정한다.
- 새 테이블과 `payments(order_id) WHERE status='DONE'` 부분 유니크 인덱스 DDL(`backend-app/db/cutover/payment-compensation-and-done-unique.sql`)을 전환 때 승인 후 수동 적용한다. 이 파일은 버전 번호가 없는 일회성 DDL이라 계약 시험 스키마에도 자동으로 들어가지 않는다. 기존 DONE 중복이 있으면 인덱스 생성이 실패하니 먼저 확인한다.
- `toss.secret-key`(필수)와 `toss.base-url`.

알려진 한계: 승인 응답이 DONE이 아닌 경우(가상계좌 `WAITING_FOR_DEPOSIT` 등)는 원래처럼 응답만 돌려주며 웹훅 처리는 없다. 서블릿 기본 오류 로그에 Toss 오류 본문이 남을 수 있다(비밀값은 없음).

단위·구성·Security/JWT MVC 시험은 stub Toss와 합성 트랜잭션 기준이다. 실제 PostgreSQL 잠금·커밋, 실 Toss·Redis 연결과 운영 활성화는 검증하지 않았다(통합 시험 과제).

### Store 모의 결제

`pawbridge.store-mock-payment.enabled=true`(대소문자 무시, `on` 등 다른 값은 비활성)에서만 원문 store-service `OrderController`의 POST `/api/v1/orders/{orderId}/payment`를 조립한다. 기본 off다. 업무는 이미 편입된 `StorePaymentEvents.processPayment`를 그대로 호출하므로 `pawbridge.store-payment-events.enabled=true`도 함께 켜야 하며, 그 업무 빈이 없으면 앱이 시작 시 실패한다(조용한 403으로 대체하지 않는다). 원문 `payments.enabled`(Toss 결제 확인)와는 별개 경로라 그 플래그에 묶지 않았다.

원문 Gateway는 `/api/orders/**`에 유효한 토큰만 요구했으므로 서명 검증한 회원이면 USER·ADMIN·SHELTER 모두 호출할 수 있다. 원문 그대로 주문 ID로 주문을 찾아 PENDING이 아니면 거부하고 Store writer 트랜잭션에서 PAID로 바꾼다. 원문의 `ORDER_PAID` outbox INSERT는 outbox 제거로 하지 않는다. 응답은 본문 없는 200, 없는 주문은 400 `C001`, PENDING이 아닌 주문은 500 `C004`, 숫자가 아닌 주문 ID는 400 `C005`다.

원문 결함은 고치지 않고 활성화 전 확인 항목으로 남긴다. **소유자·금액·실제 결제 확인 없이 아무 회원이나 다른 회원의 주문을 PAID로 바꿀 수 있다.** 프런트는 이 경로를 쓰지 않으며 운영에서 켜지 않는 것을 전제로 한다. 주문 행 잠금이 없고 UPDATE가 모든 열을 다시 쓰므로 Toss 결제 결과 처리와 경합할 수 있다(Toss ABORTED가 CANCELLED로 바꾸고 재고를 복구한 뒤 모의 결제가 PAID로 덮어쓰면 재고가 복구된 PAID 주문이 남고, 관리자 배송·상태 PATCH와 동시에 실행되면 바뀐 `delivery_status`를 이전 값으로 되돌릴 수 있다). 재고·랭킹은 바꾸지 않는다. 운영에서 켜면 주문 위조가 노출된다. 원문과 다른 점으로, 등록되지 않은 메서드·alias(`/api/orders/...`)·하위 경로는 원문 405/라우팅 대신 공통 보안 기본 거부 403이며, 원문 전역 advice의 문자셋 위반 C007 분기는 이 경로에서 입력 문자열이 없어 두지 않았다. 트랜잭션은 Store writer 기본 timeout 15초를 상속한다(원문은 timeout 없음).

활성화 전 선택한 writer에 `pawbridge_store` USAGE, `orders` SELECT·UPDATE(`@DynamicUpdate`가 없어 Hibernate가 갱신 가능한 모든 열과 `updated_at`을 다시 씀)가 필요하다(outbox 권한은 더 이상 필요 없다). 이 경로가 `store-payment-events.enabled`를 요구하므로 해당 절의 Redis(`spring.data.redis.host` 또는 `memberChatRedis`) 전제도 함께 필요하다. Store writer는 공유 `writeDataSource`(기본 최대 1개·연결 대기 2초)를 쓰므로 다른 쓰기와 연결을 다툴 수 있다. 단위·구성·실제 Security/JWT MVC 시험은 mock 업무 기준이며 실제 PostgreSQL 검증이 아니다.

### 실제 Main 조립과 격리 PostgreSQL 계약 검증

`OwnedPostgresqlMainCoreIntegrationTest`의 3사례는 실제 `PawBridgeApplication`과 JWT·SecurityFilterChain을 통해 User→Animal 즐겨찾기, User→Store 구매 내역, Community→User 작성자 조회를 검증한다. 읽기 전용 권한을 가진 별도 합성 PostgreSQL 자료에서 실제 저장소 조회, 트랜잭션 분리, 요청 후 연결 반환, 자료 불변과 정상 종료를 확인했다. 운영 데이터나 정상 결제·외부 서비스 성공을 검증한 결과는 아니다.

`OwnedPostgresqlShelterObservationTest`의 2사례는 별도 합성 PostgreSQL의 축소된 3개 업무 테이블에서 실제 관측 기록 SQL과 기존 통계 조회 SQL을 실행한다. DB의 한국 날짜, 빈 보호소와 PROTECT 수, 기존 관측 보존, 같은 날짜 재기록의 중복 방지, 트랜잭션 롤백과 서버의 읽기 전용 쓰기 거부를 확인했다. 이 시험은 실제 Boot 구성의 recorder 조립 메서드를 직접 사용하며 Main·04시 스케줄러·HTTP를 기동하지 않는다. 관측 시험 2사례의 추가는 시험·SQL만의 변경이며 결제·상품·Animal 생산 코드를 바꾸지 않는다.

Animal·Store·Payment 생산 코드를 합친 최종 조립에서는 기존 Main 무DB 2사례와 위 Main PostgreSQL 3사례, 총 5사례를 한 번 재검증했다. 새 고유 5사례로 합산하지 않는다. Payment→Store 공개 계약·내부 참조 거부·6모듈 의존 그래프의 선택 구조 시험 3사례, 현재 architecture/business-package 검사, 실행 JAR 생성도 통과했다. 이 근거는 전체 TestKit 시험 성공이나 실행 JAR 기동·Kafka 소비·Redis·Toss·R2·AI·운영 배포 성공을 뜻하지 않는다. 관측 PostgreSQL 2사례는 이 최종 조립 검증 뒤 별도로 최초 실행됐다.

두 PostgreSQL 시험은 명시적인 전용 설정 선택자와 소유권·권한·기준 자료 검증을 요구하며 입력이 없거나 잘못되면 건너뛰거나 다른 DB로 대체하지 않고 실패한다. 게시하는 파일은 시험 Java 2개와 합성 스키마 SQL 2개다. 자료 준비 helper, 동적 설정, 인증정보, 소유권 상태와 덤프는 게시하지 않는다.
