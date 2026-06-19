/**
 *
 *
 * <h2>app.config — 인프라 설정</h2>
 *
 * 인프라를 한 번에 올리지 않고 마일스톤마다 하나씩 추가한다(계획서 §11 인프라 과다 경계).
 *
 * <h3>점증 순서</h3>
 *
 * <ul>
 *   <li>M0: (Actuator/JPA 등은 application.yml 위주) — 별도 설정 클래스 최소
 *   <li>M2: {@code KafkaConfig} (토픽·파티션·컨슈머 그룹)
 *   <li>M4: {@code RedisConfig}, {@code WebSocketConfig}(STOMP) 또는 SSE
 *   <li>M7: {@code ReplicationRoutingDataSource}(읽기 복제 라우팅)
 * </ul>
 */
package com.thdwjdrl.locus.app.config;
