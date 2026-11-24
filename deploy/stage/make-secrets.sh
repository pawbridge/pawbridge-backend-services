#!/usr/bin/env bash
# 시험 환경 비밀값을 새로 만든다. 운영 값은 쓰지 않고, 값은 화면에 출력하지 않는다.
# 이미 있는 파일은 건드리지 않는다(DB를 만든 뒤 비밀번호가 바뀌면 접속이 끊기므로).
# 사용: deploy/stage/make-secrets.sh <비밀 폴더>
set -euo pipefail

dir=${1:?비밀 폴더 경로가 필요합니다}
umask 077
mkdir -p "$dir"
chmod 700 "$dir"

random() {
    openssl rand -hex "$1"
}

write_once() {
    local name=$1
    local value=$2
    if [ -e "$dir/$name" ]; then
        echo "있음: $name"
        return
    fi
    printf '%s' "$value" > "$dir/$name"
    echo "만듦: $name"
}

write_once postgres-password "$(random 24)"
write_once app-password "$(random 24)"
write_once member-password "$(random 12)"

if [ ! -e "$dir/redis.conf" ]; then
    redis_password=$(random 24)
    printf 'requirepass %s\nappendonly no\nsave ""\n' "$redis_password" > "$dir/redis.conf"
    echo "만듦: redis.conf"
else
    echo "있음: redis.conf"
fi

if [ ! -e "$dir/monolith.env" ]; then
    redis_password=$(sed -n 's/^requirepass //p' "$dir/redis.conf")
    {
        printf 'PAWBRIDGE_DB_PASSWORD=%s\n' "$(cat "$dir/app-password")"
        printf 'SPRING_DATA_REDIS_PASSWORD=%s\n' "$redis_password"
        # HMAC 서명 키는 32바이트 이상이어야 한다.
        printf 'PAWBRIDGE_JWT_SECRET=%s\n' "$(random 48)"
        printf 'PAWBRIDGE_VISITS_SIGNING_KEY=%s\n' "$(random 32)"
    } > "$dir/monolith.env"
    echo "만듦: monolith.env"
else
    echo "있음: monolith.env"
fi
