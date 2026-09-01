package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * AttachmentRepository - Data access interface for Attachment entity
 */
@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    java.util.List<Attachment> findByMessageId(Long messageId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM Attachment a WHERE a.message.id IN :messageIds")
    void deleteByMessageIds(@Param("messageIds") Collection<Long> messageIds);

    /**
     * Every attachment belonging to any of {@code messageIds}, as
     * {@code [messageId, filePath, fileName, fileType, fileSize]} rows ordered so the oldest
     * attachment of each message comes first.
     *
     * <p>Exists so a page of messages costs one query instead of one per message - see
     * MessageServiceImpl.mapToMessageResponses. A projection rather than the entity because the
     * caller only wants those five columns, and hydrating Attachment entities would pull a lazy
     * Message proxy along with each one.
     */
    @Query("SELECT a.message.id, a.filePath, a.fileName, a.fileType, a.fileSize FROM Attachment a " +
           "WHERE a.message.id IN :messageIds ORDER BY a.id ASC")
    List<Object[]> findAttachmentRowsByMessageIds(@Param("messageIds") Collection<Long> messageIds);
}
