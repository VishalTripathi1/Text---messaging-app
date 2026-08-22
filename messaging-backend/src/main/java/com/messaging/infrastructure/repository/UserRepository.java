package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * UserRepository - Data access for User entity
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByPhoneNumber(String phoneNumber);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Real search query, replacing a findAll() + in-memory filter that pulled every user row
     * in the database into memory on every keystroke of a search box.
     */
    @Query("SELECT u FROM User u WHERE " +
           "LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "u.phoneNumber LIKE CONCAT('%', :query, '%')")
    List<User> searchByQuery(@Param("query") String query);

    /**
     * Backs contact sync: matches by exact normalized phone number instead of loading every
     * user row to filter in Java. Replaces the old findAll()-based fuzzy last-10-digit
     * comparison; callers normalize numbers to digits-only before calling this.
     */
    List<User> findByPhoneNumberIn(List<String> phoneNumbers);
}
