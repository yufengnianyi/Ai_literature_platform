"""Create document-level embeddings for a prepared corpus without Chat calls."""

import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from model_client import ModelClient, ModelConfig
from pipeline import embed_members
from storage import digest, read_json, read_jsonl, timestamp, write_json


def load_seed(path, corpus, model_name, dimensions):
    if path is None:
        return []
    path = Path(path)
    rows = read_json(path)
    manifest_path = path.parents[1] / "manifest.json"
    if manifest_path.exists():
        models = read_json(manifest_path).get("config", {}).get("models", {})
        if models.get("embedding_model") != model_name or models.get("actual_dimensions") != dimensions:
            raise ValueError("seed embeddings use a different model or dimension")
    documents = {row["document_id"]: row for row in corpus}
    accepted = []
    for row in rows:
        source = documents.get(row.get("document_id"))
        vector = row.get("vector")
        if source and row.get("content_hash") == source.get("content_hash") and isinstance(vector, list):
            accepted.append(row)
    return accepted


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed-embeddings", type=Path)
    parser.add_argument("--base-url", default=os.environ.get(
        "DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
    ))
    parser.add_argument("--embedding-model", default=os.environ.get(
        "DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v4"
    ))
    parser.add_argument("--dimensions", type=int, default=1024)
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--max-attempts", type=int, default=3)
    args = parser.parse_args()

    corpus = sorted(read_jsonl(args.corpus), key=lambda row: row["document_id"])
    if not corpus:
        raise SystemExit("prepared corpus is empty")
    args.output.mkdir(parents=True, exist_ok=True)
    config = ModelConfig(
        args.base_url,
        "unused-for-embedding-only",
        args.embedding_model,
        args.dimensions,
        args.timeout,
        args.max_attempts,
    )
    client = ModelClient(config, ROOT / "outputs" / ".cache", args.output / "attempts.jsonl")

    seeded = load_seed(args.seed_embeddings, corpus, args.embedding_model, args.dimensions)
    seeded_ids = {row["document_id"] for row in seeded}
    members = [
        {
            "member_id": row["document_id"] + ":abstract",
            **row,
            "embedding_text": (row.get("title") or "") + "\n" + row["abstract"],
        }
        for row in corpus
        if row["document_id"] not in seeded_ids
    ]
    embedded, failures = embed_members(members, client)
    combined = sorted(seeded + embedded, key=lambda row: row["document_id"])

    write_json(args.output / "embeddings.json", combined)
    write_json(args.output / "embedding-failures.json", failures)
    manifest = {
        "kind": "bertopic-embedding-snapshot",
        "created_at": timestamp(),
        "corpus": str(args.corpus.resolve()),
        "corpus_hash": digest(corpus),
        "input_documents": len(corpus),
        "seeded_documents": len(seeded),
        "embedded_documents": len(embedded),
        "failed_documents": len(failures),
        "output_documents": len(combined),
        "model": args.embedding_model,
        "dimensions": args.dimensions,
        "telemetry": client.metrics(),
    }
    write_json(args.output / "manifest.json", manifest)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    raise SystemExit(2 if failures else 0)


if __name__ == "__main__":
    main()
