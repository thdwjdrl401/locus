#!/usr/bin/env bash
# SUT(타깃 박스)에서 앱을 측정용으로 실행한다.
# 고정 JVM 플래그 + GC 로그 → 측정 재현성 + M1 GC/힙 진단.
#
# 사전: ./gradlew bootJar  (build/libs/locus-*.jar 생성)
# 실행: scripts/run-app.sh
#
# 메모리 예산(8GB 박스): 힙 1.5G 고정. MySQL 버퍼풀 2G(compose) + OS → 스왑 회피.
# 플래그는 마일스톤마다 조정하되, 한 측정 세션 안에서는 고정한다.
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=$(ls build/libs/locus-*.jar 2>/dev/null | grep -v plain | head -1 || true)
if [[ -z "${JAR}" ]]; then
  echo "jar 없음. 먼저 ./gradlew bootJar 실행" >&2
  exit 1
fi

mkdir -p logs
echo "실행: ${JAR}"
exec java \
  -Xms1500m -Xmx1500m \
  -XX:+UseG1GC \
  -Xlog:gc*:file=logs/gc.log:time,uptime,level,tags \
  -jar "${JAR}" "$@"
