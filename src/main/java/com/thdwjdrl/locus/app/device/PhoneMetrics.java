package com.thdwjdrl.locus.app.device;

import com.thdwjdrl.locus.core.domain.ActivityType;
import com.thdwjdrl.locus.core.domain.AppState;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.PermissionState;
import java.util.HashMap;
import java.util.Map;

/**
 * 폰 전용 메트릭의 타입 있는 표현 (논점 3: 엔티티는 generic Map, 타입 안정성은 가장자리).
 *
 * <p>{@code metrics} JSON 맵 ↔ 타입 변환의 단일 소유처. 봉투 구조를 그대로 중첩 맵으로 보관(enum은 name 문자열).
 */
public record PhoneMetrics(
        Integer batteryLevel,
        Boolean charging,
        NetworkType networkType,
        Boolean online,
        ActivityType activity,
        AppState appState,
        PermissionState permission,
        Boolean sharingEnabled) {

    /** 타입 → generic 메트릭 맵 (저장용). */
    public Map<String, Object> toMetrics() {
        Map<String, Object> m = new HashMap<>();
        if (batteryLevel != null || charging != null) {
            Map<String, Object> battery = new HashMap<>();
            battery.put("level", batteryLevel);
            battery.put("charging", charging);
            m.put("battery", battery);
        }
        if (networkType != null || online != null) {
            Map<String, Object> network = new HashMap<>();
            network.put("type", networkType != null ? networkType.name() : null);
            network.put("online", online);
            m.put("network", network);
        }
        putName(m, "activity", activity);
        putName(m, "appState", appState);
        putName(m, "permission", permission);
        if (sharingEnabled != null) {
            m.put("sharingEnabled", sharingEnabled);
        }
        return m;
    }

    /** generic 메트릭 맵 → 타입 (읽기용). */
    @SuppressWarnings("unchecked")
    public static PhoneMetrics fromMetrics(Map<String, Object> m) {
        if (m == null) {
            return new PhoneMetrics(null, null, null, null, null, null, null, null);
        }
        Map<String, Object> battery = (Map<String, Object>) m.get("battery");
        Map<String, Object> network = (Map<String, Object>) m.get("network");
        return new PhoneMetrics(
                battery != null ? asInt(battery.get("level")) : null,
                battery != null ? (Boolean) battery.get("charging") : null,
                network != null ? parse(NetworkType.class, network.get("type")) : null,
                network != null ? (Boolean) network.get("online") : null,
                parse(ActivityType.class, m.get("activity")),
                parse(AppState.class, m.get("appState")),
                parse(PermissionState.class, m.get("permission")),
                (Boolean) m.get("sharingEnabled"));
    }

    private static void putName(Map<String, Object> m, String key, Enum<?> value) {
        if (value != null) {
            m.put(key, value.name());
        }
    }

    private static Integer asInt(Object v) {
        return (v instanceof Number n) ? n.intValue() : null;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, Object v) {
        return (v == null) ? null : Enum.valueOf(type, v.toString());
    }
}
