-- Remove unused stage_run abstraction (never wired in Java) and the legacy
-- single-profile backfill batch table. evidence_extraction_run is retained because
-- Q1 compound_evidence rows still reference it as a provenance stub.

DROP TABLE IF EXISTS stage_run_document;
DROP TABLE IF EXISTS stage_run;

DROP TABLE IF EXISTS evidence_extraction_batch;
