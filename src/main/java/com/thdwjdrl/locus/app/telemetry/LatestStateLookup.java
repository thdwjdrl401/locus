package com.thdwjdrl.locus.app.telemetry;

import java.util.List;

/**
 * 최신상태 조회 이음새(ADR 0004 출력 포트). 관제 지도의 "디바이스별 최신"을 조직 파티션 단위로 낸다.
 *
 * <p>구현은 {@link RedisLatestStateLookup}(조직별 HASH {@code latest:{orgId}}). before(DB DISTINCT ON)와의
 * 교체 이음새 — {@code locus.read.latest-source} 토글로 M4a before/after를 측정한다.
 *
 * <p>조직 파티션 근거: 콘솔은 스코프 조회다(전체 관리자=전체, 조직 관리자=자기 조직). "전체"는 별도 구조가 아니라 super-admin 스코프({@link
 * #findAll()}). 상세 스펙: docs/specs/M4-realtime-read-path.md.
 */
public interface LatestStateLookup {

    /** 한 조직의 디바이스별 최신(스냅샷). 캐시 미스 시 DB로 채운다. */
    List<TelemetryResponse> findByOrg(String orgId);

    /** 전체 조직(super-admin) — 조직 파티션 fan-out. */
    List<TelemetryResponse> findAll();

    /** write-through: 한 디바이스의 최신을 그 조직 파티션에 반영. */
    void put(String orgId, TelemetryResponse latest);
}
