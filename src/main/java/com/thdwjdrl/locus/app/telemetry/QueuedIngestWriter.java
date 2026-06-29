package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.core.domain.Telemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.BlockingQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 큐 적재 (M1 A2) — 검증된 텔레메트리를 인메모리 큐에 넣고 즉시 반환(202). 실제 DB 적재는 {@link TelemetryBatchWorker}가 배치로.
 *
 * <p><b>백프레셔 = drop</b>: 큐가 가득 차면(소비 속도 < 유입 속도) 버린다. 텔레메트리는 유실 허용(CLAUDE §3.5)이라 들어오는 요청을 막지 않는 게
 * 낫다. 버린 수는 {@code locus.ingest.dropped} 카운터로 노출해 백프레셔를 관찰한다.
 *
 * <p><b>한계(의도된 것)</b>: 인메모리 큐는 앱 크래시 시 손실된다. 이게 fan-out 브로커(Redis Streams, M4)가 사는 지점([ADR 0007]).
 * A2는 이 한계를 측정으로 드러내는 단계다.
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "queue")
public class QueuedIngestWriter implements TelemetryIngestPort {

    private final BlockingQueue<Telemetry> queue;
    private final Counter dropped;

    public QueuedIngestWriter(BlockingQueue<Telemetry> queue, MeterRegistry meters) {
        this.queue = queue;
        this.dropped =
                Counter.builder("locus.ingest.dropped")
                        .description("큐 가득참으로 버린 텔레메트리 수(백프레셔)")
                        .register(meters);
        meters.gauge("locus.ingest.queue.size", queue, BlockingQueue::size);
    }

    @Override
    public void submit(Telemetry telemetry) {
        if (!queue.offer(telemetry)) {
            dropped.increment();
        }
    }
}
