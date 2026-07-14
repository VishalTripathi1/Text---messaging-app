package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * DeviceRepository - Data access for Device entity
 */
@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceId(String deviceId);

    List<Device> findByUserId(Long userId);

    List<Device> findByUserIdAndIsActiveTrue(Long userId);
}
