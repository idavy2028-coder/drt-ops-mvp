ALTER TABLE refresh_tokens
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;

UPDATE refresh_tokens token
SET token_version = account.token_version
FROM user_accounts account
WHERE account.id = token.user_id;
