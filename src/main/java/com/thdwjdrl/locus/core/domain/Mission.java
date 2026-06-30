package com.thdwjdrl.locus.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 미션.
 *
 * <p><b>페이즈 1에서는 필드만 정의하고 동작은 없다.</b> 구현(배정·상태 전이·판정 연결)은 페이즈 2(M9). 비워 두어야 M5의 판정 엔진을 미션이 그대로 재사용할
 * 수 있다(계획서 §2).
 *
 * <p>{@code type}은 {@code MissionType}(core.strategy) 키. 페이즈 1에서는 전략과 연결하지 않고 문자열로만 둔다. {@code
 * params}는 타입별 설정(좌표·반경·체크포인트 등)을 한 테이블에서 흡수하는 JSON.
 */
@Entity
@Table(name = "mission")
public class Mission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params = new HashMap<>();

    @Column(name = "device_id")
    private String deviceId;

    @Enumerated(EnumType.STRING)
    private MissionStatus status;

    private Instant deadline;

    protected Mission() {}

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public MissionStatus getStatus() {
        return status;
    }

    public Instant getDeadline() {
        return deadline;
    }
}
