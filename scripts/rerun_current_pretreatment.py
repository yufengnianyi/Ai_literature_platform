import argparse
import csv
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path

import requests


DEFAULT_MODEL = "qwen3-max-2026-01-23"
DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact-root", default="data/rag")
    parser.add_argument("--output-root", default="PreTreatment/outputs/current-rerun-100")
    parser.add_argument("--prompt-path", default="PreTreatment/prompts/oomycete-main-study-system.txt")
    parser.add_argument("--max-documents", type=int, default=100)
    parser.add_argument(
        "--document-ids",
        help="Optional text file containing one document id per line. When provided, only these documents are screened.",
    )
    parser.add_argument("--model", default=os.environ.get("DASHSCOPE_CHAT_MODEL", DEFAULT_MODEL))
    parser.add_argument("--endpoint", default=os.environ.get("DASHSCOPE_CHAT_ENDPOINT", DEFAULT_ENDPOINT))
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=120)
    return parser.parse_args()


def read_api_key():
    key = os.environ.get("DASHSCOPE_API_KEY")
    if key:
        return key
    local_yml = Path("src/main/resources/application-local.yml")
    if local_yml.is_file():
        text = local_yml.read_text(encoding="utf-8")
        match = re.search(r"api-key:\s*\$\{DASHSCOPE_API_KEY:([^}]+)\}", text)
        if match:
            return match.group(1).strip()
    raise RuntimeError("DASHSCOPE_API_KEY is not set and no fallback key was found")


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def load_chunks(path):
    chunks = []
    if not path.is_file():
        return chunks
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.strip():
            chunks.append(json.loads(line))
    return chunks


def value(text):
    return "" if text is None else str(text)


def metrics(chunks):
    chunk_count = len(chunks)
    total_text_chars = 0
    replacement_chars = 0
    line_count = 0
    short_lines = 0
    for chunk in chunks:
        text = value(chunk.get("text"))
        if not text.strip():
            continue
        total_text_chars += len(text)
        replacement_chars += sum(1 for ch in text if ch == "\ufffd" or ch == "?")
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            line_count += 1
            if len(stripped) < 20:
                short_lines += 1
    return {
        "chunkCount": chunk_count,
        "totalTextChars": total_text_chars,
        "averageChunkChars": round(total_text_chars / chunk_count, 4) if chunk_count else 0.0,
        "replacementCharRatio": round(replacement_chars / total_text_chars, 4) if total_text_chars else 0.0,
        "shortLineRatio": round(short_lines / line_count, 4) if line_count else 0.0,
    }


def quality_gate(metadata, chunks):
    m = metrics(chunks)
    if not value(metadata.get("title")).strip():
        return "REJECT", "MISSING_TITLE", "Missing title.", m
    if m["chunkCount"] < 3:
        return "REJECT", "LOW_CHUNK_COUNT", "PDF conversion quality is too low: chunk count below threshold.", m
    if m["totalTextChars"] < 1500:
        return "REJECT", "LOW_TEXT_COVERAGE", "PDF conversion quality is too low: extracted text below threshold.", m
    if m["replacementCharRatio"] > 0.03:
        return "REJECT", "HIGH_GARBLED_TEXT_RATIO", "PDF conversion quality is too low: replacement/garbled character ratio above threshold.", m
    if m["shortLineRatio"] > 0.80:
        return "REJECT", "HIGH_SHORT_LINE_RATIO", "PDF conversion quality is too low: abnormal short line ratio above threshold.", m
    return "PASS", "", "Quality gate passed.", m


def user_message(metadata):
    return (
        "Metadata:\n"
        f"Title: {value(metadata.get('title'))}\n"
        f"Journal: {value(metadata.get('journal'))}\n"
        f"DOI: {value(metadata.get('doiNormalized'))}\n"
        f"Abstract: {value(metadata.get('abstractText'))}\n"
    )


def extract_json(raw):
    if not raw or not raw.strip():
        return "{}"
    trimmed = raw.strip()
    first = trimmed.find("{")
    last = trimmed.rfind("}")
    if first >= 0 and last > first:
        return trimmed[first:last + 1]
    return trimmed


def call_llm(endpoint, api_key, model, system_prompt, metadata, max_attempts, timeout):
    base_user = user_message(metadata)
    last_error = None
    for attempt in range(1, max(1, max_attempts) + 1):
        prompt = base_user
        if attempt > 1:
            prompt += (
                "\n\nRetry: previous output was invalid JSON: "
                + (str(last_error) if last_error else "unknown")
                + "\nReturn only one strict JSON object."
            )
        try:
            response = requests.post(
                endpoint,
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": model,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": prompt},
                    ],
                    "temperature": 0,
                    "enable_thinking": False,
                },
                timeout=timeout,
            )
            response.raise_for_status()
            payload = response.json()
            text = payload["choices"][0]["message"]["content"]
            parsed = json.loads(extract_json(text))
            return {
                "label": parsed.get("label") or "NOT_RUN",
                "taxa": parsed.get("taxa") or [],
                "researchFocus": parsed.get("researchFocus") or "",
                "evidenceChunkIds": parsed.get("evidenceChunkIds") or [],
                "reason": parsed.get("reason") or "",
                "usage": payload.get("usage") or {},
            }
        except Exception as exc:
            last_error = exc
            if attempt >= max(1, max_attempts):
                raise
            time.sleep(1.5 * attempt)
    raise RuntimeError("LLM judgment failed") from last_error


def final_decision(quality_decision, llm_label):
    if quality_decision == "REJECT":
        return "REJECTED"
    if llm_label == "RELEVANT":
        return "ACCEPTED"
    return "REJECTED"


def reason_code(final_decision_value, quality_code, llm_label):
    if final_decision_value != "REJECTED":
        return ""
    if quality_code:
        return quality_code
    if llm_label == "NOT_RELEVANT":
        return "LLM_NOT_RELEVANT"
    if llm_label == "NOT_RUN":
        return "LLM_NOT_RUN"
    return ""


def write_csv(path, rows):
    fieldnames = [
        "documentId", "title", "journal", "doi", "qualityDecision", "rejectReasonCode",
        "llmLabel", "finalDecision", "taxa", "researchFocus", "reason",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow({
                "documentId": row["documentId"],
                "title": row.get("title") or "",
                "journal": row.get("journal") or "",
                "doi": row.get("doi") or "",
                "qualityDecision": row["qualityDecision"],
                "rejectReasonCode": row["rejectReasonCode"],
                "llmLabel": row["llmLabel"],
                "finalDecision": row["finalDecision"],
                "taxa": "; ".join(row.get("taxa") or []),
                "researchFocus": row.get("researchFocus") or "",
                "reason": row.get("reason") or "",
            })


def write_summary(path, rows, args):
    total = len(rows)
    accepted = sum(1 for row in rows if row["finalDecision"] == "ACCEPTED")
    rejected = sum(1 for row in rows if row["finalDecision"] == "REJECTED")
    quality_rejected = sum(1 for row in rows if row["qualityDecision"] == "REJECT")
    relevant = sum(1 for row in rows if row["llmLabel"] == "RELEVANT")
    not_relevant = sum(1 for row in rows if row["llmLabel"] == "NOT_RELEVANT")
    not_run = sum(1 for row in rows if row["llmLabel"] == "NOT_RUN")
    path.write_text(
        "\n".join([
            "# Current PreTreatment Rerun Summary",
            "",
            f"- Total documents: {total}",
            f"- Accepted: {accepted}",
            f"- Rejected: {rejected}",
            f"- Quality-gate rejected: {quality_rejected}",
            f"- LLM RELEVANT: {relevant}",
            f"- LLM NOT_RELEVANT: {not_relevant}",
            f"- LLM NOT_RUN: {not_run}",
            f"- Model: {args.model}",
            f"- Prompt: {args.prompt_path}",
            "",
            "## Comparison Note",
            "",
            "The old `scan-100-20260709-165149` per-document output is not present in the workspace anymore,",
            "so this rerun uses the same collection rule: first 100 `data/rag` artifact directories sorted by directory name.",
        ]) + "\n",
        encoding="utf-8",
    )


def main():
    args = parse_args()
    api_key = read_api_key()
    artifact_root = Path(args.artifact_root)
    output_root = Path(args.output_root)
    run_id = str(uuid.uuid4())
    output_dir = output_root / run_id
    output_dir.mkdir(parents=True, exist_ok=True)
    prompt = Path(args.prompt_path).read_text(encoding="utf-8")
    if args.document_ids:
        document_ids = [
            line.strip()
            for line in Path(args.document_ids).read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        dirs = [artifact_root / document_id for document_id in document_ids]
    else:
        dirs = sorted([p for p in artifact_root.iterdir() if p.is_dir()], key=lambda p: p.name)
    if not args.document_ids and args.max_documents > 0:
        dirs = dirs[:args.max_documents]

    results_path = output_dir / "results.jsonl"
    rows = []
    with results_path.open("w", encoding="utf-8") as out:
        for index, doc_dir in enumerate(dirs, start=1):
            manifest_path = doc_dir / "artifact-manifest.json"
            jsonl_path = doc_dir / "document.jsonl"
            document_id = doc_dir.name
            try:
                if not manifest_path.is_file() or not jsonl_path.is_file():
                    raise FileNotFoundError("Missing artifact-manifest.json or document.jsonl")
                manifest = load_json(manifest_path)
                metadata = manifest.get("metadata") or {}
                chunks = load_chunks(jsonl_path)
                quality_decision, quality_code, quality_reason, quality_metrics = quality_gate(metadata, chunks)
                if quality_decision == "REJECT":
                    judgment = {
                        "label": "NOT_RUN",
                        "taxa": [],
                        "researchFocus": "",
                        "evidenceChunkIds": [],
                        "reason": quality_reason,
                        "usage": {},
                    }
                else:
                    judgment = call_llm(
                        args.endpoint, api_key, args.model, prompt, metadata,
                        args.max_attempts, args.timeout,
                    )
            except Exception as exc:
                metadata = locals().get("metadata", {}) or {}
                quality_decision = "REJECT"
                if isinstance(exc, FileNotFoundError):
                    quality_code = "MISSING_ARTIFACT"
                    quality_metrics = {
                        "chunkCount": 0,
                        "totalTextChars": 0,
                        "averageChunkChars": 0.0,
                        "replacementCharRatio": 0.0,
                        "shortLineRatio": 0.0,
                    }
                    reason = str(exc)
                else:
                    quality_code = "RERUN_ERROR"
                    quality_metrics = {}
                    reason = f"Rerun failed: {exc}"
                judgment = {
                    "label": "NOT_RUN",
                    "taxa": [],
                    "researchFocus": "",
                    "evidenceChunkIds": [],
                    "reason": reason,
                    "usage": {},
                }

            decision = final_decision(quality_decision, judgment["label"])
            row = {
                "documentId": document_id,
                "storageDir": str(doc_dir),
                "title": metadata.get("title"),
                "journal": metadata.get("journal"),
                "doi": metadata.get("doiNormalized"),
                "qualityDecision": quality_decision,
                "qualityMetrics": quality_metrics,
                "llmLabel": judgment["label"],
                "finalDecision": decision,
                "rejectReasonCode": reason_code(decision, quality_code, judgment["label"]),
                "taxa": judgment.get("taxa") or [],
                "researchFocus": judgment.get("researchFocus") or "",
                "evidenceChunkIds": judgment.get("evidenceChunkIds") or [],
                "reason": judgment.get("reason") or "",
                "usage": judgment.get("usage") or {},
            }
            rows.append(row)
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            out.flush()
            print(f"[{index}/{len(dirs)}] {document_id} {row['finalDecision']} {row['llmLabel']}", flush=True)

    write_csv(output_dir / "results.csv", rows)
    accepted = [row["documentId"] for row in rows if row["finalDecision"] == "ACCEPTED"]
    rejected = [row["documentId"] for row in rows if row["finalDecision"] == "REJECTED"]
    (output_dir / "accepted-document-ids.txt").write_text("\n".join(accepted) + ("\n" if accepted else ""), encoding="utf-8")
    (output_dir / "rejected-document-ids.txt").write_text("\n".join(rejected) + ("\n" if rejected else ""), encoding="utf-8")
    write_summary(output_dir / "summary.md", rows, args)
    print(f"OUTPUT_DIR={output_dir}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
