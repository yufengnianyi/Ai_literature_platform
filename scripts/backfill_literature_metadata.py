#!/usr/bin/env python3
"""
Backfill rag_document publication years and keywords.

Priority:
1. Preserve existing publication_year.
2. Parse local TEI artifacts for dates and keywords.
3. Query OpenAlex/Crossref by DOI, then by title with a similarity guard.
4. Generate fallback keywords from local title/abstract/chunks.

The script writes a CSV report and, with --apply, sends batched UPDATE statements
to the local PostgreSQL container used by this project.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import difflib
import io
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REPORT_DIR = ROOT / "outputs" / "metadata-backfill"
DEFAULT_CACHE_PATH = DEFAULT_REPORT_DIR / "metadata_api_cache.json"
CURRENT_YEAR = dt.datetime.now().year
MIN_YEAR = 1800
MAX_YEAR = CURRENT_YEAR + 1


STOPWORDS = {
    "about", "above", "after", "again", "against", "also", "among", "analysis", "and",
    "another", "any", "are", "article", "because", "been", "before", "being", "between",
    "both", "but", "can", "case", "cells", "could", "data", "did", "different", "does",
    "during", "each", "effect", "effects", "either", "from", "had", "has", "have", "having",
    "how", "however", "into", "its", "may", "more", "most", "not", "only", "other", "our",
    "paper", "plant", "plants", "present", "results", "show", "showed", "shown", "study",
    "studies", "such", "than", "that", "the", "their", "these", "this", "those", "through",
    "treatment", "two", "under", "using", "was", "were", "when", "where", "which", "while",
    "with", "within", "without", "would",
}

DOMAIN_PHRASES = [
    "anti-oomycete activity",
    "antifungal activity",
    "antimicrobial activity",
    "biological control",
    "cell wall",
    "disease resistance",
    "downy mildew",
    "essential oil",
    "fungicidal activity",
    "late blight",
    "mode of action",
    "mycelial growth",
    "plant extract",
    "plant pathogen",
    "plant pathogenic",
    "root rot",
    "seed treatment",
    "zoospore germination",
    "phytophthora",
    "pythium",
    "oomycete",
    "oomycetes",
    "fungicide",
    "fungicides",
    "resistance",
    "elicitor",
]

NOISE_KEYWORD_PATTERNS = [
    "copyright",
    "grant/award",
    "grant award",
    "will notify",
    "volume:",
    "issue:",
    "correspondence",
    "department",
    "university",
    "fax",
    "e-mail",
    "email",
    "telephone",
    "received",
    "accepted",
    "published",
    "doi:",
    "http://",
    "https://",
]


def run_psql_copy(query: str, container: str, database: str, user: str) -> list[dict[str, str]]:
    sql = f"COPY ({query}) TO STDOUT WITH CSV HEADER"
    proc = subprocess.run(
        ["docker", "exec", container, "psql", "-U", user, "-d", database, "-c", sql],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or proc.stdout.strip())
    return list(csv.DictReader(io.StringIO(proc.stdout)))


def run_psql_stdin(sql: str, container: str, database: str, user: str) -> None:
    proc = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", user, "-d", database, "-v", "ON_ERROR_STOP=1", "-f", "-"],
        cwd=ROOT,
        input=sql,
        text=True,
        encoding="utf-8",
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or proc.stdout.strip())


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def normalize_space(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = re.sub(r"\s+", " ", value.replace("\u00a0", " ")).strip()
    return normalized or None


def year_from_text(value: str | None) -> int | None:
    if not value:
        return None
    for match in re.finditer(r"(?<!\d)((?:18|19|20)\d{2})(?!\d)", value):
        year = int(match.group(1))
        if MIN_YEAR <= year <= MAX_YEAR:
            return year
    return None


def safe_publication_date(value: str | None, fallback_year: int | None = None) -> str | None:
    value = normalize_space(value)
    if value:
        iso_match = re.search(r"(?<!\d)((?:18|19|20)\d{2})(?:[-/](\d{1,2})(?:[-/](\d{1,2}))?)?(?!\d)", value)
        if iso_match:
            year = int(iso_match.group(1))
            if MIN_YEAR <= year <= MAX_YEAR:
                parts = [iso_match.group(1)]
                if iso_match.group(2):
                    parts.append(iso_match.group(2).zfill(2))
                if iso_match.group(3):
                    parts.append(iso_match.group(3).zfill(2))
                return "-".join(parts)
        if len(value) <= 64:
            return value
    if fallback_year is not None:
        return str(fallback_year)
    return None


def title_key(value: str | None) -> str:
    value = value or ""
    value = value.lower()
    value = re.sub(r"<[^>]+>", " ", value)
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


def title_similarity(left: str | None, right: str | None) -> float:
    a = title_key(left)
    b = title_key(right)
    if not a or not b:
        return 0.0
    ratio = difflib.SequenceMatcher(None, a, b).ratio()
    if a in b or b in a:
        shorter = min(len(a), len(b))
        longer = max(len(a), len(b))
        if shorter >= 30 and shorter / max(longer, 1) >= 0.65:
            ratio = max(ratio, 0.92)
    return ratio


def doi_from_identifier_text(value: str | None) -> str | None:
    value = normalize_space(value)
    if not value:
        return None
    value = re.sub(r"\.pdf$", "", value, flags=re.IGNORECASE)
    value = value.replace("https://doi.org/", "").replace("http://doi.org/", "")
    value = value.replace("doi:", "").strip()
    slash_match = re.search(r"(10\.\d{4,9}/[-._;()/:A-Z0-9]+)", value, flags=re.IGNORECASE)
    if slash_match:
        return slash_match.group(1).rstrip(".,;:").lower()
    underscore_match = re.search(r"(10\.\d{4,9})[_](\S+)", value, flags=re.IGNORECASE)
    if underscore_match:
        suffix = underscore_match.group(2).rstrip(".,;:")
        if len(suffix) >= 3:
            return f"{underscore_match.group(1)}/{suffix}".lower()
    return None


def doi_from_row(row: dict[str, str]) -> str | None:
    existing = normalize_space(row.get("doi_normalized"))
    if existing:
        return existing
    for field in ("title", "source_filename"):
        candidate = doi_from_identifier_text(row.get(field))
        if candidate:
            return candidate
    return None


def clean_keyword(value: str | None) -> str | None:
    value = normalize_space(value)
    if not value:
        return None
    value = value.strip(" .;,:/-\t\r\n")
    value = normalize_space(value)
    if not value:
        return None
    lowered = value.lower()
    if any(pattern in lowered for pattern in NOISE_KEYWORD_PATTERNS):
        return None
    if len(value) < 2 or len(value) > 120:
        return None
    alpha_count = sum(1 for ch in value if ch.isalpha())
    digit_count = sum(1 for ch in value if ch.isdigit())
    if alpha_count < 2:
        return None
    if digit_count > alpha_count:
        return None
    if len(value.split()) > 14:
        return None
    return value


def split_keyword_text(value: str | None, aggressive: bool = False) -> list[str]:
    value = normalize_space(value)
    if not value:
        return []
    parts = re.split(r"\s*[;•·]\s*|\s+--\s+|\s+-\s*(?=[A-Z])", value)
    if len(parts) == 1 and " - " in value:
        parts = re.split(r"\s+-\s*", value)
    if aggressive or any(len(part) > 80 for part in parts):
        expanded: list[str] = []
        for part in parts:
            if len(part) > 80 and "," in part:
                expanded.extend(re.split(r"\s*,\s*", part))
            else:
                expanded.append(part)
        parts = expanded
    cleaned = []
    for part in parts:
        keyword = clean_keyword(part)
        if keyword:
            cleaned.append(keyword)
    return dedupe_keywords(cleaned)


def dedupe_keywords(values: list[str], limit: int = 12) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        keyword = clean_keyword(value)
        if not keyword:
            continue
        key = re.sub(r"[^a-z0-9]+", "", keyword.lower())
        if key and key not in seen:
            seen.add(key)
            result.append(keyword)
        if len(result) >= limit:
            break
    return result


def parse_tei(path: Path) -> tuple[int | None, str | None, list[str]]:
    if not path.exists() or path.stat().st_size == 0:
        return None, None, []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return None, None, []

    date_candidates: list[str] = []
    keyword_candidates: list[str] = []

    for elem in root.iter():
        name = local_name(elem.tag)
        if name == "date":
            when = elem.attrib.get("when")
            if when:
                date_candidates.append(when)
            text = normalize_space(" ".join(elem.itertext()))
            if text:
                date_candidates.append(text)
        elif name == "keywords":
            terms = [
                normalize_space(" ".join(child.itertext()))
                for child in list(elem)
                if local_name(child.tag) == "term"
            ]
            terms = [term for term in terms if term]
            if terms:
                for term in terms:
                    keyword_candidates.extend(split_keyword_text(term, aggressive=True))
            else:
                keyword_candidates.extend(split_keyword_text(" ".join(elem.itertext()), aggressive=False))

    publication_date = next((normalize_space(value) for value in date_candidates if normalize_space(value)), None)
    publication_year = next((year_from_text(value) for value in date_candidates if year_from_text(value)), None)
    return publication_year, publication_date, dedupe_keywords(keyword_candidates)


def storage_dir_for(row: dict[str, str]) -> Path:
    value = row.get("storage_root")
    if value:
        path = Path(value)
        if path.exists():
            return path
    return ROOT / "data" / "rag" / row["document_id"]


def load_local_text(row: dict[str, str], max_chars: int = 6000) -> str:
    parts = [row.get("title") or "", row.get("abstract_text") or ""]
    if sum(len(part) for part in parts) >= max_chars:
        return "\n".join(parts)[:max_chars]

    jsonl_path = storage_dir_for(row) / "document.jsonl"
    if jsonl_path.exists():
        try:
            with jsonl_path.open("r", encoding="utf-8", errors="ignore") as handle:
                for _, line in zip(range(4), handle):
                    try:
                        payload = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    text = payload.get("text")
                    if isinstance(text, str):
                        parts.append(text)
                    if sum(len(part) for part in parts) >= max_chars:
                        break
        except OSError:
            pass
    return "\n".join(part for part in parts if part)[:max_chars]


def generated_keywords(row: dict[str, str]) -> list[str]:
    text = load_local_text(row)
    if not text:
        return []
    text_lower = text.lower()
    candidates: list[str] = []
    for phrase in DOMAIN_PHRASES:
        if phrase in text_lower:
            candidates.append(phrase)

    species_pattern = re.compile(
        r"\b(Phytophthora|Pythium|Plasmopara|Saprolegnia|Aphanomyces|Peronospora|Bremia|Albugo|Achlya|Fusarium|Alternaria|Botrytis|Rhizoctonia)\s+[a-z][a-z-]{2,}\b"
    )
    candidates.extend(match.group(0) for match in species_pattern.finditer(text))

    words = re.findall(r"[A-Za-z][A-Za-z-]{2,}", text)
    normalized_words = [word.lower().strip("-") for word in words]
    filtered = [word for word in normalized_words if word not in STOPWORDS and len(word) >= 4]

    title_words = set(re.findall(r"[a-z][a-z-]{2,}", (row.get("title") or "").lower()))
    scores: Counter[str] = Counter()
    for n in (1, 2, 3):
        for index in range(0, max(len(filtered) - n + 1, 0)):
            gram = filtered[index:index + n]
            if any(token in STOPWORDS for token in gram):
                continue
            phrase = " ".join(gram)
            if len(phrase) < 4 or len(phrase) > 60:
                continue
            scores[phrase] += 1 + sum(2 for token in gram if token in title_words)

    for phrase, _ in scores.most_common(30):
        if len(phrase.split()) == 1 and scores[phrase] < 3 and phrase not in title_words:
            continue
        candidates.append(phrase)

    return dedupe_keywords(candidates, limit=8)


def http_json(url: str, cache: dict[str, Any], delay_seconds: float) -> dict[str, Any] | None:
    if url in cache:
        return cache[url]
    if delay_seconds > 0:
        time.sleep(delay_seconds)
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "demo_01-literature-metadata-backfill/1.0 (mailto:metadata-backfill@example.invalid)",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            data = json.loads(response.read().decode("utf-8"))
            cache[url] = data
            return data
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            cache[url] = None
            return None
        print(f"HTTP {exc.code}: {url}", file=sys.stderr)
    except Exception as exc:
        print(f"HTTP failed: {url} ({exc})", file=sys.stderr)
    return None


def openalex_by_doi(doi: str, cache: dict[str, Any], delay_seconds: float) -> dict[str, Any] | None:
    doi_url = "https://doi.org/" + doi.strip()
    url = "https://api.openalex.org/works/" + urllib.parse.quote(doi_url, safe="")
    return http_json(url, cache, delay_seconds)


def openalex_by_title(title: str, cache: dict[str, Any], delay_seconds: float) -> dict[str, Any] | None:
    params = urllib.parse.urlencode({"search": title, "per-page": "5"})
    data = http_json("https://api.openalex.org/works?" + params, cache, delay_seconds)
    if not data:
        return None
    best = None
    best_score = 0.0
    for item in data.get("results") or []:
        score = title_similarity(title, item.get("title") or item.get("display_name"))
        if score > best_score:
            best = item
            best_score = score
    if best is not None and best_score >= 0.86:
        best["_match_score"] = best_score
        return best
    return None


def crossref_by_doi(doi: str, cache: dict[str, Any], delay_seconds: float) -> dict[str, Any] | None:
    url = "https://api.crossref.org/works/" + urllib.parse.quote(doi.strip(), safe="")
    data = http_json(url, cache, delay_seconds)
    if data and isinstance(data.get("message"), dict):
        return data["message"]
    return None


def crossref_by_title(title: str, cache: dict[str, Any], delay_seconds: float) -> dict[str, Any] | None:
    params = urllib.parse.urlencode({"query.title": title, "rows": "5"})
    data = http_json("https://api.crossref.org/works?" + params, cache, delay_seconds)
    items = ((data or {}).get("message") or {}).get("items") or []
    best = None
    best_score = 0.0
    for item in items:
        item_title = first_title(item.get("title"))
        score = title_similarity(title, item_title)
        if score > best_score:
            best = item
            best_score = score
    if best is not None and best_score >= 0.86:
        best["_match_score"] = best_score
        return best
    return None


def first_title(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, list) and value:
        first = value[0]
        return first if isinstance(first, str) else None
    return None


def year_from_crossref_date(value: Any) -> int | None:
    if not isinstance(value, dict):
        return None
    date_parts = value.get("date-parts")
    if isinstance(date_parts, list) and date_parts and isinstance(date_parts[0], list) and date_parts[0]:
        try:
            year = int(date_parts[0][0])
        except (TypeError, ValueError):
            return None
        if MIN_YEAR <= year <= MAX_YEAR:
            return year
    return None


def date_from_crossref_date(value: Any) -> str | None:
    if not isinstance(value, dict):
        return None
    date_parts = value.get("date-parts")
    if isinstance(date_parts, list) and date_parts and isinstance(date_parts[0], list):
        parts = [str(part) for part in date_parts[0] if part is not None]
        if parts:
            return "-".join(parts)
    return None


def metadata_from_openalex(item: dict[str, Any]) -> tuple[int | None, str | None, list[str]]:
    year = item.get("publication_year")
    if not isinstance(year, int) or not (MIN_YEAR <= year <= MAX_YEAR):
        year = year_from_text(str(year)) if year is not None else None
    date = item.get("publication_date")
    date = date if isinstance(date, str) and date else None
    keywords: list[str] = []
    for keyword in item.get("keywords") or []:
        if isinstance(keyword, dict):
            name = keyword.get("display_name") or keyword.get("name")
            if isinstance(name, str):
                keywords.append(name)
        elif isinstance(keyword, str):
            keywords.append(keyword)
    for topic in item.get("topics") or []:
        if isinstance(topic, dict):
            name = topic.get("display_name")
            if isinstance(name, str):
                keywords.append(name)
    primary_topic = item.get("primary_topic")
    if isinstance(primary_topic, dict) and isinstance(primary_topic.get("display_name"), str):
        keywords.append(primary_topic["display_name"])
    for concept in item.get("concepts") or []:
        if isinstance(concept, dict) and concept.get("level") in (0, 1, 2):
            name = concept.get("display_name")
            if isinstance(name, str):
                keywords.append(name)
    return year, date, dedupe_keywords(keywords, limit=10)


def metadata_from_crossref(item: dict[str, Any]) -> tuple[int | None, str | None, list[str]]:
    date_fields = [
        "published-print",
        "published-online",
        "published",
        "issued",
        "created",
    ]
    year = None
    date = None
    for field in date_fields:
        value = item.get(field)
        year = year_from_crossref_date(value)
        date = date_from_crossref_date(value)
        if year:
            break
    subjects = item.get("subject") or []
    keywords = [subject for subject in subjects if isinstance(subject, str)]
    return year, date, dedupe_keywords(keywords, limit=8)


def existing_keywords(row: dict[str, str]) -> list[str]:
    raw = row.get("keywords_json") or ""
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return []
    if isinstance(parsed, list):
        return dedupe_keywords([str(value) for value in parsed], limit=20)
    return []


def json_sql(value: Any) -> str:
    return sql_literal(json.dumps(value, ensure_ascii=False, separators=(",", ":"))) + "::jsonb"


def sql_literal(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("'", "''") + "'"


def int_or_none(value: str | None) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except ValueError:
        return None


def load_cache(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_cache(path: Path, cache: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")


def fetch_external(
    row: dict[str, str],
    cache: dict[str, Any],
    delay_seconds: float,
    enable_external: bool,
) -> tuple[int | None, str | None, list[str], str | None, dict[str, Any]]:
    if not enable_external:
        return None, None, [], None, {}
    doi = doi_from_row(row)
    title = normalize_space(row.get("title"))
    attempts: list[tuple[str, dict[str, Any]]] = []

    if doi:
        item = openalex_by_doi(doi, cache, delay_seconds)
        if item:
            year, date, keywords = metadata_from_openalex(item)
            return year, date, keywords, "openalex_doi", {"openalexId": item.get("id")}
        item = crossref_by_doi(doi, cache, delay_seconds)
        if item:
            year, date, keywords = metadata_from_crossref(item)
            return year, date, keywords, "crossref_doi", {"doi": item.get("DOI")}

    if title and len(title_key(title)) >= 20:
        item = openalex_by_title(title, cache, delay_seconds)
        if item:
            year, date, keywords = metadata_from_openalex(item)
            return year, date, keywords, "openalex_title", {
                "openalexId": item.get("id"),
                "matchScore": item.get("_match_score"),
            }
        item = crossref_by_title(title, cache, delay_seconds)
        if item:
            year, date, keywords = metadata_from_crossref(item)
            return year, date, keywords, "crossref_title", {
                "doi": item.get("DOI"),
                "matchScore": item.get("_match_score"),
            }

    return None, None, [], None, dict(attempts)


def filename_year(row: dict[str, str]) -> int | None:
    filename = row.get("source_filename") or ""
    for match in re.finditer(r"(?<!\d)((?:19|20)\d{2})(?:\d{2}){0,2}(?!\d)", filename):
        year = int(match.group(1))
        if MIN_YEAR <= year <= MAX_YEAR:
            return year
    return None


def build_updates(rows: list[dict[str, Any]], only_missing_keywords: bool) -> str:
    statements = ["BEGIN;"]
    for row in rows:
        assignments: list[str] = []
        if row["set_year"]:
            assignments.append(f"publication_year = {row['year']}")
        if row["set_date"]:
            assignments.append(f"publication_date = {sql_literal(row['date'])}")
        if row["set_keywords"]:
            assignments.append(f"keywords_json = {json_sql(row['keywords'])}")
        assignments.append(f"metadata_enrichment_json = {json_sql(row['enrichment'])}")
        assignments.append("metadata_enriched_at = CURRENT_TIMESTAMP")
        assignments.append("updated_at = CURRENT_TIMESTAMP")
        sql = "UPDATE rag_document SET " + ", ".join(assignments)
        sql += f" WHERE document_id = {sql_literal(row['document_id'])}::uuid;"
        statements.append(sql)
    statements.append("COMMIT;")
    return "\n".join(statements) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="write updates to PostgreSQL")
    parser.add_argument("--container", default=os.environ.get("POSTGRES_CONTAINER", "ai-code-postgres"))
    parser.add_argument("--db", default=os.environ.get("POSTGRES_DB", "demo_01"))
    parser.add_argument("--user", default=os.environ.get("POSTGRES_USER", "demo_01"))
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--no-external", action="store_true")
    parser.add_argument("--delay", type=float, default=0.05, help="delay before uncached HTTP calls")
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE_PATH)
    parser.add_argument("--force-keywords", action="store_true", help="overwrite non-empty keywords_json")
    args = parser.parse_args()

    args.report_dir.mkdir(parents=True, exist_ok=True)
    cache = load_cache(args.cache)
    enable_external = not args.no_external

    where_limit = f" LIMIT {args.limit}" if args.limit and args.limit > 0 else ""
    query = f"""
        SELECT document_id::text,
               coalesce(title, '') AS title,
               coalesce(abstract_text, '') AS abstract_text,
               coalesce(doi_normalized, '') AS doi_normalized,
               coalesce(publication_date, '') AS publication_date,
               publication_year::text AS publication_year,
               coalesce(source_filename, '') AS source_filename,
               coalesce(storage_root, '') AS storage_root,
               coalesce(keywords_json::text, '[]') AS keywords_json
        FROM rag_document
        WHERE status = 'COMPLETED'
          AND duplicate_of_document_id IS NULL
        ORDER BY document_id
        {where_limit}
    """
    documents = run_psql_copy(query, args.container, args.db, args.user)
    print(f"Loaded {len(documents)} canonical completed documents")

    updates: list[dict[str, Any]] = []
    report_rows: list[dict[str, Any]] = []
    stats = Counter()
    now = dt.datetime.now(dt.UTC).isoformat()

    for index, row in enumerate(documents, start=1):
        if index % 50 == 0:
            print(f"Processed {index}/{len(documents)}")
            save_cache(args.cache, cache)

        existing_year = int_or_none(row.get("publication_year"))
        old_keywords = existing_keywords(row)
        should_fill_keywords = args.force_keywords or not old_keywords

        storage_dir = storage_dir_for(row)
        tei_year = None
        tei_date = None
        tei_keywords: list[str] = []
        for tei_name in ("header.tei.xml", "document.tei.xml"):
            year, date, keywords = parse_tei(storage_dir / tei_name)
            tei_year = tei_year or year
            tei_date = tei_date or date
            if keywords and not tei_keywords:
                tei_keywords = keywords

        final_year = existing_year
        final_date = safe_publication_date(row.get("publication_date"), existing_year)
        final_keywords = old_keywords
        year_source = "existing" if existing_year else None
        keyword_source = "existing" if old_keywords else None
        external_meta: dict[str, Any] = {}

        if final_year is None and tei_year is not None:
            final_year = tei_year
            final_date = final_date or safe_publication_date(tei_date, final_year)
            year_source = "tei"

        if should_fill_keywords and tei_keywords:
            final_keywords = tei_keywords
            keyword_source = "tei"

        needs_external = final_year is None or (should_fill_keywords and not final_keywords)
        external_year = None
        external_date = None
        external_keywords: list[str] = []
        external_source = None
        if needs_external:
            external_year, external_date, external_keywords, external_source, external_meta = fetch_external(
                row,
                cache,
                args.delay,
                enable_external,
            )
            if final_year is None and external_year is not None:
                final_year = external_year
                final_date = final_date or safe_publication_date(external_date, final_year)
                year_source = external_source
            if should_fill_keywords and not final_keywords and external_keywords:
                final_keywords = external_keywords
                keyword_source = external_source

        if final_year is None:
            fallback_year = filename_year(row)
            if fallback_year is not None:
                final_year = fallback_year
                year_source = "source_filename"

        if should_fill_keywords and not final_keywords:
            generated = generated_keywords(row)
            if generated:
                final_keywords = generated
                keyword_source = "generated"

        set_year = existing_year is None and final_year is not None
        set_date = not normalize_space(row.get("publication_date")) and final_date is not None and len(final_date) <= 64
        set_keywords = should_fill_keywords and bool(final_keywords)

        enrichment = {
            "backfillScript": "scripts/backfill_literature_metadata.py",
            "backfilledAt": now,
            "yearSource": year_source,
            "keywordSource": keyword_source,
            "resolvedDoi": doi_from_row(row),
            "teiKeywordCount": len(tei_keywords),
            "externalSource": external_source,
            "external": external_meta,
        }

        if set_year or set_date or set_keywords:
            updates.append({
                "document_id": row["document_id"],
                "year": final_year,
                "date": final_date,
                "keywords": final_keywords,
                "set_year": set_year,
                "set_date": set_date,
                "set_keywords": set_keywords,
                "enrichment": enrichment,
            })

        stats[f"year:{year_source or 'missing'}"] += 1
        stats[f"keywords:{keyword_source or 'missing'}"] += 1

        report_rows.append({
            "document_id": row["document_id"],
            "title": row.get("title") or "",
            "doi_normalized": row.get("doi_normalized") or "",
            "old_publication_year": existing_year or "",
            "new_publication_year": final_year or "",
            "year_source": year_source or "",
            "keyword_source": keyword_source or "",
            "keyword_count": len(final_keywords),
            "keywords": "; ".join(final_keywords),
            "updated": "yes" if (set_year or set_date or set_keywords) else "no",
        })

    save_cache(args.cache, cache)

    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    report_path = args.report_dir / f"literature_metadata_backfill_report_{timestamp}.csv"
    with report_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(report_rows[0].keys()) if report_rows else [])
        writer.writeheader()
        writer.writerows(report_rows)

    summary_path = args.report_dir / f"literature_metadata_backfill_summary_{timestamp}.json"
    summary = {
        "loaded": len(documents),
        "updates": len(updates),
        "apply": args.apply,
        "stats": dict(sorted(stats.items())),
        "report": str(report_path),
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.apply and updates:
        run_psql_stdin(build_updates(updates, only_missing_keywords=not args.force_keywords), args.container, args.db, args.user)

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
