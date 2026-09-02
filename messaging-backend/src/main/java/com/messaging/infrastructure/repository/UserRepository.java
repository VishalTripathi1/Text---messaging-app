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
     * Backs contact sync. This used to match on the full normalized digit string exactly, which
     * broke the moment two numbers for the same person had a different country-code prefix -
     * extremely common in practice, since a phone's saved contact ("9876543210") and what
     * someone typed in at signup ("+91 9876543210" -> "919876543210") very often don't agree on
     * whether the country code is included. RIGHT(phone_number, 10) matches on the trailing 10
     * digits instead (the actual subscriber number), the same approach WhatsApp-style contact
     * matching uses, so a country-code prefix mismatch on either side no longer hides a real
     * match. Native query since JPQL has no portable RIGHT()/SUBSTRING-from-the-end equivalent;
     * this is Postgres-specific, which matches the rest of this project's database.
     */
    @Query(value = "SELECT * FROM users WHERE RIGHT(phone_number, 10) IN :phoneSuffixes", nativeQuery = true)
    List<User> findByPhoneNumberSuffixIn(@Param("phoneSuffixes") List<String> phoneSuffixes);
}