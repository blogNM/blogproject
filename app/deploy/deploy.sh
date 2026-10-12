#!/usr/bin/env bash
# 서버에서 도커 이미지를 받아 Creat_ 를 다시 띄운다. GitHub Actions가 SSH로 접속해 실행한다.
#
#   bash deploy.sh <이미지 파일(.tar.gz)>
#
# 받는 환경변수 (GitHub 시크릿·변수에서 넘어옴)
#   APP_PORT (기본 8340)
#   꼭: DB_ADDRESS, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD
#   선택: APP_BASE_URL, MAIL_USERNAME, MAIL_PASSWORD, ADMIN_INITIAL_LOGIN_ID, ADMIN_INITIAL_PASSWORD,
#         TURNSTILE_SITE_KEY, TURNSTILE_SECRET_KEY, COOKIE_SECURE, JAVA_OPTS
#
# 서버 폴더 (APP_HOME, 기본 ~/creat-blog)
#   data/uploads   올린 사진. 컨테이너를 바꿔도 남는다 (백업 대상)
#   secrets.env    JWT_SECRET·CODE_HASH_SECRET. 처음 한 번 만들고 계속 쓴다 (바꾸면 모두 로그아웃)
#   runtime.env    이번 배포의 DB·메일 설정. 배포할 때마다 새로 쓴다
set -euo pipefail

IMAGE_FILE="${1:?이미지 파일 경로를 넘겨 주세요}"
APP_PORT="${APP_PORT:-8340}"
APP_NAME="${APP_NAME:-creat-blog}"
APP_HOME="${APP_HOME:-$HOME/creat-blog}"
IMAGE="${APP_NAME}:latest"
DOCKER="${DOCKER:-docker}"

need() { [ -n "${!1:-}" ] || { echo "환경변수 $1 이(가) 비어 있습니다. GitHub 시크릿을 확인해 주세요." >&2; exit 1; }; }
for v in DB_ADDRESS DB_PORT DB_NAME DB_USERNAME DB_PASSWORD; do need "$v"; done
case "$APP_PORT" in ''|*[!0-9]*) echo "APP_PORT는 숫자여야 합니다." >&2; exit 1 ;; esac

if ! $DOCKER info >/dev/null 2>&1; then
  echo "docker를 쓸 수 없습니다. 이 계정이 docker 그룹에 있는지 확인해 주세요." >&2
  exit 1
fi

umask 077
mkdir -p "$APP_HOME/data/uploads"

# 로그인 토큰·인증번호 서명 키. 서버에만 두고 GitHub에는 올리지 않는다.
if [ ! -s "$APP_HOME/secrets.env" ]; then
  echo "secrets.env 를 처음 만듭니다."
  {
    echo "JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '\n/+=')"
    echo "CODE_HASH_SECRET=$(head -c 32 /dev/urandom | base64 | tr -d '\n/+=')"
  } > "$APP_HOME/secrets.env"
fi

# 컨테이너는 서버의 네트워크를 그대로 쓴다 (--network host). 학교 서버처럼 도커 내부망에서
# DB로 나가는 길이 막혀 있어도 서버에서 되는 접속은 컨테이너에서도 된다. localhost도 서버 자신이다.
DB_HOST="$DB_ADDRESS"
if (exec 3<>"/dev/tcp/${DB_HOST}/${DB_PORT}") 2>/dev/null; then
  echo "서버에서 DB 포트에 접속됩니다."
else
  echo "경고: 서버에서도 DB 포트에 접속되지 않습니다. DB_ADDRESS, DB_PORT 시크릿을 확인하세요." >&2
fi

# 손으로 돌린 배포에서 reset_db 에 reset 이라고 적었을 때만: DB의 테이블을 모두 지운다 (되돌릴 수 없음).
# 앱(Flyway)은 빈 DB에서 테이블을 처음부터 만든다. 다른 도구로 만든 테이블이 있으면 앱이 뜨지 않는다.
if [ "${RESET_DB:-}" = "reset" ]; then
  echo "DB의 테이블을 모두 지웁니다."
  $DOCKER run --rm --network host -e MYSQL_PWD="$DB_PASSWORD" "${MYSQL_CLIENT_IMAGE:-mysql:8.0}" sh -c '
    set -e
    M="mysql -h$0 -P$1 -u$2 --protocol=TCP -N"
    T=$($M -e "SET SESSION group_concat_max_len=100000; SELECT GROUP_CONCAT(CONCAT(CHAR(96),table_name,CHAR(96))) FROM information_schema.tables WHERE table_schema=\"$3\"")
    if [ -n "$T" ] && [ "$T" != "NULL" ]; then
      $M "$3" -e "SET FOREIGN_KEY_CHECKS=0; DROP TABLE $T;"
    fi
    echo "남은 테이블 수: $($M -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"$3\"")"
  ' "$DB_HOST" "$DB_PORT" "$DB_USERNAME" "$DB_NAME"
fi

# 학교 서버는 포트를 밖에 바로 열지 않는다. 공용 Nginx가 도메인으로 받은 요청을 내 포트로 넘긴다.
# APP_DOMAIN이 있으면 ~/nginx/<도메인>.conf 를 만든다. 파일이 새로 생기면 강사에게 적용을 요청해야 반영된다.
# 이미 있는 파일은 덮어쓰지 않는다 (배포할 때마다 강사에게 다시 부탁하지 않도록).
if [ -n "${APP_DOMAIN:-}" ]; then
  NGINX_CONF="$HOME/nginx/${APP_DOMAIN}.conf"
  # 로그 폴더(~/nginx/logs)는 학교가 만들어 줄 때만 있다. 없는 폴더를 적으면 Nginx가 설정을 읽지 못하므로 그때는 로그 줄을 뺀다.
  LOG_LINES=""
  if [ -d "$HOME/nginx/logs" ]; then
    LOG_LINES="    access_log $HOME/nginx/logs/${APP_DOMAIN}.access.log;
    error_log  $HOME/nginx/logs/${APP_DOMAIN}.error.log;
"
  fi
  NEW_CONF="$(cat <<NGINX
server {
    listen 80;
    server_name ${APP_DOMAIN};

${LOG_LINES}
    client_max_body_size 20M;

    location / {
        proxy_pass http://127.0.0.1:${APP_PORT};

        proxy_http_version 1.1;
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        proxy_set_header Upgrade    \$http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_read_timeout 60s;
    }
}
NGINX
)"
  if [ ! -d "$HOME/nginx" ]; then
    echo "경고: $HOME/nginx 폴더가 없어 Nginx 설정을 만들지 못했습니다." >&2
  elif [ -f "$NGINX_CONF" ] && [ "$(cat "$NGINX_CONF")" = "$NEW_CONF" ]; then
    echo "Nginx 설정은 그대로입니다: $NGINX_CONF"
  elif [ -f "$NGINX_CONF" ]; then
    # 이미 적용된 파일을 바꾸면 강사가 다시 적용해야 하므로, 있는 파일은 건드리지 않는다. 새로 만들려면 파일을 지우고 배포한다.
    echo "Nginx 설정 파일이 이미 있어 그대로 둡니다: $NGINX_CONF (새로 만들려면 지우고 다시 배포)"
  else
    printf '%s\n' "$NEW_CONF" > "$NGINX_CONF"
    echo "Nginx 설정을 만들었습니다: $NGINX_CONF  → 강사에게 파일 이름(${APP_DOMAIN}.conf)을 알려 적용을 요청하세요."
  fi
  # Nginx를 거쳐 들어오므로 손님 IP·주소는 X-Forwarded-* 헤더에서 읽는다 (요청 제한이 IP별로 맞게 동작).
  FORWARD_HEADERS_STRATEGY="${FORWARD_HEADERS_STRATEGY:-native}"
fi
BASE_URL="${APP_BASE_URL:-http://${APP_DOMAIN:-localhost:$APP_PORT}}"
# https가 아니면 로그인 쿠키에 Secure를 붙이면 로그인이 안 된다.
if [ -z "${COOKIE_SECURE:-}" ]; then
  case "$BASE_URL" in https://*) COOKIE_SECURE=true ;; *) COOKIE_SECURE=false ;; esac
fi
# 메일 계정이 없으면 메일을 보내지 않고 서버 로그에 찍는다.
MAIL_MODE=log
[ -n "${MAIL_USERNAME:-}" ] && [ -n "${MAIL_PASSWORD:-}" ] && MAIL_MODE=smtp

{
  echo "DB_URL=jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true"
  echo "DB_USERNAME=${DB_USERNAME}"
  echo "DB_PASSWORD=${DB_PASSWORD}"
  echo "APP_BASE_URL=${BASE_URL}"
  echo "COOKIE_SECURE=${COOKIE_SECURE}"
  [ -n "${FORWARD_HEADERS_STRATEGY:-}" ] && echo "FORWARD_HEADERS_STRATEGY=${FORWARD_HEADERS_STRATEGY}"
  echo "MAIL_MODE=${MAIL_MODE}"
  [ -n "${MAIL_USERNAME:-}" ] && echo "MAIL_USERNAME=${MAIL_USERNAME}"
  [ -n "${MAIL_PASSWORD:-}" ] && echo "MAIL_PASSWORD=${MAIL_PASSWORD}"
  [ -n "${ADMIN_INITIAL_LOGIN_ID:-}" ] && echo "ADMIN_INITIAL_LOGIN_ID=${ADMIN_INITIAL_LOGIN_ID}"
  [ -n "${ADMIN_INITIAL_PASSWORD:-}" ] && echo "ADMIN_INITIAL_PASSWORD=${ADMIN_INITIAL_PASSWORD}"
  [ -n "${TURNSTILE_SITE_KEY:-}" ] && echo "TURNSTILE_SITE_KEY=${TURNSTILE_SITE_KEY}"
  [ -n "${TURNSTILE_SECRET_KEY:-}" ] && echo "TURNSTILE_SECRET_KEY=${TURNSTILE_SECRET_KEY}"
  [ -n "${JAVA_OPTS:-}" ] && echo "JAVA_OPTS=${JAVA_OPTS}"
  true
} > "$APP_HOME/runtime.env"

# 지난 이미지를 previous로 남겨 두고 새 이미지를 읽는다. 새 것이 안 뜨면 previous로 되돌린다.
if $DOCKER image inspect "$IMAGE" >/dev/null 2>&1; then
  $DOCKER tag "$IMAGE" "${APP_NAME}:previous"
fi
echo "이미지를 읽습니다: $IMAGE_FILE"
gunzip -c "$IMAGE_FILE" | $DOCKER load

start() {
  $DOCKER rm -f "$APP_NAME" >/dev/null 2>&1 || true
  $DOCKER run -d --name "$APP_NAME" --restart unless-stopped \
    --network host -e "SERVER_PORT=${APP_PORT}" \
    --env-file "$APP_HOME/secrets.env" --env-file "$APP_HOME/runtime.env" \
    -v "$APP_HOME/data/uploads:/app/data/uploads" \
    --user "$(id -u):$(id -g)" \
    "$1" >/dev/null
}

healthy() {
  for _ in $(seq 1 45); do
    if [ "$($DOCKER inspect -f '{{.State.Running}}' "$APP_NAME" 2>/dev/null)" != "true" ]; then
      return 1
    fi
    if command -v curl >/dev/null 2>&1; then
      curl -fsS -o /dev/null "http://localhost:${APP_PORT}/" 2>/dev/null && return 0
    elif (exec 3<>"/dev/tcp/127.0.0.1/${APP_PORT}") 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

echo "컨테이너를 띄웁니다 (포트 ${APP_PORT})."
start "$IMAGE"
if healthy; then
  echo "배포 완료: ${BASE_URL}"
  rm -f "$IMAGE_FILE"
  $DOCKER image prune -f >/dev/null 2>&1 || true
  exit 0
fi

echo "새 컨테이너가 뜨지 않았습니다. 마지막 로그:" >&2
$DOCKER logs --tail 80 "$APP_NAME" >&2 || true
if $DOCKER image inspect "${APP_NAME}:previous" >/dev/null 2>&1; then
  echo "이전 이미지로 되돌립니다." >&2
  start "${APP_NAME}:previous"
fi
exit 1
