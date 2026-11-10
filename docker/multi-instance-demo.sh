#!/usr/bin/env bash
#
# Starts two gateway instances behind nginx and proves the property the protocol claims:
# a multi-round call begun on one node can be finished on another, because the state that
# links the two HTTP requests lives in a signed token the client carries, not in the server.
#
#   bash docker/multi-instance-demo.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

if command -v python >/dev/null 2>&1; then PY=python
elif command -v python3 >/dev/null 2>&1; then PY=python3
else echo "python is required to read JSON" >&2; exit 1; fi

COMPOSE=(docker compose -f docker-compose.multi.yml)
A=http://127.0.0.1:8932
B=http://127.0.0.1:8933
LB=http://127.0.0.1:8931
KEY=demo/incident.txt
FORGED_KEY=demo/forged.txt

META='"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}'

step() { printf '\n=== %s ===\n' "$*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }

jpath() { # dotted.path  (reads JSON on stdin)
    "$PY" -c '
import sys, json
d = json.load(sys.stdin)
for part in sys.argv[1].split("."):
    if d is None:
        break
    d = d[int(part)] if part.isdigit() else d.get(part)
if d is None:
    print("")
elif isinstance(d, (dict, list)):
    print(json.dumps(d, ensure_ascii=False))
else:
    print(d)
' "$1"
}

post() { # base method (name or "") body
    local base=$1 method=$2 name=$3 body=$4
    local args=(-sS -X POST "$base/mcp"
        -H 'Content-Type: application/json'
        -H 'MCP-Protocol-Version: 2026-07-28'
        -H "Mcp-Method: $method")
    if [ -n "$name" ]; then args+=(-H "Mcp-Name: $name"); fi
    curl "${args[@]}" -d "$body"
}

call_body() { # tool arguments-json extra-json
    printf '{"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":%s%s,%s}}' \
        "$((++ID))" "$1" "$2" "$3" "$META"
}
ID=0

# ---------------------------------------------------------------- 0. stack
step "0. two instances, one load balancer"
docker compose down --remove-orphans >/dev/null 2>&1 || true

build_log=$(mktemp)
if ! "${COMPOSE[@]}" up -d --build >"$build_log" 2>&1; then
    tail -30 "$build_log" >&2
    rm -f "$build_log"
    fail "the stack did not start"
fi
rm -f "$build_log"

printf 'waiting for the load balancer:'
for _ in $(seq 1 90); do
    if curl -fsS "$LB/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then break; fi
    printf '.'
    sleep 2
done
printf '\n'

for node in "$A" "$B"; do
    state=$(curl -fsS "$node/actuator/health" | jpath components.mcpBackend.details.backends.storage.state)
    [ "$state" = up ] || fail "$node storage backend is '$state'"
    ok "$node storage backend is up"
done

# ---------------------------------------------------------------- 1. object
step "1. write an object through node A"
out=$(post "$A" tools/call storage_put_text "$(call_body storage_put_text "{\"key\":\"$KEY\",\"content\":\"node A wrote this\"}" '')")
echo "$out" | jpath result.content.0.text

# ---------------------------------------------------------------- 2. round 1 on A
step "2. ask node A to delete it — the answer is a request for confirmation"
r1=$(post "$A" tools/call storage_delete_object "$(call_body storage_delete_object "{\"key\":\"$KEY\"}" '')")
type=$(echo "$r1" | jpath result.resultType)
[ "$type" = input_required ] || fail "expected input_required, got '$type': $r1"
state=$(echo "$r1" | jpath result.requestState)
[ -n "$state" ] || fail "no requestState in the round-1 result"

echo "   resultType   : $type"
echo "   question     : $(echo "$r1" | jpath result.inputRequests.confirm_delete.params.message)"
echo "   requestState : ${state:0:48}..."
echo "   (nothing about this call is stored on node A; the token is the whole state)"

# ---------------------------------------------------------------- 3. kill A
step "3. stop node A"
"${COMPOSE[@]}" stop gateway-a >/dev/null
sleep 2
if curl -fsS "$A/actuator/health" >/dev/null 2>&1; then fail "node A is still answering"; fi
ok "node A is down"

# ---------------------------------------------------------------- 4. resume on B
step "4. replay the token against node B and accept"
headers=$(mktemp)
r2=$(curl -sS -D "$headers" -X POST "$B/mcp" \
    -H 'Content-Type: application/json' \
    -H 'MCP-Protocol-Version: 2026-07-28' \
    -H 'Mcp-Method: tools/call' \
    -H 'Mcp-Name: storage_delete_object' \
    -d "$(call_body storage_delete_object "{\"key\":\"$KEY\"}" ",\"inputResponses\":{\"confirm_delete\":{\"action\":\"accept\",\"content\":{\"confirm\":true}}},\"requestState\":\"$state\"")")

type=$(echo "$r2" | jpath result.resultType)
[ "$type" = complete ] || fail "expected complete, got '$type': $r2"
echo "   resultType: $type"
echo "   content   : $(echo "$r2" | jpath result.content.0.text)"

if grep -qi '^mcp-session-id:' "$headers"; then
    fail "the response carried a session id, which this revision removed"
fi
ok "node B accepted node A's token, and the response carries no Mcp-Session-Id"
rm -f "$headers"

# ---------------------------------------------------------------- 5. effect
step "5. the object is really gone"
listed=$(post "$B" tools/call storage_list_objects "$(call_body storage_list_objects '{"prefix":"demo/"}' '')")
if echo "$listed" | jpath result.structuredContent.objects | grep -q "$KEY"; then
    fail "$KEY is still in the bucket"
fi
ok "$KEY no longer exists"

# ---------------------------------------------------------------- 6. forged token
step "6. the token is signed — editing it is rejected, not silently trusted"
post "$B" tools/call storage_put_text "$(call_body storage_put_text "{\"key\":\"$FORGED_KEY\",\"content\":\"still here\"}" '')" >/dev/null
r1=$(post "$B" tools/call storage_delete_object "$(call_body storage_delete_object "{\"key\":\"$FORGED_KEY\"}" '')")
state=$(echo "$r1" | jpath result.requestState)

forged=$("$PY" - "$state" <<'PY'
import base64, json, sys
payload, signature = sys.argv[1].split(".")
raw = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
state = json.loads(raw)
state["key"] = "demo/somebody-elses-file.txt"
edited = base64.urlsafe_b64encode(json.dumps(state, separators=(",", ":")).encode()).decode().rstrip("=")
print(edited + "." + signature)
PY
)

forged_reply=$(post "$B" tools/call storage_delete_object "$(call_body storage_delete_object "{\"key\":\"$FORGED_KEY\"}" ",\"inputResponses\":{\"confirm_delete\":{\"action\":\"accept\",\"content\":{\"confirm\":true}}},\"requestState\":\"$forged\"")")
code=$(echo "$forged_reply" | jpath error.code)
[ "$code" = -32602 ] || fail "expected -32602, got: $forged_reply"
echo "   code   : $code"
echo "   message: $(echo "$forged_reply" | jpath error.message)"
ok "an edited payload does not verify, so the substituted key is never acted on"

# ---------------------------------------------------------------- 7. load balancer
step "7. with A down, the load balancer still serves requests from B"
for i in 1 2 3; do
    reply=$(post "$LB" tools/list '' "{\"jsonrpc\":\"2.0\",\"id\":$((100+i)),\"method\":\"tools/list\",\"params\":{$META}}")
    type=$(echo "$reply" | jpath result.resultType)
    [ "$type" = complete ] || fail "request $i through the balancer failed: $reply"
done
ok "three requests in a row were served with A down, no sticky session involved"

# ---------------------------------------------------------------- 8. done
step "8. bring node A back"
"${COMPOSE[@]}" start gateway-a >/dev/null
for _ in $(seq 1 60); do
    curl -fsS "$A/actuator/health" >/dev/null 2>&1 && break
    sleep 1
done
ok "node A is up again"

printf '\nWhat this shows: the only thing that crossed the node boundary was the signed token.\n'
printf 'Stop the stack with: docker compose -f docker-compose.multi.yml down\n'