#!/usr/bin/env bash
# 매일 백업 (D-129). GitHub Actions(backup.yml)가 SSH로 접속해 서버에서 실행한다.
#
#   bash backup.sh [uploads]
#
# 1) DB를 APP_HOME/backups/daily-<시각>.sql.gz 로 뜬다. daily- 로 시작하는 것은 최근 BACKUP_KEEP_DAILY(기본 14)개만 남긴다.
#    배포 때 뜨는 before-deploy-*·before-reset-* 는 deploy.sh가 따로 관리한다.
# 2) 서버 밖으로 가져갈 파일을 APP_HOME/backups/outgoing/ 에 둔다.
#      db.sql.gz          방금 뜬 DB
#      uploads.tar.gz     첫 인자가 uploads 일 때만. data/uploads 폴더 전체 (올린 사진)
#    워크플로가 이 폴더를 받아 간 뒤 지운다. 서버 디스크가 망가져도 받아 간 쪽에 남는다.
#
# 받는 환경변수: DB_ADDRESS, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD (배포와 같은 시크릿)
set -euo pipefail

APP_HOME="${APP_HOME:-$HOME/creat-blog}"
DOCKER="${DOCKER:-docker}"
KEEP="${BACKUP_KEEP_DAILY:-14}"
WITH_UPLOADS="${1:-}"

need() { [ -n "${!1:-}" ] || { echo "환경변수 $1 이(가) 비어 있습니다. GitHub 시크릿을 확인해 주세요." >&2; exit 1; }; }
for v in DB_ADDRESS DB_PORT DB_NAME DB_USERNAME DB_PASSWORD; do need "$v"; done
case "$KEEP" in ''|*[!0-9]*|0) echo "BACKUP_KEEP_DAILY 는 1 이상의 숫자여야 합니다." >&2; exit 1 ;; esac

umask 077
dir="$APP_HOME/backups"
out="$dir/outgoing"
mkdir -p "$dir"
rm -rf "$out"
mkdir -p "$out"

# 배포와 겹쳐도 괜찮다: --single-transaction 이라 뜨는 동안 표를 잠그지 않고 한 시점의 모습을 뜬다
file="$dir/daily-$(date +%Y%m%d-%H%M%S).sql.gz"
if ! $DOCKER run --rm --network host -e MYSQL_PWD="$DB_PASSWORD" "${MYSQL_CLIENT_IMAGE:-mysql:8.0}" \
     mysqldump -h"$DB_ADDRESS" -P"$DB_PORT" -u"$DB_USERNAME" --protocol=TCP --single-transaction \
     --no-tablespaces --set-gtid-purged=OFF "$DB_NAME" | gzip > "$file"; then
  rm -f "$file"
  echo "DB 백업에 실패했습니다." >&2
  exit 1
fi
# 덤프가 끝까지 써졌는지: gzip이 온전하고, mysqldump가 마지막에 쓰는 "Dump completed" 줄이 있는지
if ! gzip -t "$file" || ! gzip -dc "$file" | tail -n 1 | grep -q 'Dump completed'; then
  rm -f "$file"
  echo "DB 백업 파일이 온전하지 않습니다." >&2
  exit 1
fi
chmod 600 "$file"
echo "DB 백업: $file ($(du -h "$file" | cut -f1))"
ls -1t "$dir"/daily-*.sql.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f
cp "$file" "$out/db.sql.gz"

if [ "$WITH_UPLOADS" = "uploads" ]; then
  if [ -d "$APP_HOME/data/uploads" ]; then
    tar -czf "$out/uploads.tar.gz" -C "$APP_HOME/data" uploads
    echo "사진 백업: $(find "$APP_HOME/data/uploads" -type f | wc -l)개, $(du -h "$out/uploads.tar.gz" | cut -f1)"
  else
    echo "경고: $APP_HOME/data/uploads 폴더가 없어 사진은 건너뜁니다." >&2
  fi
fi

echo "== 서버에 남은 매일 백업"
ls -1t "$dir"/daily-*.sql.gz | head -n "$KEEP"
echo "== 서버 디스크"
df -h "$APP_HOME" | tail -n 1
