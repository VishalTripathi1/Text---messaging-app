package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * UserProfileRepository - Data access for UserProfile entity
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByUserId(Long userId);

    /**
     * Profiles for several users in one query, as {@code [userId, profile]} rows, so building a list
     * of chats does not run {@link #findByUserId} once per row. See
     * MessageServiceImpl.mapToChatResponses.
     *
     * <p>The owning user's id is selected alongside the entity rather than read back from
     * {@code profile.getUser()}: that association is lazy, and with field-based access Hibernate
     * cannot always answer {@code getId()} from the proxy without loading the row, which would put
     * back the per-row query this exists to remove. {@code p.user.id} compiles to the foreign-key
     * column, with no join at all.
     */
    @Query("SELECT p.user.id, p FROM UserProfile p WHERE p.user.id IN :userIds")
    List<Object[]> findProfileRowsByUserIds(@Param("userIds") Collection<Long> userIds);
}
