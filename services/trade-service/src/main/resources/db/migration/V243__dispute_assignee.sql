-- V243: dispute ticket assignee (handler display name)

ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS assignee VARCHAR(64);
COMMENT ON COLUMN dispute_ticket.assignee IS U&'\5904\7406\4EBA\5C55\793A\540D\FF08\7ED3\6848\5199\5165\FF09';

CREATE INDEX IF NOT EXISTS idx_dispute_ticket_assignee ON dispute_ticket (assignee);
