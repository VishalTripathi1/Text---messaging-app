package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.ChatUserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ChatUserPreferenceRepository - Data access for per-user chat preferences
 */
@Repository
public interface ChatUserPreferenceRepository extends JpaRepository<ChatUserPreference, Long> {

    Optional<ChatUserPreference> findByChatIdAndUserId(Long chatId, Long userId);

    @Modifying
    @Query("DELETE FROM ChatUserPreference p WHERE p.chat.id = :chatId")
    void deleteByChatId(@Param("chatId") Long chatId);

    /**
     * This user's pin/archive/clear/delete flags across several chats, as {@code [chatId, isPinned, isArchived, isCleared, isDeleted]}
     * rows, so the chat list does not run {@link #findByChatIdAndUserId} once per chat. Chats with no
     * preference row - the common case - are simply absent, so callers must treat a missing chat as
     * neither pinned, nor archived, nor cleared, nor deleted. See MessageServiceImpl.mapToChatResponses.
     */
    @Query("SELECT p.chat.id, p.isPinned, p.isArchived, p.isCleared, p.isDeleted FROM ChatUserPreference p " +
           "WHERE p.user.id = :userId AND p.chat.id IN :chatIds")
    List<Object[]> findPreferenceRowsByUserIdAndChatIds(@Param("userId") Long userId,
                                                       @Param("chatIds") Collection<Long> chatIds);
}
