#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IDS_FILE = ROOT / "outputs/q1-q10-classification-500-20260720/Q1-supported-document-ids.txt"
OUT_FILE = ROOT / "outputs/q1-q10-classification-500-20260720/Q1-supported-rag-document-ids.txt"
MANIFEST_FILE = ROOT / "outputs/q1-q10-classification-500-20260720/Q1-id-remap.json"

DOI_REMAP = {
    "57748dd2-c478-48b3-a305-799264df1320": "12936255-87cd-41ff-8b88-12363b7fc342",
    "72b5e04d-9d55-4ef7-9774-f60f3b1ff8aa": "52eb7db0-0652-4f8f-9f5e-1415dba62b4f",
    "72d644ae-2fa1-4595-8ad1-b567f92fe56c": "11ae13fc-b3ef-4a36-87a6-ad9fa2c3c026",
}


def run_psql(sql: str) -> str:
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
            sql,
        ],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return result.stdout.strip()


def resolve_ids(classification_ids: list[str]) -> tuple[list[str], dict[str, str]]:
    remapped: dict[str, str] = {}
    resolved: list[str] = []
    for document_id in classification_ids:
        if document_id in DOI_REMAP:
            target = DOI_REMAP[document_id]
            remapped[document_id] = target
            resolved.append(target)
            continue
        exists = run_psql(
            f"SELECT COUNT(*) FROM rag_document "
            f"WHERE document_id = '{document_id}' "
            f"AND status = 'COMPLETED' AND duplicate_of_document_id IS NULL;"
        )
        if exists != "0":
            resolved.append(document_id)
            continue
        manifest_path = ROOT / "data/rag" / document_id / "artifact-manifest.json"
        if manifest_path.is_file():
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            doi = ((manifest.get("metadata") or {}).get("doiNormalized") or "").replace("'", "''")
            if doi:
                canonical = run_psql(
                    "SELECT document_id::text FROM rag_document "
                    f"WHERE doi_normalized = '{doi}' "
                    "AND status = 'COMPLETED' AND duplicate_of_document_id IS NULL "
                    "LIMIT 1;"
                )
                if canonical:
                    remapped[document_id] = canonical
                    resolved.append(canonical)
                    continue
        raise RuntimeError(f"Unable to resolve classification document id: {document_id}")
    return resolved, remapped


def main() -> None:
    classification_ids = [
        line.strip()
        for line in IDS_FILE.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    resolved, remapped = resolve_ids(classification_ids)
    if len(resolved) != len(classification_ids):
        raise RuntimeError("resolved count mismatch")
    if len(set(resolved)) != len(resolved):
        raise RuntimeError(f"duplicate resolved ids: {len(resolved) - len(set(resolved))}")
    OUT_FILE.write_text("\n".join(resolved) + "\n", encoding="utf-8")
    MANIFEST_FILE.write_text(
        json.dumps(
            {
                "classificationIds": classification_ids,
                "ragDocumentIds": resolved,
                "remapped": remapped,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"classification_ids={len(classification_ids)}")
    print(f"rag_document_ids={len(resolved)}")
    print(f"remapped={len(remapped)}")


if __name__ == "__main__":
    main()
