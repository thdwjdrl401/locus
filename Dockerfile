# Locus 앱 이미지 (M8 선행 — 컨테이너화).
#
# 측정과의 관계: 성능 측정은 계속 호스트 JVM에서 한다(scripts/run-app.sh, RUNBOOK).
# 이 이미지는 데모·리뷰용 실행 경로다 — `docker compose --profile app up -d` 한 줄로
# 인프라 + 앱 + 시뮬레이터가 뜨게 하는 것이 목적. 기본 `docker compose up -d`는 인프라만 띄운다.
#
# 빌드: docker build -t locus-app:local .

# ---- 1단계: 빌드 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 빌드 스크립트·wrapper를 먼저 복사해 의존성 해석을 별도 레이어로 캐시한다(소스만 바뀌면 재다운로드 없음).
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet dependencies || true

COPY src src
# 테스트는 CI가 돌린다(Testcontainers=Docker 필요라 이미지 빌드 중엔 못 돈다).
# installGitHooks는 .git이 없으면 건너뛴다(build.gradle.kts의 onlyIf).
RUN ./gradlew --no-daemon bootJar -x test

# 레이어 분해: 의존성 / 앱 클래스를 나눠 재배포 시 변경분만 전송되게 한다.
RUN mkdir -p /workspace/extracted \
    && java -Djarmode=tools -jar build/libs/locus-*[!n].jar extract --layers --launcher \
       --destination /workspace/extracted

# ---- 2단계: 실행 ----
FROM eclipse-temurin:21-jre AS runtime

# healthcheck용. 그 외 패키지는 넣지 않는다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 비루트 실행.
RUN groupadd --system locus && useradd --system --gid locus --home-dir /app --shell /usr/sbin/nologin locus
WORKDIR /app

# 변경 빈도가 낮은 레이어부터 복사. `extract --layers`는 destination 바로 아래에 레이어 디렉터리를 만든다
# (jar 이름 하위 디렉터리 없음). 경로를 글롭으로 쓰면 매칭 실패해도 빌드가 통과하고 /app이 비어버린다.
COPY --from=build --chown=locus:locus /workspace/extracted/dependencies/ ./
COPY --from=build --chown=locus:locus /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=locus:locus /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=locus:locus /workspace/extracted/application/ ./

# 빌드 단계에서 잘못된 경로로 빈 이미지가 나오는 걸 막는 가드.
RUN test -f /app/org/springframework/boot/loader/launch/JarLauncher.class

USER locus
EXPOSE 8093

# 기본 JVM 플래그는 scripts/run-app.sh(측정 경로)와 같은 값으로 맞춘다 — 컨테이너로 띄웠을 때
# 힙·GC 조건이 달라 측정값이 어긋나지 않게. compose가 JAVA_OPTS로 덮어쓸 수 있다.
ENV JAVA_OPTS="-Xms1500m -Xmx1500m -XX:+UseG1GC"

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8093/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
