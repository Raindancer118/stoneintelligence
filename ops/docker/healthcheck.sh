#!/usr/bin/env bash
# Container-Healthcheck ohne curl/wget (nicht in jedem JRE-Image): healthcheck.sh PORT [PFAD]
# Exit 0 nur bei HTTP 200.
set -euo pipefail
port="${1:?port}"
path="${2:-/actuator/health}"
exec 3<>"/dev/tcp/127.0.0.1/$port"
printf 'GET %s HTTP/1.0\r\nHost: localhost\r\n\r\n' "$path" >&3
read -r -t 5 status <&3
[[ "$status" == HTTP/*" 200"* ]]
