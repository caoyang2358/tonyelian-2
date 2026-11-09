-- Existing references receive a deterministic ID tie-break; subsequent writes preserve user order.
ALTER TABLE p2_knowledge_attachment ADD COLUMN display_order INT NOT NULL DEFAULT 0;
CREATE INDEX ix_p2_knowledge_attachment_order ON p2_knowledge_attachment(article_id, display_order, attachment_id);
