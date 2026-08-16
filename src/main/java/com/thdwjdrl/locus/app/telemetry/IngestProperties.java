package com.thdwjdrl.locus.app.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 수집 경로 설정 ({@code locus.ingest.*}).
 *
 * <p>M1 실험을 변수로 끊어 측정하기 위한 손잡이. {@code mode}로 적재 전략을 갈아끼운다(ADR 0004 이음새):
 *
 * <ul>
 *   <li>{@code direct}(기본) — 요청당 단건 저장 + Device upsert(현 동작 = M1 A0).
 *   <li>{@code queue} — 인메모리 큐 + 워커 배치 적재(M1 A2). fsync를 N건당 1회로 분할.
 *   <li>{@code stream} — Redis Stream 발행(M4b B). fan-out: storage/monitoring 컨슈머 그룹(ADR 0007).
 * </ul>
 *
 * <p>나머지는 배치 거동 손잡이: 배치 크기·최대 지연·큐 용량과, Device upsert 교란변수 격리용 토글({@code device-upsert=false} = M1
 * A2-x).
 */
@Component
@ConfigurationProperties(prefix = "locus.ingest")
public class IngestProperties {

    /** 적재 전략: {@code direct}(A0, 기본) 또는 {@code queue}(A2). */
    private String mode = "direct";

    /** 큐 용량(queue 모드). 가득 차면 들어오는 텔레메트리를 drop(유실 허용). */
    private int queueCapacity = 10_000;

    /** 한 트랜잭션·한 배치로 묶는 최대 행 수. */
    private int batchSize = 500;

    /** 배치가 덜 찼을 때 강제로 flush하는 최대 대기(ms). */
    private long maxDelayMs = 200;

    /** Device upsert 수행 여부. {@code false} = M1 A2-x(핫패스 UPDATE 교란변수 격리). */
    private boolean deviceUpsert = true;

    /** 배치 워커 스레드 수(queue 모드). 병렬 적재로 단일 워커 처리율 한계를 넘긴다(M2-par). DB 풀 크기 이하로 둔다. */
    private int workers = 1;

    /** Redis Stream 키(stream 모드, ADR 0007 fan-out 버퍼). */
    private String streamKey = "telemetry.stream";

    /**
     * Stream 최대 길이(근사 절단, MAXLEN). 초과분은 오래된 것부터 버림 — 단기 버퍼, 보존은 TimescaleDB.
     *
     * <p>사이징 규칙(M4b 측정): (a) Redis {@code maxmemory} 안에 들 것 — 엔트리 ~0.5KB라 {@code MAXLEN×0.5KB}가
     * maxmemory를 넘으면 트리밍이 못 걸려 OOM(기본 1,000,000×0.5KB≈512MB > 256mb에서 실제 OOM 발생). (b) worst-case
     * storage 컨슈머 랙보다 클 것 — 트림은 oldest부터라 {@code MAXLEN < 미소비 랙}이면 미소비분이 잘려 유실(내구 경로). 박스(maxmemory
     * 256mb·업링크 10k SLO)에서 400,000이 무손실 최소값. maxmemory를 바꾸면 역산해 조정.
     */
    private long streamMaxlen = 400_000;

    /**
     * 수집 경로에서 <b>새로 생성되는</b> Device에 넣을 조직(테넌트) id. {@code null}(기본)이면 org 없이 만든다.
     *
     * <p>org는 원래 enrollment(인증)가 정할 값이라 수집 경로가 정하는 게 최종형은 아니다. 다만 인증이 보류인 동안 org 없는 디바이스는 <b>어디에도 안
     * 보인다</b> — 조회({@code GET /api/telemetry/latest?org=})·push({@code /topic/org/{org}})·지오펜스 판정이
     * 전부 org로 라우팅하기 때문. 그래서 자체 등록 디바이스에 넣을 기본 org를 설정으로 연다. 인증 도입 시 enrollment가 이 자리를 대체한다.
     *
     * <p>기존 행의 org는 덮어쓰지 않는다(생성 시에만 적용) — 이미 배정된 조직을 수집이 되돌리면 안 된다.
     */
    private String defaultOrg = null;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public void setMaxDelayMs(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
    }

    public boolean isDeviceUpsert() {
        return deviceUpsert;
    }

    public void setDeviceUpsert(boolean deviceUpsert) {
        this.deviceUpsert = deviceUpsert;
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }

    public String getStreamKey() {
        return streamKey;
    }

    public void setStreamKey(String streamKey) {
        this.streamKey = streamKey;
    }

    public long getStreamMaxlen() {
        return streamMaxlen;
    }

    public void setStreamMaxlen(long streamMaxlen) {
        this.streamMaxlen = streamMaxlen;
    }

    public String getDefaultOrg() {
        return defaultOrg;
    }

    /** 빈 문자열은 null로 접는다 — {@code INGEST_DEFAULT_ORG=} 같은 빈 env가 org=""인 디바이스를 만들지 않게. */
    public void setDefaultOrg(String defaultOrg) {
        this.defaultOrg = (defaultOrg == null || defaultOrg.isBlank()) ? null : defaultOrg.strip();
    }
}
