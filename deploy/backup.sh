#!/usr/bin/env bash
# Writes a compressed dump of the Karname database to deploy/backups/ and keeps the newest
# $KEEP of them (default 14). Run it from cron, e.g.: 30 3 * * * /path/to/deploy/backup.sh
# The database alone is not enough: keep a copy of .env (KARNAME_SECRET_KEY) as well.
set -euo pipefail
cd "$(dirname "$0")"

keep="${KEEP:-14}"
mkdir -p backups
file="backups/karname-$(date +%Y%m%d-%H%M%S).sql.gz"

docker compose exec -T db pg_dump --username=karname --dbname=karname --no-owner --clean --if-exists \
  | gzip > "$file.partial"
mv "$file.partial" "$file"

ls -1t backups/karname-*.sql.gz | tail -n +"$((keep + 1))" | xargs -r rm --
echo "Backup written to $file"
