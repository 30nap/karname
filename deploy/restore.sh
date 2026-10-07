#!/usr/bin/env bash
# Replaces the database with a dump made by backup.sh: ./restore.sh backups/karname-….sql.gz
# The backend is stopped while the dump is loaded. Use the same KARNAME_SECRET_KEY as when the
# backup was made, or stored API keys cannot be decrypted.
set -euo pipefail
cd "$(dirname "$0")"

file="${1:?usage: ./restore.sh backups/karname-YYYYmmdd-HHMMSS.sql.gz}"
[[ -f "$file" ]] || { echo "No such file: $file" >&2; exit 1; }

read -r -p "This replaces ALL data in the database with $file. Continue? [y/N] " answer
[[ "$answer" == [yY] ]] || { echo "Cancelled."; exit 1; }

docker compose stop backend
gunzip -c "$file" | docker compose exec -T db psql --username=karname --dbname=karname --quiet -v ON_ERROR_STOP=1
docker compose start backend
echo "Restored $file"
