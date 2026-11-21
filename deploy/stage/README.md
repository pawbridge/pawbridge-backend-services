# 시험 환경

운영과 같은 이미지로 monolith, PostgreSQL, Redis를 따로 띄운다. 빈 DB에 스키마만 만들고 시험 회원을 넣는다.
이 Mac 안(`127.0.0.1:38500`, 관리 포트 `38501`)에서만 열리고, 외부 수집, 결제, 소셜 로그인, AI 서비스 연동은 꺼져 있다.

## 처음 띄우기

저장소 루트에서 실행한다.

```bash
deploy/stage/make-secrets.sh <비밀 폴더>          # 비밀값 새로 만들기(있으면 그대로 둠)
cp deploy/stage/stage.env.example <stage.env>      # 이미지와 비밀 폴더 경로 채우기
docker compose -f deploy/stage/compose.yaml --env-file <stage.env> up -d postgres redis
deploy/stage/init-db.sh                            # 스키마와 앱 역할, 권한
deploy/stage/seed-members.sh 2                     # 시험 회원
docker compose -f deploy/stage/compose.yaml --env-file <stage.env> up -d monolith
node deploy/stage/smoke.mjs <비밀 폴더>               # 로그인, 채팅 연결, 메시지 1건 주고받기
```

시험 회원은 `stage-member-0001@stage.pawbridge.test`부터이고 비밀번호는 비밀 폴더의 `member-password`다.

## 이미지 바꾸기와 끄기

- 다른 monolith 이미지로 시험하려면 `MONOLITH_IMAGE`를 바꾸고 `up -d monolith`.
- 끄기: `docker compose -f deploy/stage/compose.yaml --env-file <stage.env> down`(DB 볼륨은 남는다).
- 처음부터 다시: `down` 뒤 `docker volume rm pawbridge-stage-postgres-data`, 그리고 위 순서.

## 권한 다시 뽑기

운영 앱 역할 권한이 바뀌면 `export-grants.sql`을 운영 DB에서 읽기 전용으로 실행해 `app-grants.sql`의 GRANT 줄을 바꾼다.
결과는 GRANT 문뿐이고 비밀값은 없다.

## 운영과 다른 점

- 앱 역할 권한(`app-grants.sql`)은 운영 DB에서 뽑은 실제 권한이다. 표 소유자는 다르다(운영은 스키마별 `*_migration` 역할, 시험 환경은 `postgres`).
- payment 스키마는 만들지 않는다(결제 꺼짐).
- 데이터가 비어 있어 동물, 보호소 화면은 빈 결과다.
