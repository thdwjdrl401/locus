/**
 *
 *
 * <h2>app.simulator — 폰 시뮬레이터</h2>
 *
 * 실제 단말 없이 가상 디바이스 N대가 주기적으로 텔레메트리를 전송한다.
 *
 * <h3>실행 (결정 0005)</h3>
 *
 * 별도 모듈로 빼지 않고 같은 앱에 두되 {@code simulator} 프로파일로 켠다.
 *
 * <pre>./gradlew bootRun --args='--spring.profiles.active=simulator'</pre>
 *
 * 부하 주력은 k6이고(계획서 §6), 시뮬레이터는 "현실적 움직임 패턴" 담당.
 *
 * <h3>담기는 것 (M0)</h3>
 *
 * <ul>
 *   <li>{@code DeviceSimulator}, {@code SimulatedDevice} — 폰 1대 = 가상 스레드 1개(Java 21)
 *   <li>{@code PhoneProfile} 사용: 보행자 random walk, 신호 끊김·재연결, 배터리 감소
 * </ul>
 */
package com.thdwjdrl.locus.app.simulator;
