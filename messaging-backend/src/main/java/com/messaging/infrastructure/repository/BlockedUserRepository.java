package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.BlockedUser;
import com.messaging.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlockedUserRepository extends JpaRepository<BlockedUser, Long> {

    Optional<BlockedUser> findByBlockerAndBlocked(User blocker, User blocked);

    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    @Query("SELECT bu.blocked.id FROM BlockedUser bu WHERE bu.blocker.id = :blockerId")
    List<Long> findBlockedUserIdsByBlockerId(@Param("blockerId") Long blockerId);

    void deleteByBlockerIdAndBlockedId(Long blockerId, Long blockedId);
}
