package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * GroupMemberRepository - Data access interface for GroupMember entity
 */
@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    void deleteByGroupIdAndUserId(Long groupId, Long userId);

    List<GroupMember> findByGroupId(Long groupId);

    /**
     * The group's member ids only. {@link #findByGroupId} returns entities whose {@code user} is a
     * lazy association, so reading the ids back off them costs one query per member; the id lives on
     * the membership row itself.
     */
    @Query("SELECT gm.user.id FROM GroupMember gm WHERE gm.group.id = :groupId")
    List<Long> findMemberIdsByGroupId(@Param("groupId") Long groupId);
}
