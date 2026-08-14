#!/usr/bin/env python3
"""Create Q1-supported test cohort from 500-doc Q1-Q10 classification output."""

from __future__ import annotations

import hashlib
import json
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESULTS_PATH = ROOT / "outputs/q1-q10-classification-500-20260720/document-results.jsonl"
OUT_DIR = RESULTS_PATH.parent
COHORT_NAME = "Q1-supported-500-20260720"
SOURCE_REF_ID = "08777e18-6028-44da-ab30-eb22cc21ea10"  # BALANCED_500 rag eval experiment


def load_unique_rows() -> list[dict]:
    by_id: dict[str, dict] = {}
    for line in RESULTS_PATH.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        by_id[row["documentId"]] = row
    rows = list(by_id.values())
    if len(rows) != 500:
        raise RuntimeError(f"expected 500 unique documents, got {len(rows)}")
    return rows


def q1_status_counts(rows: list[dict]) -> tuple[list[str], list[str], dict[str, int]]:
    supported: list[str] = []
    uncertain: list[str] = []
    counts = {"SUPPORTED": 0, "UNCERTAIN": 0, "NOT_SUPPORTED": 0, "FAILED": 0}
    for row in rows:
        for question in row["questions"]:
            if question["questionId"] != "Q1":
                continue
            status = question["status"]
            counts[status] = counts.get(status, 0) + 1
            if status == "SUPPORTED":
                supported.append(row["documentId"])
            elif status == "UNCERTAIN":
                uncertain.append(row["documentId"])
    return sorted(supported), sorted(uncertain), counts


def input_hash(document_ids: list[str]) -> str:
    payload = "\n".join(document_ids)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def write_outputs(
    supported: list[str],
    uncertain: list[str],
    counts: dict[str, int],
    db_supported: list[str],
    missing_ids: list[str],
) -> uuid.UUID:
    (OUT_DIR / "Q1-supported-document-ids.txt").write_text(
        "\n".join(supported) + "\n", encoding="utf-8"
    )
    (OUT_DIR / "Q1-uncertain-document-ids.txt").write_text(
        "\n".join(uncertain) + "\n", encoding="utf-8"
    )

    cohort_id = uuid.uuid5(uuid.NAMESPACE_URL, f"demo_01:{COHORT_NAME}")
    manifest = {
        "cohortId": str(cohort_id),
        "name": COHORT_NAME,
        "sourceType": "CLASSIFICATION",
        "sourceRefId": SOURCE_REF_ID,
        "classificationOutputDir": str(OUT_DIR.relative_to(ROOT)).replace("\\", "/"),
        "classificationSummary": {
            "totalDocuments": 500,
            "Q1": {
                "SUPPORTED": counts["SUPPORTED"],
                "UNCERTAIN": counts["UNCERTAIN"],
                "NOT_SUPPORTED": counts["NOT_SUPPORTED"],
            },
        },
        "classificationSupportedCount": len(supported),
        "databaseAvailableCount": len(db_supported),
        "missingDocumentIds": missing_ids,
        "documentCount": len(db_supported),
        "documentIds": db_supported,
        "inputHash": input_hash(db_supported),
    }
    (OUT_DIR / "Q1-test-cohort.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return cohort_id


def existing_document_ids(document_ids: list[str]) -> list[str]:
    if not document_ids:
        return []
    values = ",".join(f"'{document_id}'" for document_id in document_ids)
    result = subprocess.run(
        [
            "docker",
            "exec",
            "ai-code-postgres",
            "psql",
            "-U",
            "demo_01",
            "-d",
            "demo_01",
            "-t",
            "-A",
            "-c",
            f"SELECT document_id::text FROM rag_document WHERE document_id IN ({values}) ORDER BY document_id",
        ],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    existing = {line.strip() for line in result.stdout.splitlines() if line.strip()}
    return [document_id for document_id in document_ids if document_id in existing]


def apply_to_database(cohort_id: uuid.UUID, supported: list[str], doc_hash: str) -> None:
    sql_path = OUT_DIR / "create-q1-test-cohort.sql"
    lines = [
        f"DELETE FROM cohort_member WHERE cohort_id = '{cohort_id}';",
        f"DELETE FROM document_cohort WHERE name = '{COHORT_NAME}';",
        (
            "INSERT INTO document_cohort "
            "(cohort_id, name, source_type, source_ref_id, input_hash, frozen) "
            f"VALUES ('{cohort_id}', '{COHORT_NAME}', 'CLASSIFICATION', "
            f"'{SOURCE_REF_ID}', '{doc_hash}', TRUE);"
        ),
    ]
    for ordinal, document_id in enumerate(supported, start=1):
        lines.append(
            "INSERT INTO cohort_member "
            "(cohort_id, document_id, ordinal, added_reason) "
            f"VALUES ('{cohort_id}', '{document_id}', {ordinal}, "
            "'Q1 supported by 500-doc classification');"
        )
    sql_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            "ai-code-postgres",
            "psql",
            "-U",
            "demo_01",
            "-d",
            "demo_01",
            "-v",
            "ON_ERROR_STOP=1",
            "-f",
            "-",
        ],
        input=sql_path.read_text(encoding="utf-8"),
        check=True,
        text=True,
        encoding="utf-8",
    )


def main() -> None:
    rows = load_unique_rows()
    supported, uncertain, counts = q1_status_counts(rows)
    db_supported = existing_document_ids(supported)
    missing_ids = sorted(set(supported) - set(db_supported))
    cohort_id = write_outputs(supported, uncertain, counts, db_supported, missing_ids)
    doc_hash = input_hash(db_supported)
    apply_to_database(cohort_id, db_supported, doc_hash)

    print("500-doc Q1 classification counts:")
    print(f"  SUPPORTED:     {counts['SUPPORTED']}")
    print(f"  UNCERTAIN:     {counts['UNCERTAIN']}")
    print(f"  NOT_SUPPORTED: {counts['NOT_SUPPORTED']}")
    print(f"cohort name: {COHORT_NAME}")
    print(f"cohort id:   {cohort_id}")
    print(f"cohort members (in rag_document): {len(db_supported)}")
    if missing_ids:
        print(f"missing from rag_document: {len(missing_ids)}")


if __name__ == "__main__":
    main()
