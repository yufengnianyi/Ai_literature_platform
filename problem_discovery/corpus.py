"""Read existing artifacts without modifying the source literature store."""

import json
import random
import re
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

from storage import digest, timestamp, write_json, write_jsonl


def clean_doi(value):
    return re.sub(r"^(?:https?://(?:dx\.)?doi\.org/|doi:\s*)", "", value.strip(), flags=re.I).lower()


def content_hash(row):
    return digest({key: row.get(key) for key in ("document_id", "title", "abstract", "doi", "year")})


def normalize_record(raw, source):
    if not isinstance(raw, dict):
        raise ValueError("record must be an object")
    row = {
        "document_id": raw.get("document_id", raw.get("documentId")),
        "title": raw.get("title") or "",
        "abstract": raw.get("abstract", raw.get("abstractText", raw.get("abstract_text", ""))) or "",
        "doi": raw.get("doi", raw.get("doiNormalized", raw.get("doi_normalized", ""))) or "",
        "year": raw.get("year", raw.get("publicationYear", raw.get("publication_year"))),
        "source": source,
    }
    if not isinstance(row["document_id"], str) or not row["document_id"].strip():
        raise ValueError("document_id must be a non-empty string")
    if any(not isinstance(row[key], str) for key in ("title", "abstract", "doi")):
        raise ValueError("title, abstract, and DOI must be strings")
    row["document_id"] = row["document_id"].strip()
    row["doi"] = clean_doi(row["doi"])
    if row["year"] is not None:
        try:
            row["year"] = int(row["year"])
        except (ValueError, TypeError):
            row["year"] = None
    row["duplicate_of"] = raw.get("duplicate_of", raw.get("duplicateOfDocumentId", raw.get("duplicate_of_document_id")))
    row["content_hash"] = content_hash(row)
    return row


def read_tei(path):
    root = ET.parse(path).getroot()
    header = root.find("./{*}teiHeader")
    if header is None:
        raise ValueError("TEI has no teiHeader")

    def text_at(expression):
        element = header.find(expression)
        return " ".join("".join(element.itertext()).split()) if element is not None else ""

    title = text_at("./{*}fileDesc/{*}titleStmt/{*}title") or text_at(
        "./{*}fileDesc/{*}sourceDesc/{*}biblStruct/{*}analytic/{*}title")
    abstract = text_at("./{*}profileDesc/{*}abstract")
    doi = ""
    for element in header.findall("./{*}fileDesc/{*}sourceDesc/{*}biblStruct/{*}idno"):
        if element.get("type", "").lower() == "doi":
            doi = "".join(element.itertext()).strip()
            break
    date = header.find("./{*}fileDesc/{*}sourceDesc/{*}biblStruct/{*}monogr/{*}imprint/{*}date")
    date_text = "" if date is None else date.get("when", "") + " " + "".join(date.itertext())
    match = re.search(r"\b(?:18|19|20|21)\d{2}\b", date_text)
    return {"title": title, "abstract": abstract, "doi": doi,
            "year": int(match.group()) if match else None}


def artifact_record(directory):
    merged = {"document_id": directory.name}
    origins, warnings, existing = {}, [], False
    for name in ("header.tei.xml", "document.tei.xml"):
        path = directory / name
        if not path.is_file():
            continue
        existing = True
        try:
            values = read_tei(path)
            for key, value in values.items():
                if not merged.get(key) and value:
                    merged[key] = value
                    origins[key] = str(path.resolve())
        except (ET.ParseError, ValueError, OSError) as error:
            warnings.append(f"{name}: {type(error).__name__}")
        if all(merged.get(key) for key in ("title", "abstract", "doi", "year")):
            break
    row = normalize_record(merged, origins)
    row["warnings"] = warnings
    if not existing:
        row["input_error"] = "MISSING_ARTIFACT"
    elif warnings and not row["abstract"]:
        row["input_error"] = "READ_FAILED"
    return row


def prepare_corpus(output, artifact_root=None, input_jsonl=None, ids_file=None, limit=100, seed=42):
    output = Path(output)
    if (output / "manifest.json").exists():
        raise ValueError("preparation output already exists; choose a new directory")
    if limit < 0:
        raise ValueError("limit must be non-negative (0 means all)")
    ids = None
    if ids_file:
        ids = sorted({line.strip() for line in Path(ids_file).read_text(encoding="utf-8-sig").splitlines()
                      if line.strip()})
    candidates, excluded = [], []
    if input_jsonl:
        for number, line in enumerate(Path(input_jsonl).read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line.strip():
                continue
            try:
                row = normalize_record(json.loads(line), {"jsonl": str(Path(input_jsonl).resolve()), "line": number})
                if ids is None or row["document_id"] in ids:
                    candidates.append(row)
                else:
                    excluded.append({**row, "status": "OUTSIDE_ID_LIST"})
            except (ValueError, TypeError) as error:
                excluded.append({"document_id": f"input-line-{number}", "status": "INPUT_INVALID",
                                 "error": str(error)[:300]})
        found = {row["document_id"] for row in candidates}
        for missing in set(ids or []) - found:
            excluded.append({"document_id": missing, "status": "MISSING_DOCUMENT"})
    else:
        root = Path(artifact_root).resolve()
        if not root.is_dir():
            raise ValueError(f"artifact root does not exist: {root}")
        directories = sorted(path for path in root.iterdir() if path.is_dir()) if ids is None else []
        for identifier in ids or []:
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", identifier):
                raise ValueError("artifact document IDs must be directory names, not paths")
            directory = root / identifier
            if directory.is_dir():
                directories.append(directory)
            else:
                excluded.append({"document_id": identifier, "status": "MISSING_DOCUMENT"})
        candidates = [artifact_record(directory) for directory in directories]

    eligible, seen_ids, seen_dois, seen_texts = [], set(), {}, {}
    for row in sorted(candidates, key=lambda item: item["document_id"]):
        identifier = row["document_id"]
        text_key = digest([row["title"].strip().casefold(), " ".join(row["abstract"].split()).casefold()])
        duplicate = row["duplicate_of"] or seen_dois.get(row["doi"]) or seen_texts.get(text_key)
        if identifier in seen_ids:
            status = "DUPLICATE_ID"
        elif duplicate:
            status = "DUPLICATE"
            row["duplicate_of"] = duplicate
        elif row.get("input_error"):
            status = row["input_error"]
        elif not row["abstract"].strip():
            status = "MISSING_ABSTRACT"
        else:
            status = "READY"
        if status != "READY":
            excluded.append({**row, "status": status})
            continue
        seen_ids.add(identifier)
        if row["doi"]:
            seen_dois[row["doi"]] = identifier
        seen_texts[text_key] = identifier
        eligible.append({**row, "status": "READY"})
    selected = eligible if limit == 0 or len(eligible) <= limit else random.Random(seed).sample(eligible, limit)
    selected = sorted(selected, key=lambda row: row["document_id"])
    selected_ids = {row["document_id"] for row in selected}
    excluded.extend({**row, "status": "NOT_SELECTED"} for row in eligible if row["document_id"] not in selected_ids)
    manifest = {"kind": "prepared-corpus", "created_at": timestamp(), "seed": seed, "limit": limit,
                "selected_count": len(selected), "eligible_count": len(eligible),
                "excluded_counts": dict(Counter(row["status"] for row in excluded)),
                "corpus_hash": digest(selected), "source": str(Path(input_jsonl or artifact_root).resolve())}
    write_jsonl(output / "corpus.jsonl", selected)
    write_jsonl(output / "excluded.jsonl", excluded)
    write_json(output / "manifest.json", manifest)
    return manifest
