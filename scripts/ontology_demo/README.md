# Ontology Construction Demo

This demo proves a minimal human-in-the-loop ontology workflow:

```text
papers -> candidate extraction -> review CSV -> approved ontology store
```

## Scope

- No database.
- No LLM call.
- No changes to the existing Java service.
- Rule extraction is used only as a local baseline so the data model and review loop can be tested first.

## Data Model

Entity types:

- `pathogen`
- `compound`
- `disease`
- `assay`
- `target`
- `host`

Relation types:

- `has_activity_against`
- `evaluated_by`
- `causes`

Each approved entity/relation stores evidence:

- `paper_id`
- `paper_title`
- `evidence_text`

## Quick Demo

Run from the repository root:

```powershell
python scripts\ontology_demo\ontology_demo.py demo --output-dir outputs\ontology-demo
```

Generated files:

- `outputs/ontology-demo/review_queue.csv`
- `outputs/ontology-demo/review_decisions.auto.csv`
- `outputs/ontology-demo/ontology_store.reviewed.json`

## Manual Review Flow

Step 1: build the review queue.

```powershell
python scripts\ontology_demo\ontology_demo.py build-review `
  --papers scripts\ontology_demo\sample_papers.jsonl `
  --store scripts\ontology_demo\ontology_store.json `
  --out outputs\ontology-demo\review_queue.csv
```

Step 2: edit `review_queue.csv`.

Set the `decision` column to:

- `approved`
- `rejected`
- blank, meaning keep pending

Step 3: apply approved rows.

```powershell
python scripts\ontology_demo\ontology_demo.py apply-review `
  --store scripts\ontology_demo\ontology_store.json `
  --review outputs\ontology-demo\review_queue.csv `
  --out outputs\ontology-demo\ontology_store.reviewed.json
```

## Engineering Next Steps

1. Replace `ontology_store.json` with PostgreSQL tables: `ontology_entity`, `ontology_alias`, `ontology_relation`, `ontology_evidence`, and `ontology_review_queue`.
2. Connect `build-review` to a daily literature ingestion job.
3. Replace rule extraction with LLM JSON extraction while keeping rules as a baseline.
4. Add a small review UI that shows high-priority candidates first.
5. Export a weekly `ontology_snapshot.json` for literature relevance judging and classifier training.
