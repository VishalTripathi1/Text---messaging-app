package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * UserSettingsRepository - Data access for UserSettings entity
 */
@Repository
public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

    Optional<UserSettings> findByUserId(Long userId);

    /**
     * Just the read-receipt opt-out for several users, as {@code [userId, showReadReceipts]} rows.
     *
     * <p>Deciding whether to report a message as read means consulting the reader's setting; done with
     * {@link #findByUserId} that was one query per recipient, which on the chat list meant one per
     * conversation. Users with no settings row are absent, and callers must default them to enabled.
     * See MessageServiceImpl.mapToMessageResponses.
     */
    @Query("SELECT s.user.id, s.showReadReceipts FROM UserSettings s WHERE s.user.id IN :userIds")
    List<Object[]> findShowReadReceiptsRowsByUserIds(@Param("userIds") Collection<Long> userIds);
}
