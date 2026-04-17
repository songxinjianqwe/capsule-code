package com.xinjian.capsulecode.config;

import com.xinjian.capsulecode.websocket.PushWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * capsule-code 只保留 Push WebSocket。Claude 交互走 HTTP（ClaudeHttpController）
 * 和 tmux 底层 ProcessManager，不走独立 WebSocket。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final PushWebSocketHandler pushWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(pushWebSocketHandler, "/ws/push")
                .setAllowedOrigins("*");
    }
}
