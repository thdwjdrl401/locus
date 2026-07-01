package com.thdwjdrl.locus.app.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 최신상태 캐시 — 조직별 Redis HASH {@code latest:{orgId}}(field=deviceId, value=JSON).
 *
 * <p>조직 파티션이라 조직 스냅샷 = HGETALL 한 번(O(디바이스)). DB의 DISTINCT ON은 O(전체행)이라 이력이 쌓이면 급락한다(측정:
 * docs/measurements/M4a.md). 진실원본은 DB — 캐시는 파생이라 미스 시 DB로 채우고, 재빌드 가능(영속화 없음, docker-compose 참조).
 *
 * <p>{@code locus.read.latest-source=cache}일 때만 활성(before/after 토글). 값은 읽기 모델 {@link
 * TelemetryResponse}(Jackson record) 그대로.
 */
@Component
@ConditionalOnProperty(name = "locus.read.latest-source", havingValue = "cache")
public class RedisLatestStateLookup implements LatestStateLookup {

    private static final String KEY_PREFIX = "latest:";
    private static final String ORGS_KEY = "latest:orgs"; // 파티션 인덱스(findAll fan-out용)

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TelemetryRepository repo; // 미스 fallback + warm

    public RedisLatestStateLookup(
            StringRedisTemplate redis, ObjectMapper json, TelemetryRepository repo) {
        this.redis = redis;
        this.json = json;
        this.repo = repo;
    }

    @Override
    public List<TelemetryResponse> findByOrg(String orgId) {
        Map<Object, Object> entries = redis.opsForHash().entries(KEY_PREFIX + orgId);
        if (entries.isEmpty()) {
            return loadFromDb(orgId); // 미스 → DB로 채움
        }
        return entries.values().stream().map(v -> read((String) v)).toList();
    }

    @Override
    public List<TelemetryResponse> findAll() {
        Set<String> orgs = redis.opsForSet().members(ORGS_KEY);
        if (orgs == null || orgs.isEmpty()) {
            return List.of();
        }
        return orgs.stream().flatMap(orgId -> findByOrg(orgId).stream()).toList();
    }

    @Override
    public void put(String orgId, TelemetryResponse latest) {
        redis.opsForHash().put(KEY_PREFIX + orgId, latest.deviceId(), write(latest));
        redis.opsForSet().add(ORGS_KEY, orgId);
    }

    /** 캐시 미스 시 DB(DISTINCT ON per-org)로 조회하고 캐시를 채운다. */
    private List<TelemetryResponse> loadFromDb(String orgId) {
        List<TelemetryResponse> latest =
                repo.findLatestByOrg(orgId).stream().map(TelemetryResponse::from).toList();
        latest.forEach(r -> put(orgId, r));
        return latest;
    }

    private String write(TelemetryResponse r) {
        try {
            return json.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("최신상태 직렬화 실패: " + r.deviceId(), e);
        }
    }

    private TelemetryResponse read(String s) {
        try {
            return json.readValue(s, TelemetryResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("최신상태 역직렬화 실패", e);
        }
    }
}
