package com.thdwjdrl.locus.app.device;

import java.util.HashMap;
import java.util.Map;

/**
 * AMR(자율이동로봇) 전용 메트릭의 타입 있는 표현.
 *
 * <p>{@code metrics} JSON 맵 ↔ 타입 변환의 단일 소유처(폰의 {@link PhoneMetrics}에 대응). 상태 필드는 문자열 코드로 보관해 core
 * enum을 늘리지 않는다(디바이스별 상태 어휘는 app에만 산다). 필드 근거·값 어휘는 docs/reference/amr-telemetry.md.
 *
 * @param batteryPercent 배터리 잔량(0~100)
 * @param batteryStatus CHARGING / DISCHARGING / FULL
 * @param operatingMode AUTOMATIC / MANUAL / SERVICE
 * @param driving 주행 중 여부
 * @param estopState ESTOPPED / NOT_ESTOPPED
 * @param faultLevel OK / WARN / FATAL
 * @param odomX 오도메트리 x(m, 맵 기준)
 * @param odomY 오도메트리 y(m, 맵 기준)
 * @param odomTheta 헤딩(rad)
 * @param mapId 현재 맵 식별자
 */
public record AmrMetrics(
        Integer batteryPercent,
        String batteryStatus,
        String operatingMode,
        Boolean driving,
        String estopState,
        String faultLevel,
        Double odomX,
        Double odomY,
        Double odomTheta,
        String mapId) {

    /** 타입 → generic 메트릭 맵 (저장용). */
    public Map<String, Object> toMetrics() {
        Map<String, Object> m = new HashMap<>();
        putIfPresent(m, "batteryPercent", batteryPercent);
        putIfPresent(m, "batteryStatus", batteryStatus);
        putIfPresent(m, "operatingMode", operatingMode);
        putIfPresent(m, "driving", driving);
        putIfPresent(m, "estopState", estopState);
        putIfPresent(m, "faultLevel", faultLevel);
        putIfPresent(m, "odomX", odomX);
        putIfPresent(m, "odomY", odomY);
        putIfPresent(m, "odomTheta", odomTheta);
        putIfPresent(m, "mapId", mapId);
        return m;
    }

    /** generic 메트릭 맵 → 타입 (읽기용). */
    public static AmrMetrics fromMetrics(Map<String, Object> m) {
        if (m == null) {
            return new AmrMetrics(null, null, null, null, null, null, null, null, null, null);
        }
        return new AmrMetrics(
                asInt(m.get("batteryPercent")),
                asString(m.get("batteryStatus")),
                asString(m.get("operatingMode")),
                (Boolean) m.get("driving"),
                asString(m.get("estopState")),
                asString(m.get("faultLevel")),
                asDouble(m.get("odomX")),
                asDouble(m.get("odomY")),
                asDouble(m.get("odomTheta")),
                asString(m.get("mapId")));
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static Integer asInt(Object v) {
        return (v instanceof Number n) ? n.intValue() : null;
    }

    private static Double asDouble(Object v) {
        return (v instanceof Number n) ? n.doubleValue() : null;
    }

    private static String asString(Object v) {
        return (v == null) ? null : v.toString();
    }
}
