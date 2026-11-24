#!/usr/bin/env bash
# 시험 환경에 비밀번호 로그인용 시험 회원을 만든다. 시험 환경 DB에만 쓴다.
# 이메일은 stage-member-<번호>@stage.pawbridge.test, 비밀번호는 비밀 폴더의 member-password(모두 같음).
# 이미 있는 번호는 건너뛴다. 비밀번호 해시는 BCrypt(pgcrypto의 bf)이고, pgcrypto는 시험 환경 전용 stage_tools 스키마에 둔다.
# 사용: deploy/stage/seed-members.sh <회원 수>
set -euo pipefail

count=${1:?회원 수가 필요합니다}
case $count in
    '' | *[!0-9]*) echo "회원 수는 숫자여야 합니다" >&2; exit 1 ;;
esac
if [ "$count" -lt 1 ] || [ "$count" -gt 1000 ]; then
    echo "회원 수는 1~1000입니다" >&2
    exit 1
fi

container=pawbridge-stage-postgres
if [ "$(docker inspect -f '{{.Config.Image}}' "$container" 2>/dev/null)" = "" ]; then
    echo "$container 컨테이너가 없습니다" >&2
    exit 1
fi

docker exec -i "$container" psql -X -q -v ON_ERROR_STOP=1 -v count="$count" -U postgres -d pawbridge <<'SQL'
CREATE SCHEMA IF NOT EXISTS stage_tools;
CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA stage_tools;
\set member_password `cat /run/stage/member-password`
-- 해시는 한 번만 만들어 모든 회원에 쓴다(회원마다 bf 10을 돌리면 수백 명에 수십 초가 걸린다).
WITH hash AS (
    SELECT stage_tools.crypt(:'member_password', stage_tools.gen_salt('bf', 10)) AS value
)
INSERT INTO pawbridge_user.users (email, name, nickname, password, provider, role, created_at, updated_at)
SELECT format('stage-member-%s@stage.pawbridge.test', lpad(n::text, 4, '0')),
       format('시험회원%s', lpad(n::text, 4, '0')),
       format('stage%s', lpad(n::text, 4, '0')),
       hash.value, 'LOCAL', 'ROLE_USER', now(), now()
FROM generate_series(1, :count) AS n, hash
ON CONFLICT DO NOTHING;
SELECT count(*) AS stage_members FROM pawbridge_user.users WHERE email LIKE 'stage-member-%@stage.pawbridge.test';
SQL
