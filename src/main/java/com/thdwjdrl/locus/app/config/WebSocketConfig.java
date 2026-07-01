package com.thdwjdrl.locus.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 실시간 push (M4b) — STOMP over WebSocket.
 *
 * <p>관제 지도가 조직 토픽 {@code /topic/org/{orgId}}을 구독해 델타를 받는다. 접속 시 스냅샷은 REST(GET /latest)로, 이후 갱신만
 * push(폴링 제거). 브로커는 인메모리 SimpleBroker(단일 인스턴스용) — 다중 인스턴스·내구성은 Redis Streams(B)로.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // LAN 측정용 — 오리진 제약은 인증(M4)과 함께 좁힌다.
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic"); // 서버 → 클라이언트 브로드캐스트
        registry.setApplicationDestinationPrefixes("/app"); // 클라이언트 → 서버(현재 미사용)
    }
}
