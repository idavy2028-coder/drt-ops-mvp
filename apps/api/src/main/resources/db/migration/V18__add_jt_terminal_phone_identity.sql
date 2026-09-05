ALTER TABLE jt_terminals
  ADD COLUMN terminal_phone_identity VARCHAR(30);

UPDATE jt_terminals
SET terminal_phone_identity = CASE
  WHEN terminal_phone ~ '^[0-9]+$'
    AND UPPER(TRIM(protocol_version)) IN ('JT808_2013', 'JT/T 808-2013', 'JT/T808-2013')
    AND CHAR_LENGTH(terminal_phone) <= 12
    THEN LPAD(terminal_phone, 20, '0')
  WHEN terminal_phone ~ '^[0-9]+$'
    AND UPPER(TRIM(protocol_version)) IN ('JT808_2019', 'JT/T 808-2019', 'JT/T808-2019')
    AND CHAR_LENGTH(terminal_phone) <= 20
    THEN LPAD(terminal_phone, 20, '0')
  ELSE terminal_phone
END;

ALTER TABLE jt_terminals
  ALTER COLUMN terminal_phone_identity SET NOT NULL;

ALTER TABLE jt_terminals
  ADD CONSTRAINT uq_jt_terminals_terminal_phone_identity
  UNIQUE (terminal_phone_identity);
