#!/usr/bin/env bash
#
# Build and run the wsnew test client, or refresh the user JWT.
#
#   ./run.sh                 # full flow (default)
#   ./run.sh hello           # run only up to the HelloClient/HelloServer step
#   ./run.sh session -d      # session step, verbose (DEBUG) logging
#   ./run.sh token           # fetch a fresh JWT into application.local.conf
#
set -euo pipefail
cd "$(dirname "$0")"

BIN="build/install/wsnew-test-client/bin/wsnew-test-client"

usage() {
  cat <<'EOF'
Usage: ./run.sh [step] [options]

Steps (cumulative; default: full):
  connect   open the TLS WebSocket only
  hello     + HelloClient / HelloServer handshake
  session   + InitiateUserSession (needs a JWT in application.local.conf)
  adddoc    + RemoteDelivery / AddDocument, then drain for the reaction
  listen    + stay connected, print inbound PrintLocalDocument
  full      hello -> session -> [adddoc if configured] -> listen
  token     fetch a fresh user JWT and write it into application.local.conf

Options:
  -d, --debug    verbose logging (WSNEW_LOG_LEVEL=DEBUG)
  -b, --build    force a rebuild before running
  -h, --help     show this help

Env overrides for 'token':
  API_HOST (default testcustomer.localhost.nip.io)
  API_PORT (default 7300)
  API_USER (default test)
  API_PASS (default Testing12345)
  API_KEY  (default dev-api-key)
EOF
}

STEP="full"; DEBUG=""; FORCE_BUILD=""
for a in "$@"; do
  case "$a" in
    connect|hello|session|adddoc|listen|full|token) STEP="$a" ;;
    -d|--debug) DEBUG=1 ;;
    -b|--build) FORCE_BUILD=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $a" >&2; usage; exit 1 ;;
  esac
done

# --- token: refresh the JWT in application.local.conf and exit ---
if [[ "$STEP" == "token" ]]; then
  [[ -f application.local.conf ]] || { echo "application.local.conf not found (copy application.local.conf.example)" >&2; exit 1; }
  host="${API_HOST:-testcustomer.localhost.nip.io}"
  port="${API_PORT:-7300}"
  user="${API_USER:-test}"
  pass="${API_PASS:-Testing12345}"
  key="${API_KEY:-dev-api-key}"
  echo "Fetching JWT for '$user' @ https://$host:$port ..."
  tok=$(curl -k -s "https://$host:$port/api/v1/login?authtype=0&userid=$user&password=$pass" \
          -H "X-API-Key: $key" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['token']['access_token'])")
  [[ -n "$tok" ]] || { echo "Failed to obtain token (check server / credentials)" >&2; exit 1; }
  python3 - "$tok" <<'PY'
import sys, re
tok = sys.argv[1]
path = "application.local.conf"
s = open(path).read()
s, n = re.subn(r'jwt = "[^"]*"', f'jwt = "{tok}"', s, count=1)
if n == 0:
    raise SystemExit('No `jwt = "..."` line found in application.local.conf')
open(path, "w").write(s)
print(f"Wrote fresh jwt ({len(tok)} chars) to {path}")
PY
  exit 0
fi

# --- build if needed ---
if [[ -n "$FORCE_BUILD" || ! -x "$BIN" ]]; then
  echo "Building (installDist)..."
  ./gradlew installDist --no-daemon -q
fi

# --- run ---
[[ -n "$DEBUG" ]] && export WSNEW_LOG_LEVEL=DEBUG
echo "Running step: $STEP${DEBUG:+ (debug)}"
exec "$BIN" "$STEP"
