#!/usr/bin/env bash
# M-MQTT 런1 — 인입 램프 (2k→10k, 단계별). k6 telemetry-capacity.js의 MQTT 대응.
#   1 client = 1 device(telemetry/%i) = 1Hz(-I 1000). 페이로드 timestamp = %TIMESTAMPMS%(epoch-ms).
#   목적: 단일 Paho 콜백 스레드 인라인 처리가 어느 도착률에서 plateau 하는가 + 메모리 벽(스왑 off라 OOM).
#
# 함정(측정 전 확인, docs/measurements/M-MQTT.md):
#   1) 템플릿 변수(%TIMESTAMPMS%)는 template:// 파일 모드에서만 치환. 인라인 -m 리터럴은 글자 그대로 나가 전량 드롭.
#   2) %TIMESTAMP%(초) 아님 %TIMESTAMPMS%(밀리초). 앱이 숫자를 epoch-ms로 읽음(초는 1970년→@ValidTimestamp 드롭).
#   3) --ulimit nofile은 docker run에(호스트 ulimit 미전파 → emfile).
#   4) 시간 제어 = background + docker kill. macOS엔 GNU timeout이 없다.
#
# 실행(맥): ./load/mqtt-ramp.sh          (박스·이미지·단계는 아래 env로 오버라이드)
#   예: STEPS="2000 5000 10000" STEP_SECONDS=90 ./load/mqtt-ramp.sh
set -euo pipefail
cd "$(dirname "$0")/.."

BOX="${BOX:-192.168.219.124}"              # 박스(SUT) IP
IMG="${IMG:-emqx/emqtt-bench:latest}"      # 재현성: docker inspect --format '{{.Id}}' 로 digest 확인해 M-MQTT.md에 핀
QOS="${QOS:-1}"                            # 1=at-least-once(중복은 ON CONFLICT dedup) · 측정 변수
CONNECT_INTERVAL="${CONNECT_INTERVAL:-2}"  # ms, 커넥션 램프 간격(10k → ~20s에 다 붙음)
PUB_INTERVAL="${PUB_INTERVAL:-1000}"       # ms, 디바이스당 발행 주기 → 1Hz
STEP_SECONDS="${STEP_SECONDS:-120}"        # 단계별 지속(warm-up 흡수 후 steady-state 확보)
STEPS="${STEPS:-2000 4000 6000 8000 10000}"
BENCH_NAME="${BENCH_NAME:-locus-mqtt-bench}"
PAYLOAD="$PWD/load/mqtt-payload.json.tmpl"

cleanup() { docker rm -f "$BENCH_NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

run_step() {   # $1=clients  $2=seconds
  cleanup
  docker run --rm --name "$BENCH_NAME" --network host \
    --ulimit nofile=1048576:1048576 \
    -v "${PAYLOAD}:/payload.json:ro" \
    "$IMG" pub -h "$BOX" -p 1883 \
    -c "$1" -i "$CONNECT_INTERVAL" -I "$PUB_INTERVAL" \
    -t 'telemetry/%i' -q "$QOS" \
    -m 'template:///payload.json' &
  local pid=$!
  sleep "$2"
  docker kill "$BENCH_NAME" >/dev/null 2>&1 || true
  wait "$pid" 2>/dev/null || true
}

echo "박스=$BOX 이미지=$IMG qos=$QOS 단계=[$STEPS] 각 ${STEP_SECONDS}s"
for C in $STEPS; do
  echo "===== rate ${C}/s (clients=${C}, epoch=$(date +%s)) ====="
  run_step "$C" "$STEP_SECONDS"
  sleep 5
done
echo "램프 종료. 카운터·DB 정산은 M-MQTT.md 절차 참조."
