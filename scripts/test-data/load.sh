#!/usr/bin/env bash
# 테스트 데이터 넣기. 지금 DB를 먼저 백업하고 seed.sql로 모든 데이터를 바꾼다.
# 사용법: scripts/test-data/load.sh   (DB_NAME, DB_USER, DB_PASSWORD, DB_HOST 로 바꿀 수 있음)
# 되돌리기: mysql -u blog -p blog < backups/blog-날짜.sql
set -euo pipefail

DB_NAME="${DB_NAME:-blog}"
DB_USER="${DB_USER:-blog}"
DB_PASSWORD="${DB_PASSWORD:-blog}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
BACKUP_DIR="$ROOT/backups"
STAMP="$(date +%Y%m%d-%H%M%S)"

export MYSQL_PWD="$DB_PASSWORD"
mkdir -p "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/$DB_NAME-$STAMP.sql"
mysqldump -h "$DB_HOST" -u "$DB_USER" --no-tablespaces --set-gtid-purged=OFF "$DB_NAME" > "$BACKUP"
echo "백업: $BACKUP"

mysql -h "$DB_HOST" -u "$DB_USER" --default-character-set=utf8mb4 "$DB_NAME" < "$DIR/seed.sql"
mysql -h "$DB_HOST" -u "$DB_USER" -N "$DB_NAME" -e \
  "SELECT CONCAT('회원 ', (SELECT COUNT(*) FROM members), '명, 블로그 ', (SELECT COUNT(*) FROM blogs), '개, 글 ', (SELECT COUNT(*) FROM posts), '개')"
echo "끝. 비밀번호는 모두 Test1234! (docs/05-guides/테스트-데이터.md)"
