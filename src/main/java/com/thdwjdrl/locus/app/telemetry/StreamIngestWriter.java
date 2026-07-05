package com.thdwjdrl.locus.app.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 발행 (M4b B — {@code mode=stream}). 검증된 텔레메트리를 Stream에 XADD한다.
 *
 * <p>인메모리 큐(A2)를 대체하는 fan-out 지점(ADR 0007): 같은 Stream을 {@code storage} 컨슈머 그룹(적재)과 {@code
 * monitoring} 컨슈머 그룹(실시간 push)이 각자 소비한다. Stream은 단기 버퍼(MAXLEN 절단, 청크 4), 보존·리플레이는 TimescaleDB.
 *
 * <p>페이로드는 {@link TelemetryResponse} JSON — 적재(→Telemetry 복원)와 push 둘 다 이걸로 복원한다. 필드 하나({@code
 * data})에 담는다.
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "stream")
public class StreamIngestWriter implements TelemetryIngestPort {

    /** 스트림 엔트리에서 JSON 페이로드가 담기는 필드명(스트림 소비자들의 와이어 계약). */
    public static final String PAYLOAD_FIELD = "data";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final IngestProperties props;

    public StreamIngestWriter(
            StringRedisTemplate redis, ObjectMapper json, IngestProperties props) {
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    @Override
    public void submit(Telemetry telemetry) {
        String payload = write(TelemetryResponse.from(telemetry));
        // XADD ... MAXLEN ~ N: 근사 절단으로 스트림 길이 bound(단기 버퍼). 보존은 TimescaleDB.
        redis.opsForStream()
                .add(
                        StreamRecords.newRecord()
                                .in(props.getStreamKey())
                                .ofMap(Map.of(PAYLOAD_FIELD, payload)),
                        XAddOptions.maxlen(props.getStreamMaxlen()).approximateTrimming(true));
    }

    private String write(TelemetryResponse r) {
        try {
            return json.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("스트림 직렬화 실패: " + r.deviceId(), e);
        }
    }
}
