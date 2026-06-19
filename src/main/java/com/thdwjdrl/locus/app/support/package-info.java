/**
 *
 *
 * <h2>app.support — 횡단 관심사</h2>
 *
 * 특정 슬라이스에 속하지 않는 공통 코드.
 *
 * <h3>담기는 것</h3>
 *
 * <ul>
 *   <li>전역 예외 처리({@code @RestControllerAdvice}), 표준 에러 응답
 *   <li>커스텀 검증 애너테이션({@code @ValidTimestamp} 등) 구현 측
 *   <li>로그 마스킹 필터 (M6: 평문 위치가 로그에 안 남게)
 * </ul>
 *
 * <p>슬라이스 간 공유가 정말 필요한 것만 둔다. 애매하면 해당 슬라이스 안에 둔다.
 */
package com.thdwjdrl.locus.app.support;
