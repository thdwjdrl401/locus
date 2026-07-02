package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.util.Map;

/**
 * 텔레메트리 조회 응답. 도메인 {@link Telemetry}를 DTO로 노출(컨벤션 §6: 전송은 record).
 *
 * <p>{@code location}은 nullable — 권한 거부·공유 off 시 미수집(최소 수집). 클라이언트(관제 지도)는 null이면 마커를 안 찍는다.
 */
public record TelemetryResponse(
        String deviceId,
        DeviceType deviceType,
        Instant recordedAt,
        Instant receivedAt,
        LocationDto location,
        Map<String, Object> metrics) {

    /** 위치 VO의 전송 형태. */
    public record LocationDto(
            Double lat,
            Double lng,
            Double accuracy,
            Double altitude,
            Double speed,
            Double heading) {

        static LocationDto from(Location l) {
            if (l == null) {
                return null;
            }
            return new LocationDto(
                    l.getLatitude(),
                    l.getLongitude(),
                    l.getAccuracy(),
                    l.getAltitude(),
                    l.getSpeed(),
                    l.getHeading());
        }
    }

    public static TelemetryResponse from(Telemetry t) {
        return new TelemetryResponse(
                t.getDeviceId(),
                t.getDeviceType(),
                t.getRecordedAt(),
                t.getReceivedAt(),
                LocationDto.from(t.getLocation()),
                t.getMetrics());
    }

    /** 도메인 {@link Telemetry}로 복원. Stream 페이로드(JSON)를 storage 컨슈머가 적재할 때 쓴다({@code from}의 역). */
    public Telemetry toTelemetry() {
        Location loc =
                location == null
                        ? null
                        : new Location(
                                location.lat(),
                                location.lng(),
                                location.accuracy(),
                                location.altitude(),
                                location.speed(),
                                location.heading());
        return new Telemetry(deviceId, deviceType, recordedAt, receivedAt, loc, metrics);
    }
}
