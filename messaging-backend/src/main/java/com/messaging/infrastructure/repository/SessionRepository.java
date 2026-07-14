package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SessionRepository - Data access for Session entity
 */
@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    Optional<Session> findByToken(String token);

    Optional<Session> findByRefreshToken(String refreshToken);

    List<Session> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
