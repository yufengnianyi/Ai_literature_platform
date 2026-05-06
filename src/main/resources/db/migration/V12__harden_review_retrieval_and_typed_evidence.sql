ALTER TABLE rag_document
    ADD COLUMN IF NOT EXISTS synopsis_json JSONB,
    ADD COLUMN IF NOT EXISTS synopsis_text TEXT;

UPDATE rag_document
SET fts_vector = to_tsvector(
        'english',
        coalesce(title, '') || ' ' ||
        coalesce(journal, '') || ' ' ||
        coalesce(abstract_text, '') || ' ' ||
        coalesce(synopsis_text, '')
    )
WHERE status = 'COMPLETED';

CREATE OR REPLACE FUNCTION rag_document_fts_trigger() RETURNS trigger AS $$
BEGIN
    NEW.fts_vector := to_tsvector(
        'english',
        coalesce(NEW.title, '') || ' ' ||
        coalesce(NEW.journal, '') || ' ' ||
        coalesce(NEW.abstract_text, '') || ' ' ||
        coalesce(NEW.synopsis_text, '')
    );
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_rag_document_fts ON rag_document;
CREATE TRIGGER trg_rag_document_fts
    BEFORE INSERT OR UPDATE OF title, journal, abstract_text, synopsis_text ON rag_document
    FOR EACH ROW EXECUTE FUNCTION rag_document_fts_trigger();

ALTER TABLE review_evidence
    ADD COLUMN IF NOT EXISTS typed_entities JSONB,
    ADD COLUMN IF NOT EXISTS document_title TEXT;
