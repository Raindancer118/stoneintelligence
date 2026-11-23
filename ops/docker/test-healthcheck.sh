#!/usr/bin/env bash
# Prueft healthcheck.sh gegen einen Wegwerf-HTTP-Server (200, 503, toter Server).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dir="$(mktemp -d)"
server=""
trap 'kill $server 2>/dev/null || true; rm -rf "$dir"' EXIT
cat > "$dir/srv.py" <<'PY'
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == "/up" else 503)
        self.end_headers()
        self.wfile.write(b"{}")
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
port=$((20000 + RANDOM % 20000))
python3 "$dir/srv.py" "$port" & server=$!
for _ in $(seq 50); do (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && break; sleep 0.1; done
"$here/healthcheck.sh" "$port" /up || { echo "FAIL: 200 nicht erkannt"; exit 1; }
if "$here/healthcheck.sh" "$port" /down; then echo "FAIL: 503 als gesund gemeldet"; exit 1; fi
kill "$server"; wait "$server" 2>/dev/null || true; server=""
if "$here/healthcheck.sh" "$port" /up 2>/dev/null; then echo "FAIL: toter Server als gesund gemeldet"; exit 1; fi
echo OK
