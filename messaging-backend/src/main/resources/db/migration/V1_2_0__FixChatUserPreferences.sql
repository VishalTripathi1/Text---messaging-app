-- chat_id used to be a real @ManyToOne to Chat, which made Hibernate create a foreign key
-- to chats(id). It was refactored to a plain Long so the same column could also hold a
-- group's id (as a negative number, to keep the two id spaces from colliding), but
-- ddl-auto=update never drops constraints, so the old FK silently survived and now rejects
-- every insert/update for a group preference row (fkd0nol89iy88ndnx7jgql3rkve).
DO $$
DECLARE
    fk_name text;
BEGIN
    SELECT tc.constraint_name INTO fk_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name
     AND tc.table_schema = kcu.table_schema
    WHERE tc.table_name = 'chat_user_preferences'
      AND tc.constraint_type = 'FOREIGN KEY'
      AND kcu.column_name = 'chat_id';

    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE chat_user_preferences DROP CONSTRAINT %I', fk_name);
    END IF;
END $$;

-- Any existing group preference rows were written with the group's raw (positive) id before
-- this fix, so they may already collide with a real chat's id. Convert them to the negative
-- convention. Only rows whose "chat_id" doesn't correspond to a real chat can safely be
-- assumed to be group rows written by the old buggy code path.
UPDATE chat_user_preferences p
SET chat_id = -p.chat_id
WHERE p.chat_id > 0
  AND NOT EXISTS (SELECT 1 FROM chats c WHERE c.id = p.chat_id)
  AND EXISTS (SELECT 1 FROM groups g WHERE g.id = p.chat_id);

-- clearedAt/deletedAt: "clear chat" and "delete chat" only ever set a boolean, so nothing
-- filtered old messages back out of the message list - reopening the chat (or a fresh insert
-- via getOrCreateChat) showed the same full history again. A timestamp lets us hide only
-- messages sent before the clear/delete happened, matching WhatsApp/Telegram behavior.
ALTER TABLE chat_user_preferences ADD COLUMN IF NOT EXISTS cleared_at TIMESTAMP;
ALTER TABLE chat_user_preferences ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

UPDATE chat_user_preferences SET cleared_at = updated_at WHERE is_cleared = true AND cleared_at IS NULL;
UPDATE chat_user_preferences SET deleted_at = updated_at WHERE is_deleted = true AND deleted_at IS NULL;
