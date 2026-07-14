package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * AttachmentRepository - Data access interface for Attachment entity
 */
@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    java.util.List<Attachment> findByMessageId(Long messageId);
}
