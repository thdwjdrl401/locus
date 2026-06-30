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
}
