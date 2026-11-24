#!/usr/bin/env bash
# 빈 시험 DB에 스키마를 만든다. 순서는 계약 시험(ContractPostgres)과 같고, 권한은 운영에서 뽑은 app-grants.sql이다.
# 확장(vector, pg_trgm) → user, community, animal, store 스키마별 V 파일을 번호 순으로 → 앱 역할 → 권한.
# payment 스키마는 결제를 끈 시험 환경에서 쓰지 않아 계약 시험처럼 만들지 않는다.
# 사용: deploy/stage/init-db.sh  (저장소 루트에서, 시험 환경 postgres가 떠 있을 때)
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
container=pawbridge-stage-postgres
sql_root="$root/backend-app/db/postgresql"
grants="$root/deploy/stage/app-grants.sql"

psql_stage() {
    docker exec -i "$container" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d pawbridge "$@"
}

existing=$(psql_stage -tA -c "SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'pawbridge\_%'")
if [ "$existing" != "0" ]; then
    echo "이미 스키마가 있습니다($existing개). 다시 만들려면 시험 환경 볼륨을 지우고 처음부터 합니다." >&2
    exit 1
fi

# V 파일 이름을 번호 순으로(V10이 V9 뒤에) 늘어놓는다.
migrations() {
    find "$sql_root/$1" -maxdepth 1 -name 'V*__*.sql' -exec basename {} \; | sort -t_ -k1.2 -n
}

{
    echo "CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;"
    echo "CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;"
    for schema in user community animal store; do
        echo "CREATE SCHEMA pawbridge_$schema;"
        # 앞쪽 V 파일은 스키마 없이 테이블 이름만 쓰므로 소유 스키마를 검색 경로 맨 앞에 둔다.
        echo "SET search_path TO pawbridge_$schema;"
        for file in $(migrations "$schema"); do
            echo "\\echo $schema/$file"
            cat "$sql_root/$schema/$file"
            echo
        done
    done
    echo "RESET search_path;"
    # 역할은 V 파일 뒤에 만든다. user V4는 역할이 있으면 테이블 전체 권한을 줘서 열 단위 회수를 덮는다.
    echo "\\set app_password \`cat /run/stage/app-password\`"
    echo "CREATE ROLE pawbridge_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;"
    cat "$grants"
} | psql_stage

echo "스키마 생성 끝:"
psql_stage -tA -c "SELECT nspname || ' ' || count(c.oid) FROM pg_namespace n LEFT JOIN pg_class c ON c.relnamespace = n.oid AND c.relkind = 'r' WHERE nspname LIKE 'pawbridge\_%' GROUP BY nspname ORDER BY 1"
