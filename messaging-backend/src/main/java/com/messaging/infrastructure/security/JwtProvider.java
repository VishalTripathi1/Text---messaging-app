package com.messaging.infrastructure.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JwtProvider - Handles JWT token generation and validation
 */
@Slf4j
@Component
public class JwtProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    @Value("${app.jwt.refresh-expiration}")
    private long refreshTokenExpiration;

    /**
     * Reports the token lifetimes actually in force, once, at startup.
     *
     * application.properties defaults these to 24 hours and 90 days, but each is overridable by an
     * environment variable (JWT_EXPIRATION / JWT_REFRESH_EXPIRATION) - and a deployment that quietly
     * sets the access token to an hour looks exactly like a bug from the outside: everything works,
     * then every request starts failing at a time nobody can account for. Printing the effective
     * values means the deploy log answers that question directly.
     *
     * The refresh lifetime is the one that decides whether a session can renew itself silently or
     * the user has to log in again, so the two must never be equal: when they were (both 30 days,
     * issued together at login) the refresh token expired at the same instant as the access token it
     * existed to replace, and every idle user was forced to log in again. If this line ever shows
     * the two values close together, that is the bug, wherever it was set.
     */
    @jakarta.annotation.PostConstruct
    void logTokenLifetimes() {
        log.info("JWT lifetimes in effect - access: {} min, refresh: {} min ({} days)",
                jwtExpiration / 60_000,
                refreshTokenExpiration / 60_000,
                refreshTokenExpiration / 86_400_000);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
                jwtSecret.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Generate JWT access token.
     */
    public String generateAccessToken(
            Long userId,
            String username
    ) {
        return buildToken(
                userId,
                username,
                jwtExpiration,
                "ACCESS"
        );
    }

    /**
     * Generate refresh token.
     */
    public String generateRefreshToken(
            Long userId,
            String username
    ) {
        return buildToken(
                userId,
                username,
                refreshTokenExpiration,
                "REFRESH"
        );
    }

    /**
     * Build JWT token.
     */
    private String buildToken(
            Long userId,
            String username,
            long expirationTime,
            String tokenType
    ) {

        Date now = new Date();

        Date expiryDate =
                new Date(
                        now.getTime() + expirationTime
                );

        return Jwts.builder()
                .setSubject(userId.toString())
                .claim("username", username)
                .claim("type", tokenType)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(
                        getSigningKey(),
                        SignatureAlgorithm.HS256
                )
                .compact();
    }

    /**
     * Extract user ID from token.
     */
    public Long getUserIdFromToken(
            String token
    ) {

        try {

            Claims claims =
                    Jwts.parserBuilder()
                            .setSigningKey(getSigningKey())
                            .build()
                            .parseClaimsJws(token)
                            .getBody();

            return Long.parseLong(
                    claims.getSubject()
            );

        } catch (
                JwtException |
                IllegalArgumentException e
        ) {

            log.error(
                    "Error extracting user ID from token: {}",
                    e.getMessage()
            );

            return null;
        }
    }

    /**
     * Extract username from token.
     */
    public String getUsernameFromToken(
            String token
    ) {

        try {

            Claims claims =
                    Jwts.parserBuilder()
                            .setSigningKey(getSigningKey())
                            .build()
                            .parseClaimsJws(token)
                            .getBody();

            return claims.get(
                    "username",
                    String.class
            );

        } catch (
                JwtException |
                IllegalArgumentException e
        ) {

            log.error(
                    "Error extracting username from token: {}",
                    e.getMessage()
            );

            return null;
        }
    }

    /**
     * Validate JWT token.
     */
    public boolean validateToken(
            String token
    ) {

        try {

            Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token);

            return true;

        } catch (ExpiredJwtException e) {

            log.error(
                    "JWT token is expired: {}",
                    e.getMessage()
            );

        } catch (UnsupportedJwtException e) {

            log.error(
                    "Unsupported JWT token: {}",
                    e.getMessage()
            );

        } catch (MalformedJwtException e) {

            log.error(
                    "Invalid JWT token: {}",
                    e.getMessage()
            );

        } catch (SignatureException e) {

            log.error(
                    "Invalid JWT signature: {}",
                    e.getMessage()
            );

        } catch (IllegalArgumentException e) {

            log.error(
                    "JWT claims string is empty: {}",
                    e.getMessage()
            );
        }

        return false;
    }

    /**
     * Get access-token expiration time in milliseconds.
     */
    public Long getTokenExpirationTime() {
        return jwtExpiration;
    }

    /**
     * Get refresh-token expiration time in milliseconds.
     */
    public Long getRefreshTokenExpirationTime() {
        return refreshTokenExpiration;
    }
}