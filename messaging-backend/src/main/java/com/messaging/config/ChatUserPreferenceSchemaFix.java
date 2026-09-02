package com.messaging.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * This project has no schema migration tool (no Flyway/Liquibase dependency on the classpath) -
 * the schema is managed entirely by Hibernate's spring.jpa.hibernate.ddl-auto=update, which only
 * ever adds tables/columns and never drops or alters anything. A SQL file placed under
 * db/migration is therefore NOT automatically executed by anything in this project (that was a
 * mistaken assumption in an earlier fix and never actually ran).
 *
 * chat_user_preferences.chat_id used to be a real @ManyToOne to Chat, which made Hibernate create
 * a foreign key to chats(id). It was refactored to a plain Long so the same column could also
 * hold a group's id (stored negative, so it can't collide with a real chat's id), but ddl-auto
 * never drops the old constraint, so it silently survives on any database it was ever created on
 * and rejects every group clear/delete with a "violates foreign key constraint" error.
 *
 * This runs once on every startup, is entirely idempotent (every statement is safe to run
 * against a database that's already fixed), and requires no separate migration step or manual
 * psql access - it fixes itself the same way ddl-auto already keeps the rest of the schema
 * in sync.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatUserPreferenceSchemaFix implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            dropStaleForeignKey();
            convertLegacyPositiveGroupIds();
            backfillClearedAndDeletedTimestamps();
        } catch (Exception e) {
            // Never take the whole app down over a self-heal step; worst case, the next
            // clear/delete on a group hits the same FK error as before and we try again on the
            // next restart.
            log.error("ChatUserPreferenceSchemaFix failed - chat_user_preferences may still have " +
                    "the stale foreign key. The app will keep starting, but group clear/delete " +
                    "may keep failing until this is resolved.", e);
        }
    }

    private void dropStaleForeignKey() {
        List<String> fkNames = jdbcTemplate.queryForList(
                "SELECT tc.constraint_name FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu " +
                "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                "WHERE tc.table_name = 'chat_user_preferences' " +
                "  AND tc.constraint_type = 'FOREIGN KEY' " +
                "  AND kcu.column_name = 'chat_id'",
                String.class);

        for (String fkName : fkNames) {
            try {
                log.warn("Dropping stale foreign key '{}' on chat_user_preferences.chat_id - this " +
                        "column now also stores group ids (as negative numbers) and can no longer " +
                        "be a real FK to chats(id).", fkName);
                jdbcTemplate.execute("ALTER TABLE chat_user_preferences DROP CONSTRAINT \"" + fkName + "\"");
            } catch (DataAccessException e) {
                // Another instance may have already dropped it between our check and this call.
                log.warn("Could not drop constraint '{}' (possibly already removed): {}", fkName, e.getMessage());
            }
        }
    }

    private void convertLegacyPositiveGroupIds() {
        // Any group preference rows written before this fix existed used the group's raw
        // (positive) id, which can collide with a real chat's id. Only rows that can't possibly
        // be a real chat (no matching row in chats) and do match a real group are converted.
        int converted = jdbcTemplate.update(
                "UPDATE chat_user_preferences p SET chat_id = -p.chat_id " +
                "WHERE p.chat_id > 0 " +
                "  AND NOT EXISTS (SELECT 1 FROM chats c WHERE c.id = p.chat_id) " +
                "  AND EXISTS (SELECT 1 FROM groups g WHERE g.id = p.chat_id)");
        if (converted > 0) {
            log.warn("Converted {} chat_user_preferences row(s) from a positive to a negative (group) chat_id.", converted);
        }
    }

    private void backfillClearedAndDeletedTimestamps() {
        jdbcTemplate.update("UPDATE chat_user_preferences SET cleared_at = updated_at " +
                "WHERE is_cleared = true AND cleared_at IS NULL");
        jdbcTemplate.update("UPDATE chat_user_preferences SET deleted_at = updated_at " +
                "WHERE is_deleted = true AND deleted_at IS NULL");
    }
}
