# 측정 런북 (2-머신)

타깃 박스(SUT)가 부하를 어디까지 버티는지 재고 최적화하기 위한 절차.
**SUT(우분투 박스)** = Locus 앱 + Locus 인프라(docker: TimescaleDB·Redis·Mosquitto). **맥북** = k6(부하) + Prometheus/Grafana(관측). 유선 LAN.

> **공존 전제**: 박스엔 다른 서비스용 시스템 MySQL(3306, 거의 무부하)이 영구 상주한다(Locus와 무관한 별개 서비스).
> 운영=측정 일치를 위해 **끄지 않고** 측정 내내 idle·동일 상태로 둔다.
> Locus 저장소는 **docker TimescaleDB**(M2에서 MySQL에서 전환, [ADR 0008](../decisions/0008-telemetry-store-timescaledb.md))를 쓰고, **박스 ≈3/4(코어 0–5, 메모리 ~6G)** 로 격리한다(시스템 서비스+OS에 1/4 예약). 절대수치는 이 3/4 샌드박스 종속 → **before/after 비율로 해석**.

```
[박스 = SUT]  앱(java -jar) + 인프라(docker: TimescaleDB·Redis·Mosquitto)
     ▲ 8093 부하        ▲ 8093/actuator/prometheus 스크레이프
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

# .env로 포트·자원 격리를 고정한다.
cp .env.example .env
#  → LOCUS_CPUSET=0-5   ← 측정 baseline. 이게 없으면 인프라가 코어 핀 없이 떠서 이전 측정과 비교 불가.
#  → 박스에 시스템 PostgreSQL이 5432를 점유하면 분리:
#      DB_HOST_PORT=5433
#      DB_URL=jdbc:postgresql://localhost:5433/locus?reWriteBatchedInserts=true

# 부하 포트 LAN 개방(인증 없는 M0 엔드포인트 → LAN만, 공개는 nginx 443) + 스왑 회피(HDD 스왑은 측정을 무효화)
sudo ufw allow from 192.168.219.0/24 to any port 8093
sudo sysctl vm.swappiness=10
ip -4 addr | grep inet      # ← 박스 IP 기억 (맥에서 씀)
```

## 1. 매 측정 — 박스에서 빌드·DB 기동·앱 실행
> 솔로 측정 루프라 박스 한 곳에서 빌드·실행한다(jar는 어디서 빌드해도 동일). 단 **측정 직전 Gradle 데몬을 꺼** 측정 중 RAM 오염을 막는다. (CI/CD·플릿 규모로 가면 빌드를 박스 밖으로 분리 — ROADMAP "CD 자동화" 가드레일.)
```bash
cd locus
git pull                          # 최신 마일스톤 코드
docker compose up -d              # 인프라만: TimescaleDB(pg16, shared_buffers 2G, cpuset 0-5, mem 3G)
                                  #           + Redis + Mosquitto + node-exporter
                                  # ⚠ --profile app 을 붙이지 않는다 — 컨테이너 앱이 같이 뜨면
                                  #   호스트 JVM 앱과 같은 코어(0-5)를 나눠 써 교란변수가 된다.
./gradlew bootJar                 # 박스에서 빌드
./gradlew --stop                  # 측정 전 Gradle 데몬 종료 → 측정 중 RAM 오염 0
scripts/run-app.sh                # jar를 taskset -c 0-5로 실행, 힙 1.5G 고정, logs/gc.log

# 기동 직후 위생 점검: 코어 핀이 걸렸고, 시스템 서비스는 그대로고, 스왑은 0이어야 한다.
docker inspect -f '{{.HostConfig.CpusetCpus}}' locus-timescaledb   # 0-5 확인 (비어 있으면 .env의 LOCUS_CPUSET 누락)
systemctl is-active mysql          # 박스의 다른 서비스 — 공존, 끄지 않음
free -h                            # Swap used = 0 확인 (HDD 스왑 들어가면 측정 무효)
```
> 깨끗한 baseline은 Locus 볼륨만 초기화: `docker compose down -v && docker compose up -d` (박스의 다른 서비스와 무관).

## 2. 매 측정 — 맥북에서 관측 + 부하
```bash
cd locus
cp monitoring/prometheus.yml.example monitoring/prometheus.yml
#  → targets를 박스 IP:8093 으로 수정

docker compose -f docker-compose.monitoring.yml up -d   # Prometheus+Grafana
#  Grafana http://localhost:3000 에서 박스 메트릭 뜨는지 확인
#  디스크 메트릭: 박스 docker-compose의 node-exporter(9100)를 Prometheus가 긁음(job_name: node).
#  Prometheus http://localhost:9090/targets 에서 locus·node 둘 다 UP 확인.

# 연결 확인
curl -s http://박스IP:8093/actuator/prometheus | head

# 부하 (목적별 택1)
k6 run -e BASE_URL=http://박스IP:8093 load/telemetry-baseline.js   # 닫힌 모델: 고정 50VU baseline
k6 run -e BASE_URL=http://박스IP:8093 load/telemetry-stress.js     # 닫힌 모델: VU 램프로 포화점 탐색
k6 run -e BASE_URL=http://박스IP:8093 load/telemetry-capacity.js   # 열린 모델: "1Hz 디바이스 N대" capacity
```

> **부하 모델 구분**
> - *닫힌 모델*(VU 기반, baseline/stress): VU가 응답 받고 다음 요청 → **동시 연결 N개**를 잼.
> - *열린 모델*(도착률 기반, capacity): 응답시간과 무관하게 **초당 N건 도착** → 1 req/s = 1Hz 디바이스 1대이므로 **target == 1Hz 디바이스 수**.
> - **capacity 포화점**(= 지속 가능한 최대 1Hz 디바이스 수): 처리량이 target을 못 따라가거나(plateau≈최대 처리량) `dropped_iterations>0`·p95 급등하는 직전 단계. 라이브 출력의 VUs·dropped_iterations + **Grafana**(throughput·CPU·HikariCP + node_exporter 디스크: %util·쓰기 지연·평균 요청 크기)로 디스크가 병목인지 확증. **iostat 로그 안 씀 — 디스크도 node_exporter→Grafana로 본다.**

## 3. 기록 → `docs/measurements/Mx.md`
- **환경 블록**: 박스 사양(i7-6700HQ 4c/8t, 8GB, 5400rpm HDD), JDK 빌드, DB 버전(TimescaleDB pg16), JVM 플래그, 데이터 행수, 네트워크(유선 LAN, RTT).
- **수치**: k6의 p95/p99·처리량(req/s)·에러율 + Grafana(서버측: GC pause, HikariCP, CPU/IO).
- **Grafana 스크린샷** 첨부.
- **해석 한 단락**: 어디가 병목인지, 왜, 다음 개선 후보.

## 측정 위생 (비교 가능성)
- **한 번에 한 변수만** 바꾼다(나머지 고정).
- **3회 측정 + 중앙값/편차** (단일 수치는 노이즈).
- **램프업 구간 버리고 steady-state**만 해석(JIT·버퍼풀 워밍).
- **절대수치는 이 HDD 박스 종속** → before/after **비율**이 결론.
- 측정 끝나면 그 커밋에 마일스톤 태그(`git tag -a m0 -m "..."`)+ push.
