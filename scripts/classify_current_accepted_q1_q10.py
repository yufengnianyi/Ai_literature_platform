import argparse
import csv
import json
import os
import re
import sys
import time
from pathlib import Path

import requests


DEFAULT_MODEL = "qwen3-max-2026-01-23"
DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

PROFILES = [
    ("Q1", "Antimicrobial compound evidence",
     "Evidence that compounds, extracts, mixtures, pesticides, or derivatives are active against oomycetes."),
    ("Q2", "Effector pathogenicity mechanisms, secretion, and structure",
     "Evidence on oomycete host-directed effectors, including secreted or translocated proteins, AVR proteins, host targets, immune suppression or activation, host-cell entry, localization, pathogenicity mechanisms, structural characterization, and functional validation."),
    ("Q3", "Resistance genes: host and non-host resistance",
     "Evidence on host resistance, non-host resistance, and partial or quantitative resistance genes, loci, QTLs, mechanisms, and validation against oomycetes."),
    ("Q4", "Fungicide/oomyceticide resistance and targets",
     "Evidence on oomycete resistance to fungicides or oomyceticides, molecular targets, target validation, target mutations, resistance levels, field occurrence, and detection methods."),
    ("Q5", "Genome, pan-genome, and effector repertoire",
     "Evidence on oomycete genome assemblies, pan-genomes, population genomes, gene counts, core and pan-gene counts, effector repertoires, repeats, and reference genome versions."),
    ("Q6", "Oomycete functional genes, including metabolism and nutrition",
     "Evidence on non-classical-effector oomycete functional genes related to pathogen-intrinsic growth and development, pathogenicity or virulence, metabolism and nutrition, stress responses, cell wall or membrane biosynthesis, signaling, transcriptional regulation, reproduction, and other non-effector functions."),
    ("Q7", "Biological control and green disease management",
     "Evidence on biological control agents, plant-derived inducers, nanomaterials, novel compounds, oomycete viruses, and other green-control approaches against oomycete diseases."),
    ("Q8", "Disease diagnosis and molecular detection",
     "Evidence on PCR, qPCR, LAMP, RPA, and other diagnostic or molecular detection methods for oomycete pathogens."),
    ("Q9", "Disease epidemiology and prediction models",
     "Evidence on environmental drivers of oomycete disease epidemics, risk warnings, and construction or validation of prediction models."),
    ("Q10", "Physiological races, population diversity, and evolution",
     "Evidence on physiological races, pathotypes, population genetic diversity, Avr-R relationships, geographic distributions, and evolutionary relationships in oomycete pathogens."),
]

RANK = {"NOT_SUPPORTED": 0, "UNCERTAIN": 1, "SUPPORTED": 2}


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--accepted-ids",
        default="PreTreatment/outputs/current-rerun-100-20260729-093922/95f68505-2474-4295-91e8-ca6e7bfe7723/accepted-document-ids.txt",
    )
    parser.add_argument("--artifact-root", default="data/rag")
    parser.add_argument(
        "--output-dir",
        default="PreTreatment/outputs/current-rerun-100-20260729-093922/95f68505-2474-4295-91e8-ca6e7bfe7723/q1-q10-classification",
    )
    parser.add_argument("--prompt-path", default="src/main/resources/prompts/evidence/multi-profile-classification-system.txt")
    parser.add_argument("--model", default=os.environ.get("DASHSCOPE_CHAT_MODEL", DEFAULT_MODEL))
    parser.add_argument("--endpoint", default=os.environ.get("DASHSCOPE_CHAT_ENDPOINT", DEFAULT_ENDPOINT))
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--chunk-batch-size", type=int, default=12)
    parser.add_argument("--max-single-pass-chunks", type=int, default=40)
    parser.add_argument("--max-single-pass-chars", type=int, default=120000)
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
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.strip():
            row = json.loads(line)
            chunks.append({
                "chunkId": row.get("chunk_id"),
                "chunkIndex": row.get("chunk_index") or 0,
                "sectionPath": row.get("section_path"),
                "text": row.get("text") or "",
            })
    return chunks


def value(text):
    return "" if text is None else str(text)


def context_chunks(chunks):
    selected = []
    for chunk in chunks:
        section = value(chunk.get("sectionPath")).lower()
        if (
            "abstract" in section
            or "摘要" in section
            or "method" in section
            or "材料" in section
            or "方法" in section
            or "result" in section
            or "结果" in section
        ):
            selected.append(chunk)
        if len(selected) >= 6:
            break
    return selected if selected else chunks[:2]


def chunk_key(chunk):
    return value(chunk.get("chunkId")) or str(chunk.get("chunkIndex") or 0)


def merge_chunks(context, batch):
    merged = {}
    for chunk in context + batch:
        merged[chunk_key(chunk)] = chunk
    return list(merged.values())


def model_batches(chunks, args):
    total_chars = sum(len(value(chunk.get("text"))) for chunk in chunks)
    if len(chunks) <= args.max_single_pass_chunks and total_chars <= args.max_single_pass_chars:
        return [merge_chunks(context_chunks(chunks), chunks)]
    shared = context_chunks(chunks)
    batches = []
    size = max(1, args.chunk_batch_size)
    for start in range(0, len(chunks), size):
        end = min(len(chunks), start + size)
        adjacent_start = max(0, start - 1)
        adjacent_end = min(len(chunks), end + 1)
        batches.append(merge_chunks(shared, chunks[adjacent_start:adjacent_end]))
    return batches


def questions_text():
    return "".join(f"- {qid} {title}: {scope}\n" for qid, title, scope in PROFILES)


def metadata_text(document_id, metadata):
    authors = metadata.get("authors") or []
    return (
        "Document metadata:\n"
        f"- document_id: {document_id}\n"
        f"- title: {value(metadata.get('title'))}\n"
        f"- authors: {', '.join(authors)}\n"
        f"- publication_year: {value(metadata.get('publicationYear'))}\n"
        f"- journal: {value(metadata.get('journal'))}\n"
        f"- doi: {value(metadata.get('doiNormalized'))}\n"
    )


def render_chunks(chunks):
    parts = []
    for chunk in chunks:
        parts.append(
            f"\n--- chunk_id={value(chunk.get('chunkId'))}; section={value(chunk.get('sectionPath'))} ---\n"
            + value(chunk.get("text"))
        )
    return "".join(parts)


def classification_input(document_id, metadata, chunks):
    return (
        "Questions:\n"
        f"{questions_text()}\n"
        f"{metadata_text(document_id, metadata)}\n"
        "Supplied chunks:\n"
        f"{render_chunks(chunks)}\n"
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


def call_llm(endpoint, api_key, model, system_prompt, user_prompt, max_attempts, timeout):
    last_error = None
    for attempt in range(1, max(1, max_attempts) + 1):
        prompt = user_prompt
        if attempt > 1:
            prompt += (
                "\n\nRetry: previous output was invalid: "
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
            return json.loads(extract_json(text)), payload.get("usage") or {}
        except Exception as exc:
            last_error = exc
            if attempt >= max(1, max_attempts):
                raise
            time.sleep(1.5 * attempt)
    raise RuntimeError("classification failed") from last_error


def normalize_batch(output, supplied_chunks):
    valid_chunk_ids = {chunk.get("chunkId") for chunk in supplied_chunks if chunk.get("chunkId")}
    raw_questions = output.get("questions") or []
    by_question = {}
    for item in raw_questions:
        qid = value(item.get("questionId")).strip()
        if qid:
            by_question[qid] = item
    results = []
    for qid, _, _ in PROFILES:
        raw = by_question.get(qid)
        if not raw:
            raise ValueError(f"Missing classification for {qid}")
        declared = value(raw.get("status")).strip().upper()
        if declared not in RANK:
            raise ValueError(f"Invalid status for {qid}: {declared}")
        try:
            confidence = max(0.0, min(1.0, float(raw.get("confidence") or 0.0)))
        except Exception:
            confidence = 0.0
        requested = []
        for chunk_id in raw.get("chunkIds") or []:
            if chunk_id and chunk_id not in requested:
                requested.append(chunk_id)
        valid = [chunk_id for chunk_id in requested if chunk_id in valid_chunk_ids]
        invalid_citations = len(valid) != len(requested)
        if declared == "NOT_SUPPORTED":
            status = "NOT_SUPPORTED"
            valid = []
        elif declared == "SUPPORTED" and confidence >= 0.70 and valid and not invalid_citations:
            status = "SUPPORTED"
        elif confidence >= 0.40 or invalid_citations or (declared == "SUPPORTED" and not valid):
            status = "UNCERTAIN"
        else:
            status = "NOT_SUPPORTED"
            valid = []
        results.append({
            "questionId": qid,
            "status": status,
            "confidence": confidence,
            "reason": value(raw.get("reason")),
            "chunkIds": valid,
        })
    return results


def merge_classifications(batch_results):
    merged = []
    for qid, _, _ in PROFILES:
        candidates = [item for batch in batch_results for item in batch if item["questionId"] == qid]
        status = max((item["status"] for item in candidates), key=lambda s: RANK[s], default="NOT_SUPPORTED")
        same_status = [item for item in candidates if item["status"] == status]
        confidence = max((item["confidence"] for item in same_status), default=0.0)
        chunk_ids = []
        reasons = []
        for item in same_status:
            for chunk_id in item.get("chunkIds") or []:
                if chunk_id not in chunk_ids:
                    chunk_ids.append(chunk_id)
            reason = item.get("reason")
            if reason and reason not in reasons:
                reasons.append(reason)
        merged.append({
            "questionId": qid,
            "status": status,
            "confidence": confidence,
            "reason": "；".join(reasons[:3]),
            "chunkIds": chunk_ids,
        })
    return merged


def write_outputs(output_dir, rows):
    with (output_dir / "classification-results.jsonl").open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    with (output_dir / "classification-results.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        fieldnames = ["documentId", "title", "questionId", "status", "confidence", "chunkIds", "reason"]
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            for item in row["questions"]:
                writer.writerow({
                    "documentId": row["documentId"],
                    "title": row.get("title") or "",
                    "questionId": item["questionId"],
                    "status": item["status"],
                    "confidence": item["confidence"],
                    "chunkIds": ";".join(item.get("chunkIds") or []),
                    "reason": item.get("reason") or "",
                })

    summary = {}
    for qid, _, _ in PROFILES:
        summary[qid] = {"SUPPORTED": 0, "UNCERTAIN": 0, "NOT_SUPPORTED": 0}
    for row in rows:
        for item in row["questions"]:
            summary[item["questionId"]][item["status"]] += 1

    with (output_dir / "classification-summary.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["questionId", "SUPPORTED", "UNCERTAIN", "NOT_SUPPORTED"])
        writer.writeheader()
        for qid in summary:
            writer.writerow({"questionId": qid, **summary[qid]})

    supported_lines = []
    for qid in summary:
        supported = [
            row["documentId"]
            for row in rows
            for item in row["questions"]
            if item["questionId"] == qid and item["status"] == "SUPPORTED"
        ]
        (output_dir / f"{qid}-supported-document-ids.txt").write_text(
            "\n".join(supported) + ("\n" if supported else ""),
            encoding="utf-8",
        )
        supported_lines.append(f"- {qid}: {summary[qid]['SUPPORTED']} supported, {summary[qid]['UNCERTAIN']} uncertain")

    total_tokens = sum((row.get("usage") or {}).get("total_tokens", 0) for row in rows)
    prompt_tokens = sum((row.get("usage") or {}).get("prompt_tokens", 0) for row in rows)
    completion_tokens = sum((row.get("usage") or {}).get("completion_tokens", 0) for row in rows)
    (output_dir / "classification-summary.md").write_text(
        "# Q1-Q10 Classification Test Summary\n\n"
        f"- Documents classified: {len(rows)}\n"
        f"- LLM calls: {sum(row.get('llmCalls', 0) for row in rows)}\n"
        f"- Prompt tokens: {prompt_tokens}\n"
        f"- Completion tokens: {completion_tokens}\n"
        f"- Total tokens: {total_tokens}\n\n"
        "## Supported/Uncertain Counts\n\n"
        + "\n".join(supported_lines)
        + "\n",
        encoding="utf-8",
    )


def main():
    args = parse_args()
    api_key = read_api_key()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    system_prompt = Path(args.prompt_path).read_text(encoding="utf-8")
    accepted_ids = [
        line.strip()
        for line in Path(args.accepted_ids).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]

    results_path = output_dir / "classification-results.jsonl"
    completed = set()
    rows = []
    if results_path.is_file():
        for line in results_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                rows.append(row)
                completed.add(row["documentId"])

    with results_path.open("a", encoding="utf-8") as append_handle:
        for index, document_id in enumerate(accepted_ids, start=1):
            if document_id in completed:
                print(f"[{index}/{len(accepted_ids)}] {document_id} SKIP", flush=True)
                continue
            doc_dir = Path(args.artifact_root) / document_id
            manifest = load_json(doc_dir / "artifact-manifest.json")
            metadata = manifest.get("metadata") or {}
            chunks = load_chunks(doc_dir / "document.jsonl")
            batches = model_batches(chunks, args)
            batch_results = []
            usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
            for batch_index, batch in enumerate(batches, start=1):
                prompt = classification_input(document_id, metadata, batch)
                output, batch_usage = call_llm(
                    args.endpoint, api_key, args.model, system_prompt, prompt,
                    args.max_attempts, args.timeout,
                )
                batch_results.append(normalize_batch(output, batch))
                for key in usage:
                    usage[key] += batch_usage.get(key, 0) or batch_usage.get(key.replace("_tokens", "Tokens"), 0) or 0
                print(
                    f"[{index}/{len(accepted_ids)}] {document_id} batch {batch_index}/{len(batches)}",
                    flush=True,
                )
            questions = merge_classifications(batch_results)
            row = {
                "documentId": document_id,
                "title": metadata.get("title"),
                "journal": metadata.get("journal"),
                "doi": metadata.get("doiNormalized"),
                "chunkCount": len(chunks),
                "llmCalls": len(batches),
                "usage": usage,
                "questions": questions,
            }
            rows.append(row)
            append_handle.write(json.dumps(row, ensure_ascii=False) + "\n")
            append_handle.flush()
            supported = sum(1 for item in questions if item["status"] == "SUPPORTED")
            uncertain = sum(1 for item in questions if item["status"] == "UNCERTAIN")
            print(
                f"[{index}/{len(accepted_ids)}] {document_id} DONE supported={supported} uncertain={uncertain}",
                flush=True,
            )

    write_outputs(output_dir, rows)
    print(f"OUTPUT_DIR={output_dir}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
