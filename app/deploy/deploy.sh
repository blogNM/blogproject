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

# 컨테이너 안의 localhost는 서버가 아니라 컨테이너 자신이라, 서버의 MySQL은 host.docker.internal 로 부른다.
DB_HOST="$DB_ADDRESS"
ADD_HOST=()
case "$DB_HOST" in
  localhost|127.0.0.1)
    DB_HOST="host.docker.internal"
    ADD_HOST=(--add-host "host.docker.internal:host-gateway")
    ;;
esac

BASE_URL="${APP_BASE_URL:-http://localhost:$APP_PORT}"
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
    -p "${APP_PORT}:8080" \
    --env-file "$APP_HOME/secrets.env" --env-file "$APP_HOME/runtime.env" \
    -v "$APP_HOME/data/uploads:/app/data/uploads" \
    --user "$(id -u):$(id -g)" \
    "${ADD_HOST[@]}" \
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
