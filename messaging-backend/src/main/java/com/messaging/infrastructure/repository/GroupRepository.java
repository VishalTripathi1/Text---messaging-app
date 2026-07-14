package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Group;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * GroupRepository - Data access for Group entity
 */
@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {

    @Query("SELECT g FROM Group g JOIN g.members gm WHERE gm.user.id = :userId")
    Page<Group> findUserGroups(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT COUNT(gm) FROM GroupMember gm WHERE gm.group.id = :groupId")
    long countMembers(@Param("groupId") Long groupId);

    @Query("SELECT gm.user.id FROM GroupMember gm WHERE gm.group.id = :groupId")
    java.util.List<Long> findMemberUserIds(@Param("groupId") Long groupId);
}
