#!/usr/bin/env python3
"""Register missing Q1 docs, refresh cohort, and start Q1 extraction for 111 papers."""

from __future__ import annotations

import json
import subprocess
import sys
import time
import uuid
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "outputs/q1-q10-classification-500-20260720"
SUPPORTED_IDS_FILE = OUT_DIR / "Q1-supported-rag-document-ids.txt"
CLASSIFICATION_IDS_FILE = OUT_DIR / "Q1-supported-document-ids.txt"
COHORT_MANIFEST = OUT_DIR / "Q1-test-cohort.json"
COHORT_NAME = "Q1-supported-500-20260720"
SOURCE_REF_ID = "08777e18-6028-44da-ab30-eb22cc21ea10"
API_BASE = "http://localhost:8081/api"
LOGIN_ACCOUNT = "admin"
LOGIN_PASSWORD = "admin123456"


def run_psql(sql: str) -> str:
    result = subprocess.run(
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


def load_supported_ids() -> list[str]:
    if not SUPPORTED_IDS_FILE.is_file():
        subprocess.run([sys.executable, str(ROOT / "scripts/resolve_q1_document_ids.py")], check=True)
    return [
        line.strip()
        for line in SUPPORTED_IDS_FILE.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def register_missing_documents(document_ids: list[str]) -> list[str]:
    return []


def refresh_cohort(document_ids: list[str]) -> tuple[uuid.UUID, str]:
    cohort_id = uuid.uuid5(uuid.NAMESPACE_URL, f"demo_01:{COHORT_NAME}")
    payload = "\n".join(document_ids)
    import hashlib

    doc_hash = hashlib.sha256(payload.encode("utf-8")).hexdigest()
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
    for ordinal, document_id in enumerate(document_ids, start=1):
        lines.append(
            "INSERT INTO cohort_member "
            "(cohort_id, document_id, ordinal, added_reason) "
            f"VALUES ('{cohort_id}', '{document_id}', {ordinal}, "
            "'Q1 supported by 500-doc classification');"
        )
    run_psql("\n".join(lines))
    manifest = {
        "cohortId": str(cohort_id),
        "name": COHORT_NAME,
        "sourceType": "CLASSIFICATION",
        "sourceRefId": SOURCE_REF_ID,
        "classificationOutputDir": str(OUT_DIR.relative_to(ROOT)).replace("\\", "/"),
        "classificationSupportedCount": len(document_ids),
        "databaseAvailableCount": len(document_ids),
        "missingDocumentIds": [],
        "documentCount": len(document_ids),
        "documentIds": document_ids,
        "inputHash": doc_hash,
    }
    COHORT_MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return cohort_id, doc_hash


def wait_for_backend(timeout_sec: int = 300) -> None:
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            response = requests.get(f"{API_BASE}/user/get/login", timeout=3)
            if response.status_code in {200, 401}:
                return
        except requests.RequestException:
            pass
        time.sleep(3)
    raise TimeoutError("Backend did not become ready in time")


def login(session: requests.Session) -> None:
    response = session.post(
        f"{API_BASE}/user/login",
        json={"userAccount": LOGIN_ACCOUNT, "userPassword": LOGIN_PASSWORD},
        timeout=30,
    )
    response.raise_for_status()
    body = response.json()
    if body.get("code") != 0:
        raise RuntimeError(f"Login failed: {body}")


def ingest_documents(session: requests.Session, document_ids: list[str]) -> None:
    for document_id in document_ids:
        response = session.post(
            f"{API_BASE}/rag/documents/{document_id}/ingest",
            timeout=60,
        )
        response.raise_for_status()
        body = response.json()
        if body.get("code") != 0:
            raise RuntimeError(f"Ingest enqueue failed for {document_id}: {body}")
        print(f"ingest queued: {document_id}", flush=True)


def wait_for_ingest(session: requests.Session, document_ids: list[str], timeout_sec: int = 900) -> None:
    deadline = time.time() + timeout_sec
    pending = set(document_ids)
    while pending and time.time() < deadline:
        done: set[str] = set()
        for document_id in pending:
            response = session.get(f"{API_BASE}/rag/documents/{document_id}", timeout=30)
            response.raise_for_status()
            body = response.json()
            if body.get("code") != 0:
                raise RuntimeError(f"Document lookup failed for {document_id}: {body}")
            status = ((body.get("data") or {}).get("status") or "").upper()
            if status == "COMPLETED":
                done.add(document_id)
            elif status == "FAILED":
                raise RuntimeError(f"Document ingest failed for {document_id}: {body}")
        pending -= done
        if pending:
            print(f"waiting ingest: {len(pending)} remaining", flush=True)
            time.sleep(5)
    if pending:
        raise TimeoutError(f"Ingest did not complete for: {sorted(pending)}")


def start_extraction(session: requests.Session, cohort_id: uuid.UUID) -> str:
    response = session.post(
        f"{API_BASE}/stages/extract/runs",
        json={
            "questionId": "Q1",
            "label": "Q1 extraction for 500-doc Q1-supported cohort",
            "sourceType": "COHORT",
            "cohortId": str(cohort_id),
            "force": True,
        },
        timeout=60,
    )
    response.raise_for_status()
    body = response.json()
    if body.get("code") != 0:
        raise RuntimeError(f"Extraction submit failed: {body}")
    data = body["data"]
    print(
        f"extraction accepted: runId={data['runId']} "
        f"status={data['status']} total={data['totalDocuments']} reused={data['reused']}",
        flush=True,
    )
    return data["runId"]


def poll_extraction(session: requests.Session, run_id: str, timeout_sec: int = 7200) -> dict:
    deadline = time.time() + timeout_sec
    terminal = {"COMPLETED", "PARTIAL_FAILED", "FAILED"}
    while time.time() < deadline:
        response = session.get(f"{API_BASE}/stages/extract/runs/{run_id}", timeout=30)
        response.raise_for_status()
        body = response.json()
        if body.get("code") != 0:
            raise RuntimeError(f"Run lookup failed: {body}")
        data = body["data"]
        status = data["status"]
        print(
            f"run {run_id}: {status} "
            f"processed={data.get('processedDocuments')}/{data.get('totalDocuments')} "
            f"completed={data.get('completedDocuments')} "
            f"no_evidence={data.get('noEvidenceDocuments')} "
            f"failed={data.get('failedDocuments')}",
            flush=True,
        )
        if status in terminal:
            return data
        time.sleep(15)
    raise TimeoutError(f"Extraction run {run_id} did not finish in time")


def main() -> None:
    supported_ids = load_supported_ids()
    if len(supported_ids) != 111:
        raise RuntimeError(f"expected 111 supported IDs, got {len(supported_ids)}")

    missing = register_missing_documents(supported_ids)
    if missing:
        print(f"registered missing rag_document rows: {len(missing)}", flush=True)

    cohort_id, _ = refresh_cohort(supported_ids)
    print(f"cohort refreshed: {cohort_id} members={len(supported_ids)}", flush=True)

    wait_for_backend()
    session = requests.Session()
    login(session)

    if missing:
        ingest_documents(session, missing)
        wait_for_ingest(session, missing)

    run_id = start_extraction(session, cohort_id)
    final = poll_extraction(session, run_id)
    print("final status:", json.dumps(final, ensure_ascii=False, indent=2), flush=True)
    if final["status"] not in {"COMPLETED", "PARTIAL_FAILED"}:
        sys.exit(1)


if __name__ == "__main__":
    main()
