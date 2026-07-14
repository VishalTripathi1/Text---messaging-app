package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * DeliveryStatusRepository - Data access for DeliveryStatus entity
 */
@Repository
public interface DeliveryStatusRepository extends JpaRepository<DeliveryStatus, Long> {
    Optional<DeliveryStatus> findByMessageIdAndRecipientId(Long messageId, Long recipientId);
    List<DeliveryStatus> findByRecipientIdAndStatus(Long recipientId, DeliveryStatus.DeliveryStatusType status);
}
