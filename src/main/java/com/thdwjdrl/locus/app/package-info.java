/**
 *
 *
 * <h2>app — 배선 계층 (기능별 슬라이스)</h2>
 *
 * {@code core}의 도메인·전략·엔진을 바깥세상(HTTP·DB·Kafka·Redis·WebSocket)과 잇는다.
 *
 * <h3>구조 (결정 0003)</h3>
 *
 * 레이어별(controller/service/repo)이 아니라 <b>도메인 기능별 슬라이스</b>로 나눈다. 마일스톤 ≈ 슬라이스 폴더가 1:1로 맞는다.
 *
 * <pre>
 *   telemetry/  수집·조회 (M0~)        device/   디바이스 조회 + PhoneHandler/Profile (M0~)
 *   simulator/  폰 시뮬레이터 (M0~)     config/   인프라 설정 (M2~ 점증)
 *   support/    공통 예외·응답·검증·로그마스킹
 *   geofence/   (M5에서 생성)          mission/  (M9에서 생성)
 * </pre>
 *
 * <h3>포트는 이음새에만 (결정 0004)</h3>
 *
 * 헥사고날을 전면 채택하지 않는다. "구현을 갈아끼운다"고 계획서가 명시한 이음새(M2 수집, M4 캐시, M5 상태저장)에만 출력 포트 인터페이스를 둔다. 나머지는 슬라이스
 * 안의 평범한 직접 호출.
 */
package com.thdwjdrl.locus.app;
