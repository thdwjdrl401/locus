# 측정 런북 (2-머신)

타깃 박스(SUT)가 부하를 어디까지 버티는지 재고 최적화하기 위한 절차.
**SUT(우분투 박스)** = Locus 앱 + Locus MySQL(docker). **맥북** = k6(부하) + Prometheus/Grafana(관측). 유선 LAN.

> **공존 전제**: 박스엔 다른 서비스용 시스템 MySQL(3306, 거의 무부하)이 영구 상주한다.
> 운영=측정 일치를 위해 **시스템 MySQL은 끄지 않고** 측정 내내 idle·동일 상태로 둔다.
> Locus는 **자기 docker MySQL을 3307**로 분리해 쓰고, **박스 ≈3/4(코어 0–5, 메모리 ~6G)** 로 격리한다(시스템 DB+OS에 1/4 예약). 절대수치는 이 3/4 샌드박스 종속 → **before/after 비율로 해석**.

```
[박스 = SUT]  앱(java -jar) + MySQL(docker)
     ▲ 8080 부하        ▲ 8080/actuator/prometheus 스크레이프
     │ 유선 LAN          │ 유선 LAN
[맥북]  k6           +   Prometheus + Grafana
```

## 0. 일회성 준비 — 박스
```bash
# JDK 21 (Temurin)
sudo apt-get update && sudo apt-get install -y temurin-21-jdk \
  || (sudo apt-get install -y wget gnupg && \
      wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
      echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/adoptium.list && \
      sudo apt-get update && sudo apt-get install -y temurin-21-jdk)
java -version    # 21 확인

# Docker는 이미 설치(앞 단계). repo 클론
git clone https://github.com/thdwjdrl401/locus.git && cd locus

# Locus는 시스템 MySQL과 포트가 겹치지 않게 3307로 분리한다(.env).
cp .env.example .env
#  → .env에서: MYSQL_HOST_PORT=3307
#             DB_URL=jdbc:mysql://localhost:3307/locus?serverTimezone=UTC&characterEncoding=UTF-8

# 부하 포트 LAN 개방 + 스왑 회피(HDD라 스왑=재앙)
sudo ufw allow 8080/tcp
sudo sysctl vm.swappiness=10
ip -4 addr | grep inet      # ← 박스 IP 기억 (맥에서 씀)
```

## 1. 매 측정 — 앱+DB 기동 (빌드는 맥, 실행은 박스)
> **빌드는 박스 밖(맥)에서 하고 jar만 scp로 전달**한다. 이유: 박스에 Gradle·의존성 캐시·빌드 IO를 안 얹고(8GB/HDD 보호), jar는 플랫폼 독립이라 박스에서 동일하게 돈다. CI/CD 정신(빌드 산출물 그대로 승격)과도 일치.
```bash
# 맥: 빌드 후 jar만 박스로
./gradlew bootJar
scp build/libs/locus-*.jar lazy@박스IP:~/locus/build/libs/

# 박스: DB 기동 + 앱 실행
cd locus
git pull                          # 설정 파일만(compose/scripts/.env/load) — 박스는 빌드 안 함
docker compose up -d              # Locus MySQL (8.0.40, 3307, buffer_pool 2G, cpuset 0-5, mem 3G)
scripts/run-app.sh                # scp된 jar를 taskset -c 0-5로 실행, 힙 1.5G 고정, logs/gc.log

# 기동 직후 위생 점검: 시스템 MySQL은 살아있고, 스왑은 0이어야 한다.
systemctl is-active mysql          # 시스템 DB active (공존 — 끄지 않음)
free -h                            # Swap used = 0 확인 (HDD 스왑 들어가면 측정 무효)
```
> 깨끗한 baseline은 Locus DB만 초기화: `docker compose down -v && docker compose up -d` (Locus 볼륨만 삭제 — 시스템 MySQL과 무관).

## 2. 매 측정 — 맥북에서 관측 + 부하
```bash
cd locus
cp monitoring/prometheus.yml.example monitoring/prometheus.yml
#  → targets를 박스 IP:8080 으로 수정

docker compose -f docker-compose.monitoring.yml up -d   # Prometheus+Grafana
#  Grafana http://localhost:3000 에서 박스 메트릭 뜨는지 확인

# 연결 확인
curl -s http://박스IP:8080/actuator/prometheus | head

# 부하 (둘 중 택1)
k6 run -e BASE_URL=http://박스IP:8080 load/telemetry-baseline.js   # 고정 부하 baseline
k6 run -e BASE_URL=http://박스IP:8080 load/telemetry-stress.js     # 한계(knee) 탐색
```

## 3. 기록 → `docs/measurements/Mx.md`
- **환경 블록**: 박스 사양(i7-6700HQ 4c/8t, 8GB, 5400rpm HDD), JDK 빌드, MySQL 버전, JVM 플래그, 데이터 행수, 네트워크(유선 LAN, RTT).
- **수치**: k6의 p95/p99·처리량(req/s)·에러율 + Grafana(서버측: GC pause, HikariCP, CPU/IO).
- **Grafana 스크린샷** 첨부.
- **해석 한 단락**: 어디가 병목인지, 왜, 다음 개선 후보.

## 측정 위생 (비교 가능성)
- **한 번에 한 변수만** 바꾼다(나머지 고정).
- **3회 측정 + 중앙값/편차** (단일 수치는 노이즈).
- **램프업 구간 버리고 steady-state**만 해석(JIT·버퍼풀 워밍).
- **절대수치는 이 HDD 박스 종속** → before/after **비율**이 결론.
- 측정 끝나면 그 커밋에 마일스톤 태그(`git tag -a m0 -m "..."`)+ push.
