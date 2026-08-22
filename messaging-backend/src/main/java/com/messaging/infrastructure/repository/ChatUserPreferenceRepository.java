package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.ChatUserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * ChatUserPreferenceRepository - Data access for per-user chat preferences
 */
@Repository
public interface ChatUserPreferenceRepository extends JpaRepository<ChatUserPreference, Long> {

    Optional<ChatUserPreference> findByChatIdAndUserId(Long chatId, Long userId);
}
