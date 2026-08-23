package com.messaging.config;

import com.messaging.infrastructure.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * AuthHandshakeInterceptor - Authenticates WebSocket handshakes using JWTs
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtProvider jwtProvider;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        if (request instanceof ServletServerHttpRequest servletRequest) {

            String token = servletRequest.getServletRequest().getParameter("token");

            if (!StringUtils.hasText(token)) {
                String authHeader = servletRequest.getHeaders().getFirst("Authorization");
                if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
                    token = authHeader.substring(7);
                }
            }

            if (StringUtils.hasText(token) && jwtProvider.validateToken(token)) {

                Long userId = jwtProvider.getUserIdFromToken(token);

                if (userId != null) {
                    attributes.put("userId", userId);
                    attributes.put("username", jwtProvider.getUsernameFromToken(token));

                    log.info("WebSocket connected for user {}", userId);
                    return true;
                }
            }
        }

        // Returning false aborts the handshake, but Spring sends whatever status is on the
        // response - and with none set that is a plain 200, which reads to the client as "the
        // server answered but didn't upgrade". The Android side only refreshes its token when the
        // handshake fails with 401 (see WebSocketClient.onFailure), so an expired token produced
        // an endless reconnect loop that retried the same dead token every few seconds forever.
        // Saying 401 out loud lets the client recognise it and re-authenticate.
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        log.warn("WebSocket handshake rejected: missing, expired or invalid token");
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // No-op
    }
}