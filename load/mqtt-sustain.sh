#!/usr/bin/env bash
# M-MQTT 런3 — 지속 (flat 10k, 5분). 무손실 오라클·steady-state.
#   런1에서 안 죽고 10k를 따라가면 이걸로 무손실 확정. 오라클: DB증가분 == received − dropped(경계 B·C),
#   received vs emqtt-bench sent 총계 차이 = 경계 A(브로커). 상세 docs/measurements/M-MQTT.md.
#   페이로드·함정은 mqtt-ramp.sh 헤더 참조(template:// 파일 모드·%TIMESTAMPMS%·--ulimit).
#
# 실행(맥): ./load/mqtt-sustain.sh
set -euo pipefail
cd "$(dirname "$0")/.."

BOX="${BOX:-192.168.219.124}"
IMG="${IMG:-emqx/emqtt-bench:latest}"
QOS="${QOS:-1}"
CLIENTS="${CLIENTS:-10000}"
CONNECT_INTERVAL="${CONNECT_INTERVAL:-2}"   # 10k → ~20s 커넥션 램프
PUB_INTERVAL="${PUB_INTERVAL:-1000}"        # 1Hz
DURATION="${DURATION:-360}"                 # ~20s 램프 + 300s steady
PAYLOAD="$PWD/load/mqtt-payload.json.tmpl"

echo "===== sustain clients=${CLIENTS} for ${DURATION}s (epoch=$(date +%s)) ====="
timeout "${DURATION}" docker run --rm --network host \
  --ulimit nofile=1048576:1048576 \
  -v "${PAYLOAD}:/payload.json:ro" \
  "$IMG" pub -h "$BOX" -p 1883 \
  -c "$CLIENTS" -i "$CONNECT_INTERVAL" -I "$PUB_INTERVAL" \
  -t 'telemetry/%i' -q "$QOS" \
  -m 'template:///payload.json' || true
echo "지속 종료. 정산·XLEN·$SYS는 M-MQTT.md 절차 참조."
