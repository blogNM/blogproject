#!/usr/bin/env bash
# 서버에서 도커 이미지를 받아 Creat_ 를 다시 띄운다. GitHub Actions가 SSH로 접속해 실행한다.
#
#   bash deploy.sh <이미지 파일(.tar.gz)>
#
# 서버 두 대·무중단 배포 (D-111). 학교 Nginx는 지금처럼 APP_PORT(8340) 하나만 보면 된다 (강사 reload 필요 없음).
#
#   학교 Nginx(443) → 8340 우리 Nginx(creat-blog-lb, nginx:alpine) ─┬→ 8341 creat-blog-a (app-a, 새벽 정리 작업 돈다)
#                                                                    └→ 8342 creat-blog-b (app-b, 정리 작업 끔)
#
# 배포할 때마다: 앱을 한 대씩 새 이미지로 바꾼다 (롤링). b를 바꾸고 /api/health가 200이 되면 SETTLE_SECONDS(10초) 기다린 뒤 a를 바꾼다.
# 바꾸는 동안 우리 Nginx가 살아 있는 다른 한 대로 보낸다. 우리 Nginx는 계속 켜 두고, 설정이 바뀌었을 때만 검사(nginx -t) 후 reload 한다.
# 새 이미지가 뜨지 않으면 그 자리를 바로 전 컨테이너로 되돌리고 멈춘다 (다른 한 대는 그대로 손님을 받는다).
#
# 처음 한 번 (한 대짜리 creat-blog(8340) → 위 구조): b(8342)를 띄우고, 손으로 띄운 creat-blog-2(8341)를 a로 바꾸고,
# 우리 Nginx를 빈 포트(8349)에서 먼저 시험한 뒤, creat-blog를 내리자마자 8340에 우리 Nginx를 띄운다 (끊김 1~2초).
# 중간에 실패하면 creat-blog를 다시 켜서 8340은 계속 예전처럼 동작한다.
#
# 받는 환경변수 (GitHub 시크릿·변수에서 넘어옴)
#   APP_PORT (기본 8340): 학교 Nginx가 보내는 포트. 우리 Nginx가 듣는다
#   APP_PORT_A (기본 APP_PORT+1), APP_PORT_B (기본 APP_PORT+2): 앱 두 대. LB_CHECK_PORT (기본 APP_PORT+9): 처음 시험용
#   꼭: DB_ADDRESS, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD
#   선택: APP_BASE_URL, MAIL_USERNAME, MAIL_PASSWORD, ADMIN_INITIAL_LOGIN_ID, ADMIN_INITIAL_PASSWORD,
#         TURNSTILE_SITE_KEY, TURNSTILE_SECRET_KEY, COOKIE_SECURE, JAVA_OPTS
#
# 서버 폴더 (APP_HOME, 기본 ~/creat-blog)
#   data/uploads   올린 사진. 두 앱이 같이 쓴다. 컨테이너를 바꿔도 남는다 (백업 대상)
#   secrets.env    JWT_SECRET·CODE_HASH_SECRET. 처음 한 번 만들고 계속 쓴다 (바꾸면 모두 로그아웃)
#                  RELEASE_NOTICE_TOKEN: 릴리스 워크플로가 공지를 올릴 때 쓰는 값 (D-97). 없으면 한 번 붙인다
#   runtime.env    이번 배포의 DB·메일 설정. 배포할 때마다 새로 쓴다
#   lb/default.conf  우리 Nginx 설정. 이 스크립트가 만든다 (손으로 고치지 말고 이 스크립트를 고친다)
set -euo pipefail

IMAGE_FILE="${1:?이미지 파일 경로를 넘겨 주세요}"
APP_PORT="${APP_PORT:-8340}"
APP_NAME="${APP_NAME:-creat-blog}"
APP_HOME="${APP_HOME:-$HOME/creat-blog}"
IMAGE="${APP_NAME}:latest"
DOCKER="${DOCKER:-docker}"
LB_IMAGE="${LB_IMAGE:-nginx:alpine}"
# 앱이 받던 요청(글 저장·사진 올리기)을 마칠 때까지 기다리는 시간(초). docker stop은 이보다 넉넉히 기다린다
SHUTDOWN_SECONDS="${SHUTDOWN_SECONDS:-20}"
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"
# 우리 Nginx가 실패한 앱을 빼 두는 시간(초)과, 한 대를 바꾼 뒤 다음 한 대를 내리기 전에 기다리는 시간(초).
# 바로 다음 한 대를 내리면 두 대 모두 "실패"로 보여 502(no live upstreams)가 난다 (학교 서버에서 약 4초 겪음)
FAIL_TIMEOUT="${FAIL_TIMEOUT:-5}"
SETTLE_SECONDS="${SETTLE_SECONDS:-10}"
# 처음 한 번 8340을 우리 Nginx로 바꾸기 전에 기다리는 시간(초). 학교 Nginx가 8341도 가리키게 되면(강사가 reload)
# 그쪽 기본 fail_timeout(10초) 동안 방금 바꾼 8341을 빼 두므로, 그보다 길게 기다린 뒤 8340을 잠깐 비운다
SWAP_SETTLE_SECONDS="${SWAP_SETTLE_SECONDS:-12}"
# 새 앱이 준비되기를 기다리는 횟수 (2초마다)
READY_TRIES="${READY_TRIES:-60}"
# 앱은 이 서버 안(우리 Nginx)에서만 받는다. 비우면 모든 주소에서 받는다
APP_BIND="${APP_BIND-127.0.0.1}"
# 글에 넣는 사진 한 번에 10MB(UPLOAD_MAX_REQUEST_SIZE)보다 크게
LB_MAX_BODY="${LB_MAX_BODY:-20m}"

need() { [ -n "${!1:-}" ] || { echo "환경변수 $1 이(가) 비어 있습니다. GitHub 시크릿을 확인해 주세요." >&2; exit 1; }; }
for v in DB_ADDRESS DB_PORT DB_NAME DB_USERNAME DB_PASSWORD; do need "$v"; done
num() { case "${!1}" in ''|*[!0-9]*) echo "$1 은(는) 숫자여야 합니다." >&2; exit 1 ;; esac; }
num APP_PORT
APP_PORT_A="${APP_PORT_A:-$((APP_PORT + 1))}"
APP_PORT_B="${APP_PORT_B:-$((APP_PORT + 2))}"
LB_CHECK_PORT="${LB_CHECK_PORT:-$((APP_PORT + 9))}"
for v in APP_PORT_A APP_PORT_B LB_CHECK_PORT SHUTDOWN_SECONDS STOP_TIMEOUT FAIL_TIMEOUT SETTLE_SECONDS SWAP_SETTLE_SECONDS READY_TRIES; do num "$v"; done
if [ "$(printf '%s\n' "$APP_PORT" "$APP_PORT_A" "$APP_PORT_B" "$LB_CHECK_PORT" | sort -u | wc -l)" -ne 4 ]; then
  echo "APP_PORT, APP_PORT_A, APP_PORT_B, LB_CHECK_PORT는 모두 달라야 합니다." >&2
  exit 1
fi
if [ "$STOP_TIMEOUT" -le "$SHUTDOWN_SECONDS" ]; then
  STOP_TIMEOUT=$((SHUTDOWN_SECONDS + 10))
  echo "STOP_TIMEOUT을 SHUTDOWN_SECONDS보다 길게 ${STOP_TIMEOUT}초로 바꿉니다."
fi
if [ "$SETTLE_SECONDS" -le "$FAIL_TIMEOUT" ]; then
  SETTLE_SECONDS=$((FAIL_TIMEOUT + 5))
  echo "SETTLE_SECONDS를 FAIL_TIMEOUT보다 길게 ${SETTLE_SECONDS}초로 바꿉니다."
fi

if ! $DOCKER info >/dev/null 2>&1; then
  echo "docker를 쓸 수 없습니다. 이 계정이 docker 그룹에 있는지 확인해 주세요." >&2
  exit 1
fi

umask 077
mkdir -p "$APP_HOME/data/uploads"

# 배포 두 개가 겹치지 않게 한다 (워크플로 concurrency와 별개로, 손으로 돌릴 때도)
if command -v flock >/dev/null 2>&1; then
  exec 9>"$APP_HOME/deploy.lock"
  if ! flock -n 9; then
    echo "다른 배포가 돌고 있습니다. 끝난 뒤 다시 돌려 주세요." >&2
    exit 1
  fi
fi

# 로그인 토큰·인증번호 서명 키. 서버에만 두고 GitHub에는 올리지 않는다.
if [ ! -s "$APP_HOME/secrets.env" ]; then
  echo "secrets.env 를 처음 만듭니다."
  {
    echo "JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '\n/+=')"
    echo "CODE_HASH_SECRET=$(head -c 32 /dev/urandom | base64 | tr -d '\n/+=')"
  } > "$APP_HOME/secrets.env"
fi
# 릴리스 공지용 값 (D-97). 예전에 만든 secrets.env에는 없으므로 따로 붙인다. release.yml이 서버에서 이 파일을 읽어 쓴다.
if ! grep -q '^RELEASE_NOTICE_TOKEN=' "$APP_HOME/secrets.env"; then
  echo "RELEASE_NOTICE_TOKEN=$(head -c 32 /dev/urandom | base64 | tr -d '\n/+=')" >> "$APP_HOME/secrets.env"
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
# 학교 Nginx는 APP_PORT(우리 Nginx) 하나만 가리키면 된다. 사용자가 손으로 upstream(8340, 8341)으로 고친 파일도 그대로 둔다:
# 강사가 그 파일을 적용하면 8341(app-a)로 바로 가는 요청도 생기는데, 그래도 문제없다 (D-111).
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
# 강사가 인증서(Certbot)를 붙이면 설정 파일에 "listen 443"이 생긴다. 그때부터 사이트 주소는 https다.
SCHEME=http
if [ -n "${APP_DOMAIN:-}" ] && grep -q "listen 443" "$HOME/nginx/${APP_DOMAIN}.conf" 2>/dev/null; then
  SCHEME=https
fi
BASE_URL="${APP_BASE_URL:-${SCHEME}://${APP_DOMAIN:-localhost:$APP_PORT}}"
# https가 아니면 로그인 쿠키에 Secure를 붙이면 로그인이 안 된다.
if [ -z "${COOKIE_SECURE:-}" ]; then
  case "$BASE_URL" in https://*) COOKIE_SECURE=true ;; *) COOKIE_SECURE=false ;; esac
fi
# 시크릿에 붙여 넣다 들어간 공백을 뺀다. Gmail 앱 비밀번호는 "abcd efgh ijkl mnop"처럼 띄어 보여 주지만 실제 값은 16자다.
MAIL_USERNAME="$(printf '%s' "${MAIL_USERNAME:-}" | tr -d '[:space:]')"
MAIL_PASSWORD="$(printf '%s' "${MAIL_PASSWORD:-}" | tr -d '[:space:]')"
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
  [ -n "${GOOGLE_CLIENT_ID:-}" ] && echo "GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}"
  [ -n "${GOOGLE_CLIENT_SECRET:-}" ] && echo "GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}"
  [ -n "${NAVER_CLIENT_ID:-}" ] && echo "NAVER_CLIENT_ID=${NAVER_CLIENT_ID}"
  [ -n "${NAVER_CLIENT_SECRET:-}" ] && echo "NAVER_CLIENT_SECRET=${NAVER_CLIENT_SECRET}"
  [ -n "${KAKAO_CLIENT_ID:-}" ] && echo "KAKAO_CLIENT_ID=${KAKAO_CLIENT_ID}"
  [ -n "${KAKAO_CLIENT_SECRET:-}" ] && echo "KAKAO_CLIENT_SECRET=${KAKAO_CLIENT_SECRET}"
  [ -n "${JAVA_OPTS:-}" ] && echo "JAVA_OPTS=${JAVA_OPTS}"
  true
} > "$APP_HOME/runtime.env"

# 지난 이미지를 previous로 남겨 두고 새 이미지를 읽는다.
if $DOCKER image inspect "$IMAGE" >/dev/null 2>&1; then
  $DOCKER tag "$IMAGE" "${APP_NAME}:previous"
fi
echo "이미지를 읽습니다: $IMAGE_FILE"
gunzip -c "$IMAGE_FILE" | $DOCKER load
# 우리 Nginx 이미지. 없을 때만 받는다. 못 받으면 아무것도 건드리지 않고 멈춘다
if ! $DOCKER image inspect "$LB_IMAGE" >/dev/null 2>&1; then
  echo "Nginx 이미지를 받습니다: $LB_IMAGE"
  $DOCKER pull "$LB_IMAGE" >/dev/null || { echo "Nginx 이미지($LB_IMAGE)를 받지 못했습니다. 지금 서버는 그대로 둡니다." >&2; exit 1; }
fi

LB_NAME="${APP_NAME}-lb"
A_NAME="${APP_NAME}-a"
B_NAME="${APP_NAME}-b"
CHECK_NAME="${APP_NAME}-lb-check"
LB_DIR="$APP_HOME/lb"
CHECK_DIR="$APP_HOME/lb-check"
# 예전 구조의 앱 컨테이너. creat-blog(8340, 한 대 시절), creat-blog-1·creat-blog-2(손으로 띄운 두 번째 서버 등).
# 이 이름들은 새 구조로 옮긴 뒤 지운다.
LEGACY_NAMES="${APP_NAME} ${APP_NAME}-1 ${APP_NAME}-2"

exists() { $DOCKER container inspect "$1" >/dev/null 2>&1; }
running() { [ "$($DOCKER container inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]; }
image_of() { $DOCKER container inspect -f '{{.Image}}' "$1" 2>/dev/null || true; }
# 포트에 누가 듣고 있는지 (연결이 되면 0)
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
# 예전 컨테이너가 쓰던 포트. SERVER_PORT 환경변수가 없으면 이름으로 짐작한다
legacy_port() {
  local p
  p="$($DOCKER container inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" 2>/dev/null | sed -n 's/^SERVER_PORT=//p' | head -n 1)"
  if [ -z "$p" ]; then
    case "$1" in "${APP_NAME}-2") p="$APP_PORT_A" ;; *) p="$APP_PORT" ;; esac
  fi
  printf '%s' "$p"
}
# 포트를 쓰는 예전 컨테이너 이름들 (있는 것만)
legacy_on() {
  local n
  for n in $LEGACY_NAMES; do
    if exists "$n" && [ "$(legacy_port "$n")" = "$1" ]; then printf '%s\n' "$n"; fi
  done
}

# 포트 주소 → HTTP 상태 번호 (안 되면 000)
http_code() {
  if command -v curl >/dev/null 2>&1; then
    curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$1$2" 2>/dev/null || true
  else
    ( exec 3<>"/dev/tcp/127.0.0.1/$1" || exit 0
      printf 'GET %s HTTP/1.0\r\nHost: localhost\r\n\r\n' "$2" >&3
      IFS=' ' read -r -t 5 _ code _ <&3 && printf '%s' "$code" ) 2>/dev/null || true
  fi
}
# 우리 Nginx로 /api/health 한 번: "상태번호 받은앱" (예: 200 127.0.0.1:8341). 안 되면 "000 "
lb_probe() {
  local head
  head="$( ( exec 3<>"/dev/tcp/127.0.0.1/$1" || exit 0
    printf 'GET /api/health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
    timeout 5 cat <&3 | tr -d '\r' | sed '/^$/q' ) 2>/dev/null || true)"
  printf '%s %s' "$(printf '%s\n' "$head" | sed -n '1s/^HTTP\/[0-9.]* \([0-9]*\).*/\1/p')" \
    "$(printf '%s\n' "$head" | sed -n 's/^[Xx]-[Uu]pstream: *//p' | head -n 1)" | sed 's/^ /000 /'
}

# 이름 포트 [old]: /api/health가 200이면 준비됨. old면 /api/health가 없는 예전 이미지라 첫 화면(/)이 200이어도 된다.
wait_ready() {
  local _
  for _ in $(seq 1 "$READY_TRIES"); do
    running "$1" || return 1
    # 새 컨테이너가 뜨다가 죽어 도커가 다시 켠 적이 있으면 기다리지 않고 실패로 본다
    if [ "${3:-}" != "old" ] && [ "$($DOCKER container inspect -f '{{.RestartCount}}' "$1" 2>/dev/null || echo 0)" != "0" ]; then
      return 1
    fi
    [ "$(http_code "$2" /api/health)" = "200" ] && return 0
    if [ "${3:-}" = "old" ] && [ "$(http_code "$2" /)" = "200" ]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# 이름 포트 서버이름 정리작업(true|false) 이미지 [듣는주소]
run_app() {
  local bind=()
  [ -n "${6-$APP_BIND}" ] && bind=(-e "SERVER_ADDRESS=${6-$APP_BIND}")
  $DOCKER run -d --name "$1" --restart unless-stopped --stop-timeout "$STOP_TIMEOUT" \
    --network host -e "SERVER_PORT=$2" ${bind[@]+"${bind[@]}"} -e "INSTANCE_ID=$3" -e "APP_JOBS_ENABLED=$4" \
    -e "SHUTDOWN_TIMEOUT=${SHUTDOWN_SECONDS}s" \
    --env-file "$APP_HOME/secrets.env" --env-file "$APP_HOME/runtime.env" \
    -v "$APP_HOME/data/uploads:/app/data/uploads" \
    --user "$(id -u):$(id -g)" \
    "$5" >/dev/null
}

# 우리 Nginx 설정. 듣는 포트만 바꿔 시험용(LB_CHECK_PORT)에도 쓴다.
lb_conf() {
  cat <<NGINX
# Creat_ 부하 분산 (D-111). deploy.sh가 만든다. 손으로 고치지 말고 deploy.sh를 고친다.
# 학교 Nginx → 여기(${1}) → 앱 두 대(${APP_PORT_A}, ${APP_PORT_B})

# 이 서버 안(학교 Nginx)에서 온 요청만 X-Forwarded-Proto·X-Real-IP를 그대로 넘긴다. 아니면 직접 본 값을 쓴다
map \$remote_addr \$lb_trusted {
    default   0;
    127.0.0.1 1;
    ::1       1;
}
map "\$lb_trusted:\$http_x_forwarded_proto" \$lb_proto {
    default   \$scheme;
    "1:https" https;
    "1:http"  http;
}
map "\$lb_trusted:\$http_x_real_ip" \$lb_real_ip {
    default           \$remote_addr;
    "~^1:(?<ip>.+)\$" \$ip;
}
# 웹소켓일 때만 Connection: upgrade (지금은 안 씀). 보통 요청은 close: 앱과의 연결을 요청마다 새로 연다
map \$http_upgrade \$lb_connection {
    default upgrade;
    ''      close;
}

upstream creat_blog {
    # Nginx 일꾼(worker)들이 순서(라운드 로빈)·실패 기록을 함께 쓴다. 없으면 일꾼마다 따로 세어 한쪽으로 몰린다
    zone creat_blog 64k;
    # 연결이 안 되면 ${FAIL_TIMEOUT}초 동안 빼 둔다. keepalive는 넣지 않는다 (아래 설명)
    server 127.0.0.1:${APP_PORT_A} max_fails=1 fail_timeout=${FAIL_TIMEOUT}s;
    server 127.0.0.1:${APP_PORT_B} max_fails=1 fail_timeout=${FAIL_TIMEOUT}s;
}

server {
    listen ${1};
    server_name _;
    server_tokens off;

    client_max_body_size ${LB_MAX_BODY};

    location / {
        proxy_pass http://creat_blog;
        # 한 대가 내려가 있거나(배포 중) 502·503이면 다른 한 대로 다시 보낸다.
        # non_idempotent는 넣지 않는다: 이미 앱에 보낸 POST(글 저장 등)는 다시 보내지 않아 두 번 저장되지 않는다.
        # 내려가는 앱은 새 연결을 받지 않으므로(연결 거절 = 아직 안 보냄) POST도 다른 한 대로 간다.
        proxy_next_upstream error timeout http_502 http_503;
        proxy_next_upstream_tries 2;
        proxy_next_upstream_timeout 15s;
        proxy_connect_timeout 2s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;

        proxy_http_version 1.1;
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$lb_real_ip;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$lb_proto;
        proxy_set_header Upgrade           \$http_upgrade;
        proxy_set_header Connection        \$lb_connection;

        # 소셜 로그인 쿠키·보안 헤더가 들어가는 응답 머리가 기본 4KB를 넘을 수 있어 넉넉히
        proxy_buffer_size 16k;
        proxy_buffers 8 16k;
        proxy_busy_buffers_size 32k;

        # 어느 앱이 답했는지 (127.0.0.1:8341 또는 8342)
        add_header X-Upstream \$upstream_addr always;
    }
}
NGINX
}

# 설정 파일을 폴더에 쓴다. 컨테이너에는 폴더째 읽기 전용으로 붙인다 (파일 하나만 붙이면 바꾼 파일이 안 보임)
write_conf() {
  mkdir -p "$1"
  chmod 755 "$1"
  lb_conf "$2" > "$1/default.conf.new"
  chmod 644 "$1/default.conf.new"
  mv -f "$1/default.conf.new" "$1/default.conf"
}

# 이름 설정폴더: 만들기만 한다 (docker start로 바로 띄우려고)
create_lb() {
  $DOCKER create --name "$1" --restart unless-stopped --network host \
    -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
    -v "$2:/etc/nginx/conf.d:ro" "$LB_IMAGE" >/dev/null
}

# 포트가 비워질 때까지 기다린다 (0.1초마다, 최대 초)
wait_port_free() {
  local i
  for i in $(seq 1 $(( $2 * 10 ))); do
    port_busy "$1" || return 0
    sleep 0.1
  done
  return 1
}

# 우리 Nginx 포트로 /api/health가 200이 될 때까지 (0.25초마다, 최대 10초). 포트 컨테이너이름
# 컨테이너가 꺼지면 (설정 오류, 포트를 못 잡음) 바로 실패로 본다
wait_lb() {
  local i
  for i in $(seq 1 40); do
    [ "$(http_code "$1" /api/health)" = "200" ] && return 0
    [ "$i" -gt 2 ] && ! running "$2" && return 1
    sleep 0.25
  done
  return 1
}

# 전에 이 스크립트가 중간에 끊겨 <이름>-prev가 남아 있으면 정리한다.
# 새 컨테이너가 돌고 있으면 -prev는 지우고, 아니면 -prev를 원래 이름으로 돌려놓는다.
recover_prev() {
  exists "$1-prev" || return 0
  if running "$1"; then
    $DOCKER rm -f "$1-prev" >/dev/null 2>&1 || true
  else
    $DOCKER rm -f "$1" >/dev/null 2>&1 || true
    $DOCKER rename "$1-prev" "$1"
    $DOCKER start "$1" >/dev/null 2>&1 || true
  fi
}

# 한 자리(앱 한 대)를 새 이미지로 바꾼다. 이름 포트 서버이름 정리작업
# 지금 컨테이너는 지우지 않고 <이름>-prev로 바꿔 멈춰 둔다. 손으로 띄운 예전 컨테이너(creat-blog-2 등)도 멈춰만 둔다.
# 새 것이 준비되지 않으면 멈춰 둔 것을 그대로 다시 켠다 (같은 이미지·같은 설정). 모두 끝나면 finish가 지운다.
replace_slot() {
  local name="$1" port="$2" id="$3" jobs="$4" had_prev=0 legacy="" n
  recover_prev "$name"
  if exists "$name"; then
    echo "[$id] 포트 $port 의 지금 컨테이너를 내립니다 (받던 요청을 마칠 때까지 최대 ${STOP_TIMEOUT}초)."
    $DOCKER stop -t "$STOP_TIMEOUT" "$name" >/dev/null 2>&1 || true
    $DOCKER rename "$name" "$name-prev"
    had_prev=1
  fi
  for n in $(legacy_on "$port"); do
    if running "$n"; then
      echo "[$id] 예전 컨테이너 $n (포트 $port)를 내립니다."
      $DOCKER stop -t "$STOP_TIMEOUT" "$n" >/dev/null 2>&1 || true
      legacy="$legacy $n"
    fi
  done
  if ! wait_port_free "$port" 10; then
    echo "[$id] 포트 $port 를 이 스크립트가 모르는 프로그램이 쓰고 있습니다. docker ps 로 확인해 그 컨테이너를 지운 뒤 다시 배포하세요." >&2
    restore_slot "$name" "$port" "$id" "$had_prev" "$legacy"
    return 1
  fi
  echo "[$id] 새 이미지로 포트 $port 에 띄웁니다."
  if run_app "$name" "$port" "$id" "$jobs" "$IMAGE" && wait_ready "$name" "$port"; then
    echo "[$id] 준비됨: http://127.0.0.1:$port/api/health"
    return 0
  fi
  echo "[$id] 새 컨테이너가 준비되지 않았습니다. 마지막 로그:" >&2
  $DOCKER logs --tail 80 "$name" >&2 2>&1 || true
  restore_slot "$name" "$port" "$id" "$had_prev" "$legacy"
  return 1
}

# 이름 포트 서버이름 -prev가있었나(1|0) 멈춘예전컨테이너들
restore_slot() {
  local n
  $DOCKER rm -f "$1" >/dev/null 2>&1 || true
  if [ "$4" = "1" ] && exists "$1-prev"; then
    echo "[$3] 바로 전 컨테이너로 되돌립니다." >&2
    $DOCKER rename "$1-prev" "$1"
    $DOCKER start "$1" >/dev/null
    wait_ready "$1" "$2" old || echo "[$3] 경고: 바로 전 컨테이너도 준비되지 않았습니다." >&2
  fi
  for n in $5; do
    echo "[$3] 예전 컨테이너 $n 를 다시 켭니다." >&2
    $DOCKER start "$n" >/dev/null 2>&1 || true
    wait_ready "$n" "$2" old || echo "[$3] 경고: $n 이 준비되지 않았습니다." >&2
  done
}

# 실패하면 latest 태그를 지금 돌고 있는 이미지로 돌려 둔다 (다음 배포가 고장 난 이미지를 previous로 남기지 않게)
restore_latest() {
  local img
  img="$(image_of "$A_NAME")"
  [ -n "$img" ] || img="$(image_of "$APP_NAME")"
  if [ -n "$img" ]; then $DOCKER tag "$img" "$IMAGE" >/dev/null 2>&1 || true; fi
}

# 다 끝났을 때: 멈춰 둔 바로 전 컨테이너와 예전 컨테이너를 지운다
finish_slots() {
  local n
  $DOCKER rm -f "$A_NAME-prev" "$B_NAME-prev" >/dev/null 2>&1 || true
  for n in $(legacy_on "$APP_PORT_A") $(legacy_on "$APP_PORT_B"); do
    $DOCKER rm -f "$n" >/dev/null 2>&1 || true
  done
}

# 8340에 우리 Nginx를 처음 띄운다 (또는 멈춰 있던 것을 다시 띄운다).
# ① 빈 포트(LB_CHECK_PORT)에 같은 설정으로 시험 Nginx를 띄워 두 앱에 고르게 가는지 본다
# ② 우리 Nginx 컨테이너를 미리 만들어 둔다
# ③ 8340의 예전 앱(creat-blog)에 종료 신호를 보내고 포트가 비자마자 우리 Nginx를 켠다 (끊김 1~2초)
# ④ 8340으로 확인. 안 되면 우리 Nginx를 지우고 예전 앱을 다시 켠다 (없으면 새 이미지 앱을 8340에 띄운다)
start_lb() {
  local i ups="" code up n legacy=""
  echo "[lb] 시험: 포트 ${LB_CHECK_PORT}에 같은 설정으로 Nginx를 띄웁니다."
  $DOCKER rm -f "$CHECK_NAME" >/dev/null 2>&1 || true
  if port_busy "$LB_CHECK_PORT"; then
    echo "[lb] 시험 포트 ${LB_CHECK_PORT}를 다른 프로그램이 쓰고 있습니다. LB_CHECK_PORT를 바꿔 주세요." >&2
    return 1
  fi
  write_conf "$CHECK_DIR" "$LB_CHECK_PORT"
  create_lb "$CHECK_NAME" "$CHECK_DIR"
  $DOCKER start "$CHECK_NAME" >/dev/null
  if ! wait_lb "$LB_CHECK_PORT" "$CHECK_NAME"; then
    echo "[lb] 시험 Nginx가 응답하지 않습니다:" >&2
    $DOCKER logs --tail 30 "$CHECK_NAME" >&2 2>&1 || true
    $DOCKER rm -f "$CHECK_NAME" >/dev/null 2>&1 || true
    return 1
  fi
  for i in 1 2 3 4 5 6; do
    read -r code up <<<"$(lb_probe "$LB_CHECK_PORT")" || true
    [ "$code" = "200" ] || { echo "[lb] 시험 요청 $i 실패 ($code)" >&2; $DOCKER rm -f "$CHECK_NAME" >/dev/null 2>&1; return 1; }
    ups="$ups ${up:-?}"
  done
  $DOCKER rm -f "$CHECK_NAME" >/dev/null 2>&1 || true
  rm -rf "$CHECK_DIR"
  echo "[lb] 시험 요청 6번 모두 200, 받은 앱:$ups"
  case "$ups" in *":$APP_PORT_A"*) ;; *) echo "[lb] 시험에서 ${APP_PORT_A}로 간 요청이 없습니다." >&2; return 1 ;; esac
  case "$ups" in *":$APP_PORT_B"*) ;; *) echo "[lb] 시험에서 ${APP_PORT_B}로 간 요청이 없습니다." >&2; return 1 ;; esac

  write_conf "$LB_DIR" "$APP_PORT"
  $DOCKER rm -f "$LB_NAME" >/dev/null 2>&1 || true
  create_lb "$LB_NAME" "$LB_DIR"
  if [ -n "$(legacy_on "$APP_PORT")" ]; then
    echo "[lb] 학교 Nginx가 방금 바꾼 앱을 다시 쓰도록 ${SWAP_SETTLE_SECONDS}초 기다린 뒤 포트 ${APP_PORT}를 바꿉니다."
    sleep "$SWAP_SETTLE_SECONDS"
  fi

  for n in $(legacy_on "$APP_PORT"); do running "$n" && legacy="$legacy $n"; done
  if [ -z "$legacy" ] && port_busy "$APP_PORT"; then
    echo "[lb] 포트 ${APP_PORT}를 이 스크립트가 모르는 프로그램이 쓰고 있습니다. 그대로 두고 멈춥니다." >&2
    $DOCKER rm -f "$LB_NAME" >/dev/null 2>&1 || true
    return 1
  fi
  for n in $legacy; do
    # 다시 켜지지 않게 한 뒤 종료 신호만 보낸다. 앱이 포트를 닫는 즉시 우리 Nginx를 켠다
    $DOCKER update --restart no "$n" >/dev/null 2>&1 || true
    echo "[lb] 예전 앱 $n (포트 ${APP_PORT})에 종료 신호를 보냅니다."
    $DOCKER kill --signal TERM "$n" >/dev/null 2>&1 || true
  done
  if wait_port_free "$APP_PORT" "$STOP_TIMEOUT" && $DOCKER start "$LB_NAME" >/dev/null && wait_lb "$APP_PORT" "$LB_NAME"; then
    echo "[lb] 우리 Nginx가 포트 ${APP_PORT}에서 두 앱으로 나눕니다."
    for n in $legacy; do
      $DOCKER stop -t "$STOP_TIMEOUT" "$n" >/dev/null 2>&1 || true
      $DOCKER rm -f "$n" >/dev/null 2>&1 || true
    done
    return 0
  fi
  echo "[lb] 우리 Nginx가 포트 ${APP_PORT}에서 뜨지 않았습니다. 마지막 로그:" >&2
  $DOCKER logs --tail 30 "$LB_NAME" >&2 2>&1 || true
  $DOCKER rm -f "$LB_NAME" >/dev/null 2>&1 || true
  if [ -n "$legacy" ]; then
    for n in $legacy; do
      echo "[lb] 예전 앱 $n 를 다시 켭니다." >&2
      $DOCKER stop -t "$STOP_TIMEOUT" "$n" >/dev/null 2>&1 || true
      $DOCKER update --restart unless-stopped "$n" >/dev/null 2>&1 || true
      $DOCKER start "$n" >/dev/null 2>&1 || true
      wait_ready "$n" "$APP_PORT" old || echo "[lb] 경고: $n 이 준비되지 않았습니다." >&2
    done
  elif ! port_busy "$APP_PORT"; then
    echo "[lb] 새 이미지 앱을 포트 ${APP_PORT}에 바로 띄웁니다 (다음 배포가 다시 우리 Nginx로 옮김)." >&2
    $DOCKER rm -f "$APP_NAME" >/dev/null 2>&1 || true
    run_app "$APP_NAME" "$APP_PORT" app false "$IMAGE" "" || true
    wait_ready "$APP_NAME" "$APP_PORT" || echo "[lb] 경고: 대신 띄운 앱도 준비되지 않았습니다." >&2
  fi
  return 1
}

# 우리 Nginx가 이미 돌고 있으면 설정만 맞춘다. 바뀌었으면 검사(nginx -t) 뒤 reload, 검사에 걸리면 전 설정으로 돌린다.
update_lb() {
  local want
  want="$(lb_conf "$APP_PORT")"
  if [ -f "$LB_DIR/default.conf" ] && [ "$(cat "$LB_DIR/default.conf")" = "$want" ]; then
    echo "[lb] 우리 Nginx 설정은 그대로입니다."
    return 0
  fi
  echo "[lb] 우리 Nginx 설정이 바뀌어 검사 뒤 다시 읽힙니다."
  [ -f "$LB_DIR/default.conf" ] && cp -p "$LB_DIR/default.conf" "$LB_DIR/default.conf.bak"
  write_conf "$LB_DIR" "$APP_PORT"
  if $DOCKER exec "$LB_NAME" nginx -t && $DOCKER exec "$LB_NAME" nginx -s reload; then
    rm -f "$LB_DIR/default.conf.bak"
    return 0
  fi
  echo "[lb] 새 설정이 검사를 통과하지 못해 전 설정으로 돌립니다." >&2
  [ -f "$LB_DIR/default.conf.bak" ] && mv -f "$LB_DIR/default.conf.bak" "$LB_DIR/default.conf"
  return 1
}

# ---------------------------------------------------------------------------
# 시작: 시험용으로 손으로 띄운 Nginx(lbtest)와 지난 시험 컨테이너는 지운다
for n in lbtest "$CHECK_NAME"; do
  if exists "$n"; then
    echo "시험용 컨테이너 $n 를 지웁니다."
    $DOCKER rm -f "$n" >/dev/null 2>&1 || true
  fi
done

# 우리 Nginx가 멈춰 있고 포트가 비어 있으면 (지금 사이트가 안 열림) 앱을 바꾸기 전에 먼저 켠다
if exists "$LB_NAME" && ! running "$LB_NAME" && ! port_busy "$APP_PORT"; then
  echo "멈춰 있던 우리 Nginx를 먼저 켭니다."
  $DOCKER start "$LB_NAME" >/dev/null 2>&1 || true
fi

# 전에 끊긴 배포가 남긴 <이름>-prev를 먼저 정리한다 (두 자리 모두)
recover_prev "$B_NAME"
recover_prev "$A_NAME"

# 보통은 b → a 순서. a가 지금 응답하지 않고 b는 응답하면 a를 먼저 바꾼다 (살아 있는 한 대를 먼저 내리지 않게)
FIRST="$B_NAME $APP_PORT_B app-b false"
SECOND="$A_NAME $APP_PORT_A app-a true"
if [ "$(http_code "$APP_PORT_A" /api/health)" != "200" ] && [ "$(http_code "$APP_PORT_B" /api/health)" = "200" ]; then
  FIRST="$A_NAME $APP_PORT_A app-a true"
  SECOND="$B_NAME $APP_PORT_B app-b false"
fi
read -r N1 P1 I1 J1 <<<"$FIRST"
read -r N2 P2 I2 J2 <<<"$SECOND"

echo "롤링 배포: ${I1}(포트 ${P1}) → ${I2}(포트 ${P2}), 우리 Nginx는 포트 ${APP_PORT}"
if ! replace_slot "$N1" "$P1" "$I1" "$J1"; then
  echo "배포 실패: ${I1}를 바꾸지 못해 되돌렸습니다. ${I2}와 포트 ${APP_PORT}는 손대지 않았습니다." >&2
  restore_latest
  exit 1
fi
echo "우리 Nginx가 ${I1}를 다시 쓰도록 ${SETTLE_SECONDS}초 기다립니다."
sleep "$SETTLE_SECONDS"
if ! replace_slot "$N2" "$P2" "$I2" "$J2"; then
  echo "배포 실패: ${I2}를 되돌렸습니다. 두 대가 같은 버전이 되게 ${I1}도 되돌립니다." >&2
  if exists "$N1-prev"; then
    sleep "$SETTLE_SECONDS"
    $DOCKER stop -t "$STOP_TIMEOUT" "$N1" >/dev/null 2>&1 || true
    restore_slot "$N1" "$P1" "$I1" 1 ""
  fi
  restore_latest
  exit 1
fi

if running "$LB_NAME"; then
  # 우리 Nginx가 포트를 쥐고 있으니 8340의 예전 앱 컨테이너(멈춘 것)는 필요 없다
  for n in $(legacy_on "$APP_PORT"); do running "$n" || $DOCKER rm -f "$n" >/dev/null 2>&1 || true; done
  if ! update_lb; then
    finish_slots
    echo "배포는 됐지만 우리 Nginx 새 설정을 적용하지 못했습니다 (전 설정으로 계속 동작)." >&2
    exit 1
  fi
else
  echo "우리 Nginx가 없어 포트 ${APP_PORT}에 띄웁니다 (처음 한 번, 또는 멈춰 있던 경우)."
  if ! start_lb; then
    echo "배포 실패: 우리 Nginx를 포트 ${APP_PORT}에 띄우지 못했습니다. 포트 ${APP_PORT}는 예전처럼 동작합니다." >&2
    # 앱 두 대는 새 이미지로 돌고 있다. 다음 배포가 다시 우리 Nginx를 띄운다
    finish_slots
    exit 1
  fi
fi
finish_slots

code="$(http_code "$APP_PORT" /api/health)"
if [ "$code" != "200" ]; then
  echo "경고: 포트 ${APP_PORT}/api/health 가 ${code} 입니다." >&2
  exit 1
fi
echo "배포 완료: ${BASE_URL} (우리 Nginx ${APP_PORT} → app-a ${APP_PORT_A}, app-b ${APP_PORT_B})"
rm -f "$IMAGE_FILE"
$DOCKER image prune -f >/dev/null 2>&1 || true
exit 0
