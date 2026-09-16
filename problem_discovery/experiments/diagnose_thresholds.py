"""Offline development diagnostics, not an automatic threshold selection rule."""

import argparse
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from clustering import cluster_members
from storage import digest, read_json, timestamp, write_json, write_text


def analyze_members(members, thresholds):
    thresholds = sorted(set(thresholds))
    if not thresholds or any(not 0 < value <= 2 for value in thresholds):
        raise ValueError("provide finite thresholds in (0, 2]")
    # Reuse production validation and exactly the same clustering algorithm.
    cluster_members(members, thresholds[0])
    members = sorted(members, key=lambda row: row["member_id"])
    documents = {row["document_id"] for row in members}
    pairs = []
    if members:
        vectors = np.asarray([row["vector"] for row in members], dtype=float)
        vectors /= np.linalg.norm(vectors, axis=1)[:, None]
        distances = 1 - np.clip(vectors @ vectors.T, -1, 1)
        for i, left in enumerate(members):
            for j in range(i + 1, len(members)):
                right = members[j]
                pairs.append({"left": left["member_id"], "right": right["member_id"],
                              "left_document": left["document_id"], "right_document": right["document_id"],
                              "left_title": left["title"], "right_title": right["title"],
                              "left_question": left.get("question"), "right_question": right.get("question"),
                              "same_document": left["document_id"] == right["document_id"],
                              "distance": float(distances[i, j])})
    cross_pairs = sorted((p for p in pairs if not p["same_document"]), key=lambda p: p["distance"])
    within_pairs = [p for p in pairs if p["same_document"]]
    rows = []
    for threshold in thresholds:
        groups = cluster_members(members, threshold)
        cross = [group for group in groups if group["document_count"] > 1]
        linked_docs = {m["document_id"] for g in cross for m in g["members"]}
        rows.append({
            "threshold": threshold, "clusters": len(groups),
            "single_member_clusters": sum(len(g["members"]) == 1 for g in groups),
            "single_document_clusters": sum(g["document_count"] == 1 for g in groups),
            "cross_document_clusters": len(cross), "documents_in_cross_clusters": len(linked_docs),
            "largest_document_count": max((g["document_count"] for g in groups), default=0),
            "groups": [{"document_ids": sorted({m["document_id"] for m in g["members"]}),
                        "member_ids": [m["member_id"] for m in g["members"]]} for g in groups],
        })
    return {"member_count": len(members), "embedded_document_count": len(documents),
            "minimum_cross_document_distance": cross_pairs[0]["distance"] if cross_pairs else None,
            "minimum_within_document_distance": min((p["distance"] for p in within_pairs), default=None),
            "nearest_cross_document_pairs": cross_pairs[:10], "thresholds": rows}


def render_report(result):
    lines = ["# Offline Threshold Diagnostics", "", f"Backend: {result['backend']}", "",
             "Development analysis only. No model calls, no expert scores, no selected best threshold.",
             "Pairwise nearest distance is not the average-linkage cluster merge distance.",
             "Counts include available vectors only; see input counts and missing documents below.", ""]
    for mode, data in result["modes"].items():
        lines += [f"## {mode}", "", f"Members: {data['member_count']}; embedded documents: "
                  f"{data['embedded_document_count']}/{result['input_document_count']}.",
                  f"Documents without vectors: {', '.join(data['documents_without_vectors']) or 'none'}.",
                  f"Minimum cross-document cosine distance: {data['minimum_cross_document_distance']}", "",
                  "| Threshold | Groups | Single-member groups | Single-document groups | Cross-document groups | Documents in cross groups | Largest group (documents) |",
                  "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
        for row in data["thresholds"]:
            lines.append("| " + " | ".join(str(row[key]) for key in (
                "threshold", "clusters", "single_member_clusters", "single_document_clusters",
                "cross_document_clusters", "documents_in_cross_clusters", "largest_document_count")) + " |")
        lines += ["", "### Nearest Cross-Document Pairs", ""]
        for pair in data["nearest_cross_document_pairs"]:
            lines += [f"- {pair['distance']:.6f}: {pair['left']} / {pair['right']}",
                      f"  {pair['left_title']} / {pair['right_title']}"]
            if pair["left_question"]:
                lines.append(f"  {pair['left_question']} / {pair['right_question']}")
        lines.append("")
    return "\n".join(lines)


def diagnose_run(run_dir, output_dir, thresholds):
    run_dir, output_dir = Path(run_dir).resolve(), Path(output_dir).resolve()
    if output_dir == run_dir or output_dir.exists():
        raise ValueError("choose a new diagnostic output directory")
    result = read_json(run_dir / "results.json")
    manifest = read_json(run_dir / "manifest.json")
    expected_ids = {row["document_id"] for row in result["corpus"]}
    analysis = {"kind": "offline-development-diagnostics", "created_at": timestamp(),
                "source_run": str(run_dir), "source_config_hash": manifest["config_hash"],
                "source_results_hash": digest(result), "backend": result["backend"],
                "source_run_status": result.get("status"), "input_document_count": len(expected_ids),
                "scientific_acceptance": "PENDING_EXPERT_DECISION", "modes": {}}
    for mode in result["modes"]:
        members = read_json(run_dir / mode / "embeddings.json")
        actual_ids = {m["document_id"] for m in members}
        if not actual_ids <= expected_ids:
            raise ValueError("embedding contains an unknown input document")
        analysis["modes"][mode] = {
            **analyze_members(members, thresholds), "embedding_hash": digest(members),
            "documents_without_vectors": sorted(expected_ids - actual_ids),
        }
    write_json(output_dir / "diagnostics.json", analysis)
    write_text(output_dir / "diagnostics.md", render_report(analysis))
    return analysis


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--thresholds", type=float, nargs="+", default=[0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95])
    args = parser.parse_args()
    try:
        diagnose_run(args.run_dir, args.output_dir, args.thresholds)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(str(args.output_dir.resolve() / "diagnostics.md"))


if __name__ == "__main__":
    main()
