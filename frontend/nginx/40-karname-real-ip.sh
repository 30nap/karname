#!/bin/sh
# Behind another proxy (Caddy, Traefik, a CDN), KARNAME_REAL_IP_FROM lists its addresses (CIDR,
# space separated); the client address is then taken from its X-Forwarded-For, so per-client
# limits do not treat every visitor as one. Empty: nginx is the edge and uses the connection's.
set -eu
conf=/etc/nginx/snippets/real-ip.conf
: > "$conf"
for cidr in ${KARNAME_REAL_IP_FROM:-}; do
  case "$cidr" in
    *[!0-9a-fA-F.:/]*) echo "KARNAME_REAL_IP_FROM: not an address or CIDR: $cidr" >&2; exit 1 ;;
  esac
  echo "set_real_ip_from $cidr;" >> "$conf"
done
if [ -s "$conf" ]; then
  printf 'real_ip_header X-Forwarded-For;\nreal_ip_recursive on;\n' >> "$conf"
fi
