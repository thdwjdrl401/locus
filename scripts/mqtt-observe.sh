#!/usr/bin/env bash
# 박스에서 M-MQTT 측정 관측을 한 번에 띄운다. 출력 = docs/measurements/M-MQTT-raw/<RUN>/.
# Prometheus/Grafana가 긁는 것(received/dropped/inserted·CPU·디스크)이 아니라, exporter 없는 값을 로그로 남긴다:
#   sys.log    — 브로커 $SYS: 발행 드롭·클라이언트 수·메시지 수 (경계 A 손실 귀속)
#   stream.log — Redis XLEN·컨슈머 lag (경계 C 적체·트림 위험. lag>MAXLEN이면 유실)
#   vmstat.log — 메모리 궤적 (스왑 off라 free 바닥 = OOM 임박)
#   snap.log   — 앱 카운터(received/dropped/inserted) + DB count 주기 스냅샷 (3경계 정산 타임라인)
#
# 실행(박스): RUN=ramp1 scripts/mqtt-observe.sh     (Ctrl-C로 전부 종료)
#   부하(맥 ./load/mqtt-ramp.sh) 시작 직전에 띄우고, 부하 끝나면 Ctrl-C.
set -euo pipefail
cd "$(dirname "$0")/.."

RUN="${RUN:-run-$(date +%s)}"
OUT="docs/measurements/M-MQTT-raw/${RUN}"
STREAM_KEY="${STREAM_KEY:-telemetry.stream}"
APP="${APP:-localhost:8093}"
mkdir -p "$OUT"
echo "관측 출력 → $OUT  (Ctrl-C 종료)"

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

# 1) 브로커 $SYS (경계 A)
( docker exec locus-mosquitto mosquitto_sub -h localhost \
    -t '$SYS/broker/publish/messages/dropped' \
    -t '$SYS/broker/clients/#' \
    -t '$SYS/broker/messages/#' -v \
    | while read -r l; do echo "$(date +%s) $l"; done ) > "$OUT/sys.log" 2>&1 &
pids+=($!)

# 2) Redis XLEN·lag (경계 C)
( while true; do
    echo "$(date +%s) XLEN=$(docker exec locus-redis redis-cli XLEN "$STREAM_KEY" 2>/dev/null)"
    docker exec locus-redis redis-cli XINFO GROUPS "$STREAM_KEY" 2>/dev/null | grep -E 'name|lag' || true
    sleep 2
  done ) > "$OUT/stream.log" 2>&1 &
pids+=($!)

# 3) 메모리 궤적 (OOM 감시)
( vmstat -t -SM 2 ) > "$OUT/vmstat.log" 2>&1 &
pids+=($!)

# 4) 앱 카운터 + DB count 스냅샷 (정산 타임라인)
( while true; do
    ts=$(date +%s)
    c=$(curl -s "$APP/actuator/prometheus" \
        | grep -E 'locus_mqtt_(received|dropped)_total|locus_ingest_inserted_total' \
        | awk '{printf "%s=%s ", $1, $2}')
    db=$(docker exec locus-timescaledb psql -U locus -d locus -tAc 'SELECT count(*) FROM telemetry;' 2>/dev/null | tr -d '[:space:]')
    echo "$ts ${c}db=$db"
    sleep 5
  done ) > "$OUT/snap.log" 2>&1 &
pids+=($!)

echo "관측 PID: ${pids[*]}  — 부하 돌리고, 끝나면 Ctrl-C"
wait
