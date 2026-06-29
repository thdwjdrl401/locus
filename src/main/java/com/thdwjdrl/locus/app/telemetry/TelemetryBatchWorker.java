package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.core.domain.Telemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 배치 적재 워커 (M1 A2). 전용 스레드 하나가 큐에서 N건을 모아 {@link TelemetryBatchDao}로 한 번에 적재한다 → fsync를 N건당 1회로 분할.
 *
 * <p>flush 트리거: 큐에서 첫 건을 {@code maxDelayMs}까지 기다렸다가, 즉시 {@code drainTo}로 현재 쌓인 것을 batchSize까지 끌어모아
 * 적재. 고부하면 큐가 항상 차 있어 batchSize로 가득 찬 배치가 나가고, 저부하면 작은 배치가 낮은 지연으로 나간다.
 *
 * <p>{@link SmartLifecycle}: 컨텍스트 종료 시 {@link #stop()}에서 큐 잔여분을 flush해 정상 종료 시 유실을 막는다(앱 크래시 시 유실은
 * A2의 의도된 한계).
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "queue")
public class TelemetryBatchWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelemetryBatchWorker.class);

    private final BlockingQueue<Telemetry> queue;
    private final TelemetryBatchDao dao;
    private final IngestProperties props;

    private final Timer flushTimer;
    private final DistributionSummary batchSizeSummary;
    private final Counter inserted;
    private final Counter flushErrors;

    private volatile boolean running = false;
    private Thread worker;

    public TelemetryBatchWorker(
            BlockingQueue<Telemetry> queue,
            TelemetryBatchDao dao,
            IngestProperties props,
            MeterRegistry meters) {
        this.queue = queue;
        this.dao = dao;
        this.props = props;
        this.flushTimer =
                Timer.builder("locus.ingest.flush").description("배치 적재 1회 소요").register(meters);
        this.batchSizeSummary =
                DistributionSummary.builder("locus.ingest.batch.size")
                        .description("flush당 행 수")
                        .register(meters);
        this.inserted =
                Counter.builder("locus.ingest.inserted")
                        .description("적재 시도한 텔레메트리 행 수(중복 IGNORE 포함)")
                        .register(meters);
        this.flushErrors =
                Counter.builder("locus.ingest.flush.errors")
                        .description("배치 적재 실패 횟수")
                        .register(meters);
    }

    @Override
    public void start() {
        running = true;
        worker = new Thread(this::runLoop, "telemetry-batch-worker");
        worker.setDaemon(true);
        worker.start();
        log.info(
                "텔레메트리 배치 워커 시작 (batchSize={}, maxDelayMs={}, deviceUpsert={})",
                props.getBatchSize(),
                props.getMaxDelayMs(),
                props.isDeviceUpsert());
    }

    private void runLoop() {
        List<Telemetry> buffer = new ArrayList<>(props.getBatchSize());
        while (running) {
            try {
                Telemetry first = queue.poll(props.getMaxDelayMs(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue; // 대기 시간 내 도착 없음 — 다시 대기
                }
                buffer.add(first);
                queue.drainTo(buffer, props.getBatchSize() - 1);
                flush(buffer);
            } catch (InterruptedException e) {
                // stop()이 running=false 후 interrupt한 정상 종료 경로.
                // 여기서 재인터럽트하면 이어지는 drainRemaining의 JDBC가 깨질 수 있어 그대로 빠져나간다.
                break;
            } finally {
                buffer.clear();
            }
        }
        drainRemaining(); // 정상 종료: 큐에 남은 것 마저 적재
    }

    /** 종료 시 큐 잔여분을 batchSize 단위로 모두 flush. */
    private void drainRemaining() {
        List<Telemetry> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (rest.isEmpty()) {
            return;
        }
        log.info("종료 전 큐 잔여 {}건 flush", rest.size());
        for (int i = 0; i < rest.size(); i += props.getBatchSize()) {
            flush(rest.subList(i, Math.min(i + props.getBatchSize(), rest.size())));
        }
    }

    private void flush(List<Telemetry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        int size = batch.size();
        try {
            flushTimer.record(() -> dao.persistBatch(batch));
            inserted.increment(size);
            batchSizeSummary.record(size);
        } catch (RuntimeException e) {
            // 텔레메트리는 유실 허용 — 한 배치 실패가 워커를 죽이지 않게 삼키고 기록.
            flushErrors.increment();
            log.warn("배치 적재 실패 ({}건 버림): {}", size, e.toString());
        }
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
