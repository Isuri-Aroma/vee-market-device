#!/usr/bin/env bash
# Checks the fake server itself, by pretending to be the ESP32 with curl.
# Run this BEFORE testing with the real device: if this fails, the server is wrong, not the device.
#
#   ./test-fake-server.sh                       # server on this Mac
#   ./test-fake-server.sh http://172.20.10.3:8080   # from your friend's laptop (Git Bash on Windows works)

BASE="${1:-http://localhost:8080}"
ID="VM-0001"
KEY="test-secret-0001"
PASS=0; FAIL=0

check() { # name expected_code actual_code [body] [must_contain]
  local name="$1" want="$2" got="$3" body="$4" needle="$5"
  if [ "$got" = "$want" ] && { [ -z "$needle" ] || echo "$body" | grep -q "$needle"; }; then
    echo "  PASS  $name  ($got)"; PASS=$((PASS+1))
  else
    echo "  FAIL  $name  (wanted $want${needle:+ containing '$needle'}, got $got: $body)"; FAIL=$((FAIL+1))
  fi
}

# call METHOD PATH [JSON] [KEY]  -> sets CODE and BODY
call() {
  local method="$1" path="$2" data="$3" key="${4:-$KEY}" out
  if [ -n "$data" ]; then
    out=$(curl -s -m 15 -w $'\n%{http_code}' -X "$method" "$BASE$path" -H "Content-Type: application/json" \
          -H "X-Device-Id: $ID" -H "X-Device-Key: $key" -d "$data")
  else
    out=$(curl -s -m 15 -w $'\n%{http_code}' -X "$method" "$BASE$path" -H "X-Device-Id: $ID" -H "X-Device-Key: $key")
  fi
  CODE=$(echo "$out" | tail -n1); BODY=$(echo "$out" | sed '$d')
}

reading() { # session_id moisture
  echo "{\"session_id\":$1,\"moisture_pct\":$2,\"zones\":{\"top\":14.2,\"middle\":14.7,\"bottom\":15.0},\"spread_pct\":0.8,\"temp_c\":29.4,\"calibration_version\":\"cal-2026-10-15\",\"firmware\":\"0.1.0\"}"
}
sid() { echo "$1" | sed -n 's/.*"session_id":\([0-9]*\).*/\1/p'; }
rid() { echo "$1" | sed -n 's/.*"reading_id":\([0-9]*\).*/\1/p'; }

echo "Testing fake server at $BASE"
curl -s -m 5 -o /dev/null "$BASE/fake/state" || { echo "Cannot reach $BASE — is the server running? Same Wi-Fi? Firewall?"; exit 1; }
curl -s -X POST "$BASE/fake/reset" >/dev/null

echo "1. Poll and authentication"
call POST /api/device/poll;                  check "poll, nothing waiting"        200 "$CODE" "$BODY" '"session":null'
call POST /api/device/poll "" "wrong-key";   check "poll, wrong key"              401 "$CODE" "$BODY"
call POST /api/device/reading "$(reading 1 14.6)"; check "reading with no session" 409 "$CODE" "$BODY"

echo "2. Happy path"
call POST /api/tests/start '{"device_id":"VM-0001","lot_id":123,"farmer_name":"Nimal"}'
check "app starts test" 200 "$CODE" "$BODY" '"status":"open"'; S=$(sid "$BODY")
call POST /api/tests/start '{"device_id":"VM-0001","lot_id":124}'; check "second start -> device busy" 409 "$CODE" "$BODY" "device_busy"
call POST /api/device/poll;                  check "poll sees session $S"          200 "$CODE" "$BODY" '"farmer_name":"Nimal"'
call POST /api/device/reading "$(reading "$S" 80.0)"; check "moisture 80% -> rejected" 422 "$CODE" "$BODY"
call POST /api/device/reading '{"session_id":'"$S"',"moisture":14.6}'; check "wrong field names -> rejected" 400 "$CODE" "$BODY"
call POST /api/device/reading "$(reading "$S" 14.6)" "wrong-key"; check "reading, wrong key" 401 "$CODE" "$BODY"
call POST /api/device/reading "$(reading "$S" 14.6)"; check "good reading saved" 200 "$CODE" "$BODY" '"ok":true'; R=$(rid "$BODY")
call POST /api/device/reading "$(reading "$S" 14.6)"; check "same reading again -> OK, not saved twice" 200 "$CODE" "$BODY" "\"reading_id\":$R"
call GET "/api/tests/$S";                    check "app sees done + reading"        200 "$CODE" "$BODY" '"status":"done"'
call POST /api/device/poll;                  check "poll after done -> nothing"     200 "$CODE" "$BODY" '"session":null'

echo "3. Expiry (session length set to 3 s)"
curl -s -X POST "$BASE/fake/settings?session_seconds=3" >/dev/null
call POST /api/tests/start '{"device_id":"VM-0001","lot_id":125}'; S=$(sid "$BODY")
sleep 4
call POST /api/device/poll;                  check "poll after expiry -> nothing"   200 "$CODE" "$BODY" '"session":null'
call POST /api/device/reading "$(reading "$S" 14.6)"; check "late reading -> 409" 409 "$CODE" "$BODY"
call GET "/api/tests/$S";                    check "app sees expired"               200 "$CODE" "$BODY" '"status":"expired"'
curl -s -X POST "$BASE/fake/settings?session_seconds=300" >/dev/null

echo "4. Lost reply (server saves, then replies 500)"
call POST /api/tests/start '{"device_id":"VM-0001","lot_id":126}'; S=$(sid "$BODY")
curl -s -X POST "$BASE/fake/fault?target=reading&mode=save_then_500" >/dev/null
call POST /api/device/reading "$(reading "$S" 15.1)"; check "first send -> 500 (but saved)" 500 "$CODE" "$BODY"
call POST /api/device/reading "$(reading "$S" 15.1)"; check "retry -> 200 duplicate"        200 "$CODE" "$BODY" '"ok":true'

echo "5. Cancel and disabled device"
call POST /api/tests/start '{"device_id":"VM-0001","lot_id":127}'; S=$(sid "$BODY")
call POST "/api/tests/$S/cancel";            check "app cancels test"               200 "$CODE" "$BODY"
call POST /api/device/poll;                  check "poll after cancel -> nothing"   200 "$CODE" "$BODY" '"session":null'
curl -s -X POST "$BASE/fake/device/$ID?status=disabled" >/dev/null
call POST /api/device/poll;                  check "disabled device poll -> 403"    403 "$CODE" "$BODY"
curl -s -X POST "$BASE/fake/device/$ID?status=active" >/dev/null
call POST /api/tests/start '{"device_id":"VM-9999","lot_id":1}'; check "unknown device -> 404" 404 "$CODE" "$BODY"

curl -s -X POST "$BASE/fake/reset" >/dev/null
echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
