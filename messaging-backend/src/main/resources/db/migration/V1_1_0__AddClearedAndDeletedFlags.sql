-- Add is_cleared column if it doesn't exist
DO $$ 
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'chat_user_preferences' AND column_name = 'is_cleared') THEN
        ALTER TABLE chat_user_preferences 
            ADD COLUMN is_cleared BOOLEAN NOT NULL DEFAULT FALSE;
    ELSE
        -- Column exists, make sure it's NOT NULL and has a default
        ALTER TABLE chat_user_preferences 
            ALTER COLUMN is_cleared SET NOT NULL,
            ALTER COLUMN is_cleared SET DEFAULT FALSE;
        
        -- Update any existing NULL values to FALSE
        UPDATE chat_user_preferences 
        SET is_cleared = FALSE 
        WHERE is_cleared IS NULL;
    END IF;
END $$;

-- Add is_deleted column if it doesn't exist
DO $$ 
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'chat_user_preferences' AND column_name = 'is_deleted') THEN
        ALTER TABLE chat_user_preferences 
            ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
    ELSE
        -- Column exists, make sure it's NOT NULL and has a default
        ALTER TABLE chat_user_preferences 
            ALTER COLUMN is_deleted SET NOT NULL,
            ALTER COLUMN is_deleted SET DEFAULT FALSE;
        
        -- Update any existing NULL values to FALSE
        UPDATE chat_user_preferences 
        SET is_deleted = FALSE 
        WHERE is_deleted IS NULL;
    END IF;
END $$;
