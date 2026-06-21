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

# .env를 앱에도 넘긴다(compose와 단일 소스). 앱은 DB_URL/DB_USERNAME/DB_PASSWORD를 env로 읽음.
# 값에 &·? 가 있어 `source`는 위험 → 주석/빈 줄 건너뛰고 첫 '='만 분리해 그대로 export.
if [[ -f .env ]]; then
  while IFS='=' read -r key val; do
    [[ "${key}" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${key}" ]] && continue
    export "${key}=${val}"
  done < .env
fi

JAR=$(ls build/libs/locus-*.jar 2>/dev/null | grep -v plain | head -1 || true)
if [[ -z "${JAR}" ]]; then
  echo "jar 없음. 먼저 ./gradlew bootJar 실행" >&2
  exit 1
fi

mkdir -p logs
echo "실행: ${JAR}"

# Locus를 박스의 ≈3/4로 격리: CPU는 core 0–5에 핀(docker MySQL의 cpuset와 동일 경계),
# 메모리는 -Xmx1500m로 바운드 → 시스템 MySQL+OS에 1/4(코어 6–7, ~2G) 예약.
# taskset 없으면(다른 OS) 어피니티 없이 그대로 실행.
PIN=()
if command -v taskset >/dev/null 2>&1; then
  PIN=(taskset -c 0-5)
fi

exec "${PIN[@]}" java \
  -Xms1500m -Xmx1500m \
  -XX:+UseG1GC \
  -Xlog:gc*:file=logs/gc.log:time,uptime,level,tags \
  -jar "${JAR}" "$@"
