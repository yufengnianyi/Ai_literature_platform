"""Question extraction, source validation, directory synthesis, and immutable run inputs."""

import importlib.metadata
import json
import sys
from collections import Counter
from pathlib import Path

from clustering import cluster_members
from corpus import content_hash
from model_client import FatalModelError
from storage import ROOT, digest, read_json, read_jsonl, timestamp, write_json, write_jsonl, write_text


def require_text(value, name):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-empty text")
    return value.strip()


def locate_quote(abstract, quote, start=None):
    if not isinstance(quote, str) or not quote.strip():
        raise ValueError("evidence quote must be non-empty text")
    if start is not None:
        if isinstance(start, bool) or not isinstance(start, int) or start < 0 or abstract[start:start + len(quote)] != quote:
            raise ValueError("quote offset does not match the original abstract")
    else:
        start = abstract.find(quote)
        if start < 0:
            raise ValueError("quote does not occur in the original abstract")
        if abstract.find(quote, start + 1) >= 0:
            raise ValueError("quote has multiple occurrences; specify start_offset")
    return {"quote": quote, "start_offset": start, "end_offset": start + len(quote)}


def validate_extraction(value, document):
    status, questions = value.get("status"), value.get("questions")
    if status not in {"EXTRACTED", "INSUFFICIENT_INFORMATION"} or not isinstance(questions, list):
        raise ValueError("invalid extraction status or questions")
    if not isinstance(value.get("overflow"), bool):
        raise ValueError("overflow must be explicitly true or false")
    if len(questions) > 3:
        raise ValueError("more than three questions; explicitly flag overflow and return at most three")
    if bool(questions) != (status == "EXTRACTED"):
        raise ValueError("extraction status contradicts the question count")
    result, seen = [], set()
    for index, question in enumerate(questions, 1):
        if not isinstance(question, dict):
            raise ValueError("each extracted question must be an object")
        text = require_text(question.get("question"), "question")
        if text.casefold() in seen:
            raise ValueError("duplicate question within a paper")
        seen.add(text.casefold())
        if question.get("question_origin") not in {"EXPLICIT", "INFERRED_FROM_OBJECTIVE"}:
            raise ValueError("invalid question origin")
        quote = locate_quote(document["abstract"], question.get("evidence_quote"), question.get("start_offset"))
        result.append({"question_id": document["document_id"] + f":q{index}", "question": text,
                       "research_object": require_text(question.get("research_object"), "research_object"),
                       "question_origin": question["question_origin"], "source_field": "abstract", **quote})
    return {"document_id": document["document_id"], "status": status, "overflow": value["overflow"], "questions": result}


def validate_summary(value, members):
    lookup = {member["member_id"]: member for member in members}
    status = value.get("status")
    if status not in {"READY", "NEEDS_SPLIT"}:
        raise ValueError("invalid summary status")
    common = value.get("common_questions")
    if not isinstance(common, list) or len(common) > 6 or (status == "READY" and not common):
        raise ValueError("summary must contain at most six questions, and READY cannot be empty")
    checked = []
    for question in common:
        if not isinstance(question, dict):
            raise ValueError("each common question must be an object")
        ids = question.get("member_ids")
        evidence = question.get("evidence")
        if not isinstance(ids, list) or not ids or any(not isinstance(i, str) for i in ids):
            raise ValueError("common question needs member IDs")
        if len(set(ids)) != len(ids) or not set(ids) <= lookup.keys():
            raise ValueError("duplicate, invented, or cross-cluster member reference")
        if not isinstance(evidence, list) or not all(isinstance(item, dict) for item in evidence):
            raise ValueError("common question needs evidence for every referenced member")
        if {item.get("member_id") for item in evidence} != set(ids):
            raise ValueError("evidence must cover exactly the referenced members")
        anchors = []
        for item in evidence:
            member = lookup[item["member_id"]]
            anchors.append({"member_id": member["member_id"], "document_id": member["document_id"],
                            **locate_quote(member["abstract"], item.get("quote"), item.get("start_offset"))})
        checked.append({"question": require_text(question.get("question"), "common question"),
                        "member_ids": ids, "evidence": anchors})
    return {"topic": require_text(value.get("topic"), "topic"), "status": status, "common_questions": checked}


def source_batches(members, max_members=12, max_chars=24000):
    batch, size = [], 0
    for member in members:
        length = len(json.dumps(member, ensure_ascii=False))
        if batch and (len(batch) >= max_members or size + length > max_chars):
            yield batch
            batch, size = [], 0
        batch.append(member)
        size += length
    if batch:
        yield batch


def embed_members(members, client):
    accepted, failures = [], {}
    batches, batch, size = [], [], 0
    for member in members:
        text = member["embedding_text"]
        if batch and (len(batch) == 10 or size + len(text) > 6000):
            batches.append(batch)
            batch, size = [], 0
        batch.append(member)
        size += len(text)
    if batch:
        batches.append(batch)
    for batch in batches:
        try:
            vectors = client.embed([member["embedding_text"] for member in batch])
            accepted.extend({**member, "vector": vector} for member, vector in zip(batch, vectors, strict=True))
        except FatalModelError:
            raise
        except RuntimeError as batch_error:
            if len(batch) == 1:
                failures[batch[0]["member_id"]] = client.safe_error(batch_error)
                continue
            # Isolate a failing input rather than losing an entire provider batch.
            for member in batch:
                try:
                    accepted.append({**member, "vector": client.embed([member["embedding_text"]])[0]})
                except FatalModelError:
                    raise
                except RuntimeError as error:
                    failures[member["member_id"]] = client.safe_error(error)
    return accepted, failures


def summarize_groups(groups, mode, client, prompt):
    output = []
    for number, group in enumerate(groups, 1):
        cluster_id = f"{mode}:c{number:03d}"
        members = [{key: value for key, value in member.items() if key not in {"vector", "embedding_text"}}
                   for member in group["members"]]
        parts, common = [], []
        for part_number, batch in enumerate(source_batches(members), 1):
            try:
                if len(json.dumps(batch, ensure_ascii=False)) > 24000:
                    raise ValueError("single member exceeds summary input budget; retained for review")
                summary = client.chat(prompt, {"members": batch},
                                      lambda value: validate_summary(value, batch), "summary")
            except FatalModelError:
                raise
            except (RuntimeError, ValueError) as error:
                summary = {"topic": "", "status": "FAILED", "common_questions": [], "error": client.safe_error(error)}
            parts.append({"part": part_number, "member_ids": [m["member_id"] for m in batch], **summary})
            for question in summary["common_questions"]:
                common.append({**question, "common_question_id": f"{cluster_id}:cq{len(common) + 1}"})
        if any(part["status"] == "FAILED" for part in parts):
            status = "PARTIAL" if common else "FAILED"
        elif len(parts) > 1 or any(part["status"] == "NEEDS_SPLIT" for part in parts):
            status = "NEEDS_SPLIT"
        else:
            status = "SINGLETON" if len(members) == 1 else "READY"
        represented = {member_id for question in common for member_id in question["member_ids"]}
        output.append({"cluster_id": cluster_id,
                       "topic": parts[0]["topic"] if len(parts) == 1 else "Partitioned cluster: review its subgroups",
                       "status": status, "document_count": group["document_count"], "members": members,
                       "representative_member_ids": group["representative_member_ids"],
                       "common_questions": common, "parts": parts,
                       "unrepresented_member_ids": sorted({m["member_id"] for m in members} - represented)})
    return output


def process_mode(corpus, mode, client, prompts, directory):
    members, extractions, documents = [], [], []
    for document in corpus:
        print(f"[{mode}] {document['document_id']}", flush=True)
        state = {"document_id": document["document_id"], "status": "READY", "error": None}
        if mode == "abstract":
            members.append({"member_id": document["document_id"] + ":abstract", **document,
                            "embedding_text": document["title"] + "\n" + document["abstract"]})
        else:
            try:
                if len(document["abstract"]) > 20000:
                    raise ValueError("abstract exceeds extraction input budget; not truncated")
                record = client.chat(prompts["extraction"],
                                     {key: document[key] for key in ("document_id", "title", "abstract")},
                                     lambda value: validate_extraction(value, document), "extraction")
                extractions.append(record)
                if not record["questions"]:
                    state["status"] = "INSUFFICIENT_INFORMATION"
                for question in record["questions"]:
                    members.append({**document, **question, "member_id": question["question_id"],
                                    "embedding_text": question["research_object"] + "\n" + question["question"]})
            except FatalModelError:
                raise
            except (RuntimeError, ValueError) as error:
                state.update(status="EXTRACTION_FAILED", error=client.safe_error(error))
                extractions.append({"document_id": document["document_id"], "status": state["status"],
                                    "error": state["error"], "overflow": False, "questions": []})
        documents.append(state)
        if mode == "question":
            write_jsonl(directory / "questions.jsonl", extractions)
    embedded, failures = embed_members(members, client)
    for state in documents:
        own = [member["member_id"] for member in members if member["document_id"] == state["document_id"]]
        failed = [member_id for member_id in own if member_id in failures]
        if failed:
            state.update(status="EMBEDDING_FAILED" if len(failed) == len(own) else "PARTIAL_EMBEDDING",
                         failed_member_ids=failed)
    write_json(directory / "embeddings.json", embedded)
    return documents, extractions, embedded, failures


def run_experiment(corpus_path, output, client, mode="both", threshold=0.35, resume=False, prompts_dir=None):
    output = Path(output)
    if not 0 < threshold <= 2:
        raise ValueError("distance threshold must be in (0, 2]")
    corpus = read_jsonl(corpus_path)
    for row in corpus:
        if any(not isinstance(row.get(key), str) for key in ("document_id", "title", "abstract")):
            raise ValueError("run input must be prepared corpus JSONL")
        if not row["document_id"] or not row["abstract"].strip() or row.get("content_hash") != content_hash(row):
            raise ValueError("invalid or changed corpus record; prepare a new snapshot")
    if len({row["document_id"] for row in corpus}) != len(corpus):
        raise ValueError("prepared corpus contains duplicate document IDs")
    corpus = sorted(corpus, key=lambda row: row["document_id"])
    client.check_corpus(corpus)
    prompts_dir = Path(prompts_dir) if prompts_dir else ROOT / "prompts"
    prompts = {"extraction": (prompts_dir / "extract_questions.txt").read_text(encoding="utf-8"),
               "summary": (prompts_dir / "summarize_cluster.txt").read_text(encoding="utf-8")}
    config = {"schema_version": 1, "mode": mode, "threshold": threshold,
              "cache_enabled": client.use_cache,
              "corpus_hash": digest(corpus), "models": client.public_config(),
              "prompt_hashes": {name: digest(text) for name, text in prompts.items()},
              "code_hash": digest({path.name: path.read_text(encoding="utf-8") for path in sorted(ROOT.glob("*.py"))}),
              "python": sys.version.split()[0],
              "packages": {name: importlib.metadata.version(name) for name in ("numpy", "requests", "scikit-learn")}}
    manifest_path = output / "manifest.json"
    if manifest_path.exists():
        manifest = read_json(manifest_path)
        if not resume:
            raise ValueError("run exists; use --resume or a new output directory")
        if manifest["config_hash"] != digest(config):
            raise ValueError("input, code, prompt or configuration changed; use a new run directory")
    else:
        if resume:
            raise ValueError("cannot resume a run without a manifest")
        manifest = {"kind": "problem-discovery-run", "run_id": output.name,
                    "created_at": timestamp(), "config_hash": digest(config), "config": config}
    manifest.update(status="RUNNING", updated_at=timestamp())
    write_json(manifest_path, manifest)
    write_jsonl(output / "corpus.jsonl", corpus)
    preparation = Path(corpus_path).parent / "manifest.json"
    if preparation.exists() and read_json(preparation).get("kind") == "prepared-corpus":
        write_json(output / "preparation.json", read_json(preparation))
    result = {"backend": client.backend, "corpus": corpus, "modes": {}}
    try:
        for current_mode in ("abstract", "question") if mode == "both" else (mode,):
            client.active_mode = current_mode
            directory = output / current_mode
            documents, extractions, embedded, failures = process_mode(corpus, current_mode, client, prompts, directory)
            clusters = summarize_groups(cluster_members(embedded, threshold), current_mode, client, prompts["summary"])
            data = {"documents": documents, "extractions": extractions, "clusters": clusters,
                    "telemetry": client.metrics(current_mode),
                    "embedding_failures": failures, "document_status_counts": dict(Counter(row["status"] for row in documents))}
            result["modes"][current_mode] = data
            write_json(directory / "clusters.json", clusters)
            write_json(directory / "documents.json", documents)
            write_json(directory / "embedding-failures.json", failures)
            write_json(output / "results.json", result)
        errors = sum(sum("FAILED" in row["status"] or "PARTIAL" in row["status"] for row in data["documents"])
                     + sum(cluster["status"] in {"PARTIAL", "FAILED"} for cluster in data["clusters"])
                     for data in result["modes"].values())
        manifest["status"] = "PARTIAL" if errors else "COMPLETED"
        result["status"] = manifest["status"]
        result["telemetry"] = client.metrics()
        write_json(output / "results.json", result)
        write_json(output / "metrics.json", {"status": result["status"], "telemetry": result["telemetry"],
                                             "human_evaluation": "PENDING", "error_count": errors})
        write_text(output / "report.md", render_report(result))
        from evaluation import write_review
        write_review(output / "review.csv", result)
    except Exception as error:
        manifest.update(status="FAILED", error=client.safe_error(error))
        raise
    finally:
        manifest["updated_at"] = timestamp()
        write_json(manifest_path, manifest)
    return result


def md_text(text):
    return str(text).replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ").replace("\r", " ")


def render_report(result):
    lines = ["# Research Question Directory", "", f"Backend: **{result['backend']}**", "",
             "SIMULATED OUTPUT: software verification only; not scientific evidence." if result["backend"] == "SIMULATED"
             else "Candidate directory for this input corpus; source support and coverage require human review.", "",
             f"Input documents: {len(result['corpus'])}. Status: {result.get('status', 'RUNNING')}.", "",
             "Counts describe reports in the input, not independent studies or scientific importance.", ""]
    for mode, data in result["modes"].items():
        lines += [f"## {mode}", "", f"Document states: {data['document_status_counts']}", ""]
        for cluster in data["clusters"]:
            lines += [f"### {cluster['cluster_id']}: {md_text(cluster['topic'])}", "",
                      f"Status: {cluster['status']}; documents: {cluster['document_count']}; members: {len(cluster['members'])}.", "",
                      "Representatives: " + ", ".join(cluster["representative_member_ids"]), ""]
            for question in cluster["common_questions"]:
                lines += [f"- {question['common_question_id']}: {md_text(question['question'])}"]
                for anchor in question["evidence"]:
                    lines += [f"  - {anchor['member_id']} [{anchor['start_offset']}:{anchor['end_offset']}]: {md_text(anchor['quote'])}"]
            lines += ["", "All members:", ""]
            for member in cluster["members"]:
                lines += [f"- {member['member_id']}: {md_text(member['title'])}",
                          f"  Abstract: {md_text(member['abstract'])}",
                          f"  Source: {md_text(json.dumps(member.get('source', {}), ensure_ascii=False))}"]
                if member.get("question"):
                    lines += [f"  Extracted question: {md_text(member['question'])}",
                              f"  Evidence [{member['start_offset']}:{member['end_offset']}]: {md_text(member['quote'])}"]
            lines += ["", "Not represented by common questions: " + ", ".join(cluster["unrepresented_member_ids"]), ""]
            for part in cluster["parts"]:
                if part.get("error"):
                    lines += [f"Part {part['part']} failed: {md_text(part['error'])}", ""]
        lines += ["Documents without usable directory questions or with partial processing:", ""]
        represented = {anchor["document_id"] for cluster in data["clusters"] for question in cluster["common_questions"]
                       for anchor in question["evidence"]}
        for document in data["documents"]:
            if document["document_id"] not in represented or document["status"] != "READY":
                lines += [f"- {document['document_id']}: {document['status']}; {md_text(document.get('error') or '')}"]
        for extraction in data["extractions"]:
            if extraction.get("overflow"):
                lines += [f"- {extraction['document_id']}: more than three questions; overflow retained for review."]
        lines += [""]
    return "\n".join(lines) + "\n"
