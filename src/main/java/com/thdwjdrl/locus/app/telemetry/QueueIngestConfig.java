package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.core.domain.Telemetry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * queue 모드(M1 A2) 전용 배선. {@code locus.ingest.mode=queue}일 때만 활성.
 *
 * <p>큐는 {@link QueuedIngestWriter}(생산자)와 {@link TelemetryBatchWorker}(소비자)가 공유한다. 유계 큐라 가득 차면 생산자가
 * drop한다(유실 허용).
 */
@Configuration
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "queue")
public class QueueIngestConfig {

    @Bean
    public BlockingQueue<Telemetry> ingestQueue(IngestProperties props) {
        return new ArrayBlockingQueue<>(props.getQueueCapacity());
    }
}
