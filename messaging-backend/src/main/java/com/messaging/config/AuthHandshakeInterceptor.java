package com.messaging.config;

import com.messaging.infrastructure.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    // @Override
    // public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
    //                                WebSocketHandler wsHandler, Map<String, Object> attributes) {
    //     if (request instanceof ServletServerHttpRequest) {
    //         ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
    //         String token = servletRequest.getServletRequest().getParameter("token");

    //         if (!StringUtils.hasText(token)) {
    //             String authHeader = servletRequest.getHeaders().getFirst("Authorization");
    //             if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
    //                 token = authHeader.substring(7);
    //             }
    //         }

    //         if (StringUtils.hasText(token) && jwtProvider.validateToken(token)) {
    //             Long userId = jwtProvider.getUserIdFromToken(token);
    //             String username = jwtProvider.getUsernameFromToken(token);
    //             if (userId != null) {
    //                 attributes.put("userId", userId);
    //                 attributes.put("username", username);
    //                 log.info("WebSocket handshake successful for userId: {}", userId);
    //                 return true;
    //             }
    //         }
    //     }
    //     log.warn("WebSocket handshake failed: invalid or missing token");
    //     return false;
    // }

    
    @Override
public boolean beforeHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Map<String, Object> attributes) {

    log.info("===== NEW WEBSOCKET BUILD =====");
    if (request instanceof ServletServerHttpRequest) {
        ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;

        String token = servletRequest.getServletRequest().getParameter("token");

        if (!StringUtils.hasText(token)) {
            String authHeader = servletRequest.getHeaders().getFirst("Authorization");
            if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
            }
        }

        log.info("Received token: {}", token);

        boolean valid = StringUtils.hasText(token) && jwtProvider.validateToken(token);
        log.info("Token valid: {}", valid);

        if (valid) {
            Long userId = jwtProvider.getUserIdFromToken(token);
            String username = jwtProvider.getUsernameFromToken(token);

            log.info("Extracted userId: {}", userId);
            log.info("Extracted username: {}", username);

            if (userId != null) {
                attributes.put("userId", userId);
                attributes.put("username", username);

                log.info("WebSocket handshake successful for userId: {}", userId);
                return true;
            }

            log.warn("Token is valid but userId is null");
        }
    }

    log.warn("WebSocket handshake failed");
    return false;
}

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }
}
