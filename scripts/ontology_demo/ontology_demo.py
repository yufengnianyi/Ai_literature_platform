#!/usr/bin/env python3
"""
Minimal human-in-the-loop ontology construction demo.

The demo intentionally uses JSON/CSV files instead of a database so the full
candidate -> review -> store loop can be tested locally before adding tables.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


ENTITY_TYPES = {"pathogen", "compound", "disease", "assay", "target", "host"}
RELATION_TYPES = {"has_activity_against", "evaluated_by", "causes"}

BUILTIN_TERMS = {
    "compound": [
        "oxathiapiprolin",
        "metalaxyl",
        "cymoxanil",
        "dimethomorph",
        "fluopicolide",
        "mancozeb",
        "cinnamaldehyde",
        "eugenol",
        "carvacrol",
        "thymol",
        "lauric acid",
        "glycerol monolaurate",
    ],
    "disease": [
        "late blight",
        "downy mildew",
        "damping-off",
        "root rot",
        "black pod disease",
    ],
    "assay": [
        "EC50",
        "IC50",
        "mycelial growth inhibition",
        "mycelial growth",
        "zoospore germination",
        "zoospore germination assay",
        "in vitro assay",
        "greenhouse assay",
    ],
    "target": [
        "OSBP",
        "oxysterol binding protein",
        "cellulose synthase",
        "RNA polymerase",
    ],
}

PATHOGEN_RE = re.compile(
    r"\b(?:Phytophthora|Pythium|Plasmopara|Peronospora|Aphanomyces|Saprolegnia)\s+[a-z][a-z-]+\b"
)
ACTIVITY_RE = re.compile(
    r"\b(activity|activities|active|inhibit|inhibited|inhibitory|inhibition|effective|efficacy|"
    r"EC50|IC50|mycelial growth|zoospore germination|against)\b",
    re.IGNORECASE,
)
CAUSE_RE = re.compile(r"\b(cause|causes|caused by|causal agent|responsible for)\b", re.IGNORECASE)


@dataclass(frozen=True)
class EntityCandidate:
    key: str
    canonical_name: str
    entity_type: str
    aliases: tuple[str, ...]
    evidence_text: str
    paper_id: str
    paper_title: str
    confidence: float


@dataclass(frozen=True)
class RelationCandidate:
    key: str
    subject_key: str
    subject_name: str
    relation_type: str
    object_key: str
    object_name: str
    evidence_text: str
    paper_id: str
    paper_title: str
    confidence: float


def normalize_text(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")
    return normalized or "unknown"


def entity_key(entity_type: str, canonical_name: str) -> str:
    return f"{entity_type}:{normalize_text(canonical_name)}"


def relation_key(subject_key: str, relation_type: str, object_key: str) -> str:
    raw = f"{subject_key}|{relation_type}|{object_key}"
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12]
    return f"relation:{digest}"


def load_store(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        store = json.load(f)
    store.setdefault("entities", [])
    store.setdefault("relations", [])
    return store


def write_store(path: Path, store: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(store, f, ensure_ascii=False, indent=2)
        f.write("\n")


def load_papers(path: Path) -> list[dict]:
    papers = []
    with path.open("r", encoding="utf-8") as f:
        for line_number, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            paper = json.loads(line)
            paper.setdefault("paper_id", paper.get("documentId") or f"line-{line_number}")
            paper.setdefault("title", "")
            paper.setdefault("abstract", "")
            papers.append(paper)
    return papers


def sentence_split(text: str) -> list[str]:
    text = re.sub(r"\s*\n+\s*", ". ", text)
    parts = re.split(r"(?<=[.!?])\s+", text)
    return [part.strip() for part in parts if part.strip()]


def compile_known_aliases(store: dict) -> list[tuple[re.Pattern, str, str, str]]:
    aliases = []
    for entity in store.get("entities", []):
        terms = [entity["canonical_name"], *entity.get("aliases", [])]
        for term in terms:
            if not term:
                continue
            pattern = re.compile(rf"\b{re.escape(term)}\b", re.IGNORECASE)
            aliases.append((pattern, entity["key"], entity["canonical_name"], entity["entity_type"]))
    return aliases


def add_entity_candidate(candidates: dict[str, EntityCandidate], candidate: EntityCandidate) -> None:
    existing = candidates.get(candidate.key)
    if existing is None or candidate.confidence > existing.confidence:
        candidates[candidate.key] = candidate


def extract_entities(paper: dict, store: dict) -> dict[str, EntityCandidate]:
    title = paper.get("title", "")
    abstract = paper.get("abstract", "")
    fallback = paper.get("researchFocus") or paper.get("reason") or ""
    text = "\n".join(part for part in [title, abstract, fallback] if part)
    candidates: dict[str, EntityCandidate] = {}
    paper_id = str(paper.get("paper_id"))

    for sentence in sentence_split(text):
        for pattern, key, canonical_name, entity_type in compile_known_aliases(store):
            for match in pattern.finditer(sentence):
                add_entity_candidate(
                    candidates,
                    EntityCandidate(
                        key=key,
                        canonical_name=canonical_name,
                        entity_type=entity_type,
                        aliases=(match.group(0),),
                        evidence_text=sentence,
                        paper_id=paper_id,
                        paper_title=title,
                        confidence=0.95,
                    ),
                )

        for match in PATHOGEN_RE.finditer(sentence):
            name = match.group(0)
            epithet = name.rsplit(" ", 1)[-1].lower()
            if epithet in {"species", "spp", "isolates"}:
                continue
            add_entity_candidate(
                candidates,
                EntityCandidate(
                    key=entity_key("pathogen", name),
                    canonical_name=name,
                    entity_type="pathogen",
                    aliases=(),
                    evidence_text=sentence,
                    paper_id=paper_id,
                    paper_title=title,
                    confidence=0.9,
                ),
            )

        for entity_type, terms in BUILTIN_TERMS.items():
            for term in terms:
                if re.search(rf"\b{re.escape(term)}\b", sentence, re.IGNORECASE):
                    canonical = term if term.isupper() else term[0].upper() + term[1:]
                    add_entity_candidate(
                        candidates,
                        EntityCandidate(
                            key=entity_key(entity_type, canonical),
                            canonical_name=canonical,
                            entity_type=entity_type,
                            aliases=(term,),
                            evidence_text=sentence,
                            paper_id=paper_id,
                            paper_title=title,
                            confidence=0.86,
                        ),
                    )
    return candidates


def entities_in_sentence(sentence: str, entities: Iterable[EntityCandidate]) -> list[EntityCandidate]:
    found = []
    for entity in entities:
        terms = [entity.canonical_name, *entity.aliases]
        if any(re.search(rf"\b{re.escape(term)}\b", sentence, re.IGNORECASE) for term in terms if term):
            found.append(entity)
    return found


def extract_relations(paper: dict, entity_candidates: dict[str, EntityCandidate]) -> dict[str, RelationCandidate]:
    title = paper.get("title", "")
    abstract = paper.get("abstract", "")
    fallback = paper.get("researchFocus") or paper.get("reason") or ""
    text = "\n".join(part for part in [title, abstract, fallback] if part)
    paper_id = str(paper.get("paper_id"))
    relations: dict[str, RelationCandidate] = {}

    for sentence in sentence_split(text):
        sentence_entities = entities_in_sentence(sentence, entity_candidates.values())
        compounds = [e for e in sentence_entities if e.entity_type == "compound"]
        pathogens = [e for e in sentence_entities if e.entity_type == "pathogen"]
        diseases = [e for e in sentence_entities if e.entity_type == "disease"]
        assays = [e for e in sentence_entities if e.entity_type == "assay"]

        if ACTIVITY_RE.search(sentence):
            for compound in compounds:
                for pathogen in pathogens:
                    key = relation_key(compound.key, "has_activity_against", pathogen.key)
                    relations[key] = RelationCandidate(
                        key=key,
                        subject_key=compound.key,
                        subject_name=compound.canonical_name,
                        relation_type="has_activity_against",
                        object_key=pathogen.key,
                        object_name=pathogen.canonical_name,
                        evidence_text=sentence,
                        paper_id=paper_id,
                        paper_title=title,
                        confidence=0.82,
                    )
                for assay in assays:
                    key = relation_key(compound.key, "evaluated_by", assay.key)
                    relations[key] = RelationCandidate(
                        key=key,
                        subject_key=compound.key,
                        subject_name=compound.canonical_name,
                        relation_type="evaluated_by",
                        object_key=assay.key,
                        object_name=assay.canonical_name,
                        evidence_text=sentence,
                        paper_id=paper_id,
                        paper_title=title,
                        confidence=0.76,
                    )

        if CAUSE_RE.search(sentence):
            for pathogen in pathogens:
                for disease in diseases:
                    key = relation_key(pathogen.key, "causes", disease.key)
                    relations[key] = RelationCandidate(
                        key=key,
                        subject_key=pathogen.key,
                        subject_name=pathogen.canonical_name,
                        relation_type="causes",
                        object_key=disease.key,
                        object_name=disease.canonical_name,
                        evidence_text=sentence,
                        paper_id=paper_id,
                        paper_title=title,
                        confidence=0.8,
                    )
    return relations


def priority_for_entity(entity: EntityCandidate) -> int:
    if entity.entity_type in {"compound", "pathogen"}:
        return 90
    if entity.entity_type in {"assay", "disease"}:
        return 70
    return 50


def priority_for_relation(relation: RelationCandidate) -> int:
    if relation.relation_type == "has_activity_against":
        return 100
    if relation.relation_type == "causes":
        return 75
    return 65


def build_review(args: argparse.Namespace) -> None:
    store = load_store(Path(args.store))
    papers = load_papers(Path(args.papers))
    existing_entities = {entity["key"] for entity in store.get("entities", [])}
    existing_relations = {relation["key"] for relation in store.get("relations", [])}

    entity_candidates: dict[str, EntityCandidate] = {}
    relation_candidates: dict[str, RelationCandidate] = {}
    for paper in papers:
        extracted = extract_entities(paper, store)
        entity_candidates.update(extracted)
        relation_candidates.update(extract_relations(paper, extracted))

    rows = []
    for candidate in sorted(entity_candidates.values(), key=lambda item: (-priority_for_entity(item), item.key)):
        if candidate.key in existing_entities:
            continue
        rows.append(
            {
                "candidate_id": candidate.key,
                "candidate_kind": "entity",
                "entity_key": candidate.key,
                "canonical_name": candidate.canonical_name,
                "entity_type": candidate.entity_type,
                "aliases": "; ".join(sorted(set(candidate.aliases))),
                "subject_key": "",
                "subject": "",
                "relation_type": "",
                "object_key": "",
                "object": "",
                "evidence_text": candidate.evidence_text,
                "paper_id": candidate.paper_id,
                "paper_title": candidate.paper_title,
                "confidence": f"{candidate.confidence:.2f}",
                "priority": priority_for_entity(candidate),
                "decision": "",
                "review_note": "",
            }
        )

    for candidate in sorted(relation_candidates.values(), key=lambda item: (-priority_for_relation(item), item.key)):
        if candidate.key in existing_relations:
            continue
        rows.append(
            {
                "candidate_id": candidate.key,
                "candidate_kind": "relation",
                "entity_key": "",
                "canonical_name": "",
                "entity_type": "",
                "aliases": "",
                "subject_key": candidate.subject_key,
                "subject": candidate.subject_name,
                "relation_type": candidate.relation_type,
                "object_key": candidate.object_key,
                "object": candidate.object_name,
                "evidence_text": candidate.evidence_text,
                "paper_id": candidate.paper_id,
                "paper_title": candidate.paper_title,
                "confidence": f"{candidate.confidence:.2f}",
                "priority": priority_for_relation(candidate),
                "decision": "",
                "review_note": "",
            }
        )

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "candidate_id",
        "candidate_kind",
        "entity_key",
        "canonical_name",
        "entity_type",
        "aliases",
        "subject_key",
        "subject",
        "relation_type",
        "object_key",
        "object",
        "evidence_text",
        "paper_id",
        "paper_title",
        "confidence",
        "priority",
        "decision",
        "review_note",
    ]
    with out.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} review candidates to {out}")


def append_evidence(target: dict, row: dict) -> None:
    evidence = target.setdefault("evidence", [])
    mention = {
        "paper_id": row["paper_id"],
        "paper_title": row["paper_title"],
        "evidence_text": row["evidence_text"],
    }
    if mention not in evidence:
        evidence.append(mention)


def apply_review(args: argparse.Namespace) -> None:
    store = load_store(Path(args.store))
    with Path(args.review).open("r", encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))

    entity_by_key = {entity["key"]: entity for entity in store.get("entities", [])}
    relation_by_key = {relation["key"]: relation for relation in store.get("relations", [])}
    approved = [row for row in rows if row.get("decision", "").strip().lower() == "approved"]

    added_entities = 0
    added_relations = 0
    for row in approved:
        if row["candidate_kind"] != "entity":
            continue
        if row["entity_type"] not in ENTITY_TYPES:
            raise ValueError(f"Unsupported entity_type: {row['entity_type']}")
        key = row["entity_key"]
        aliases = [alias.strip() for alias in row.get("aliases", "").split(";") if alias.strip()]
        entity = entity_by_key.get(key)
        if entity is None:
            entity = {
                "key": key,
                "canonical_name": row["canonical_name"],
                "entity_type": row["entity_type"],
                "aliases": aliases,
                "definition": "",
                "status": "approved",
                "evidence": [],
            }
            store["entities"].append(entity)
            entity_by_key[key] = entity
            added_entities += 1
        else:
            entity["aliases"] = sorted(set(entity.get("aliases", []) + aliases))
        append_evidence(entity, row)

    for row in approved:
        if row["candidate_kind"] != "relation":
            continue
        if row["relation_type"] not in RELATION_TYPES:
            raise ValueError(f"Unsupported relation_type: {row['relation_type']}")
        if row["subject_key"] not in entity_by_key or row["object_key"] not in entity_by_key:
            print(f"Skipped relation with missing entity: {row['candidate_id']}")
            continue
        key = row["candidate_id"]
        relation = relation_by_key.get(key)
        if relation is None:
            relation = {
                "key": key,
                "subject_key": row["subject_key"],
                "relation_type": row["relation_type"],
                "object_key": row["object_key"],
                "status": "approved",
                "evidence": [],
            }
            store["relations"].append(relation)
            relation_by_key[key] = relation
            added_relations += 1
        append_evidence(relation, row)

    store["summary"] = {
        "entity_count": len(store["entities"]),
        "relation_count": len(store["relations"]),
    }
    out = Path(args.out)
    write_store(out, store)
    print(f"Added {added_entities} entities and {added_relations} relations to {out}")


def auto_approve_review(queue_path: Path, decisions_path: Path, min_priority: int) -> None:
    with queue_path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        fieldnames = reader.fieldnames or []
    for row in rows:
        row["decision"] = "approved" if int(row["priority"]) >= min_priority else "rejected"
        row["review_note"] = "auto-approved for local demo" if row["decision"] == "approved" else "below demo priority"
    with decisions_path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def run_demo(args: argparse.Namespace) -> None:
    base = Path(args.output_dir)
    base.mkdir(parents=True, exist_ok=True)
    script_dir = Path(__file__).resolve().parent
    store_path = base / "ontology_store.initial.json"
    queue_path = base / "review_queue.csv"
    decisions_path = base / "review_decisions.auto.csv"
    updated_store_path = base / "ontology_store.reviewed.json"

    shutil.copyfile(script_dir / "ontology_store.json", store_path)
    build_review(
        argparse.Namespace(
            papers=str(script_dir / "sample_papers.jsonl"),
            store=str(store_path),
            out=str(queue_path),
        )
    )
    auto_approve_review(queue_path, decisions_path, min_priority=args.min_priority)
    apply_review(
        argparse.Namespace(
            store=str(store_path),
            review=str(decisions_path),
            out=str(updated_store_path),
        )
    )
    print(f"Demo output directory: {base}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Ontology construction demo")
    subparsers = parser.add_subparsers(dest="command", required=True)

    build = subparsers.add_parser("build-review", help="extract candidates into a review CSV")
    build.add_argument("--papers", required=True, help="JSONL papers with title/abstract fields")
    build.add_argument("--store", required=True, help="current ontology_store.json")
    build.add_argument("--out", required=True, help="review queue CSV path")
    build.set_defaults(func=build_review)

    apply = subparsers.add_parser("apply-review", help="apply approved review CSV rows into the ontology store")
    apply.add_argument("--store", required=True, help="current ontology_store.json")
    apply.add_argument("--review", required=True, help="review CSV with decision=approved rows")
    apply.add_argument("--out", required=True, help="updated ontology_store.json")
    apply.set_defaults(func=apply_review)

    demo = subparsers.add_parser("demo", help="run a self-contained sample")
    demo.add_argument("--output-dir", default="outputs/ontology-demo", help="demo output directory")
    demo.add_argument("--min-priority", type=int, default=75, help="auto-approval threshold for demo only")
    demo.set_defaults(func=run_demo)
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
