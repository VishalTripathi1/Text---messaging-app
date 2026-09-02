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

    // Preferences for a group are stored on the same chat_user_preferences table as chats,
    // keyed by -groupId (see MessageServiceImpl.clearChat/deleteChat) so a group's id can never
    // collide with a real chat's id in that shared chat_id column.
    @Query("SELECT g FROM Group g JOIN g.members gm LEFT JOIN ChatUserPreference p " +
           "ON p.chatId = -g.id AND p.user.id = :userId " +
           "WHERE gm.user.id = :userId AND " +
           "COALESCE(p.isCleared, false) = false AND COALESCE(p.deleted, false) = false")
    Page<Group> findUserGroupsExcludingHidden(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT g FROM Group g JOIN g.members gm JOIN ChatUserPreference p " +
           "ON p.chatId = -g.id AND p.user.id = :userId " +
           "WHERE gm.user.id = :userId AND p.isCleared = true")
    Page<Group> findClearedUserGroups(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT g FROM Group g JOIN g.members gm JOIN ChatUserPreference p " +
           "ON p.chatId = -g.id AND p.user.id = :userId " +
           "WHERE gm.user.id = :userId AND p.deleted = true")
    Page<Group> findDeletedUserGroups(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT COUNT(gm) FROM GroupMember gm WHERE gm.group.id = :groupId")
    long countMembers(@Param("groupId") Long groupId);

    @Query("SELECT gm.user.id FROM GroupMember gm WHERE gm.group.id = :groupId")
    java.util.List<Long> findMemberUserIds(@Param("groupId") Long groupId);
}
