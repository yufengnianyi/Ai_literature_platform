"""Clustering is numerical; cluster labels and scientific interpretation are separate."""

import numpy as np
from sklearn.cluster import AgglomerativeClustering


def cluster_members(members, threshold=0.35):
    if not 0 < threshold <= 2:
        raise ValueError("distance threshold must be in (0, 2]")
    if not members:
        return []
    ordered = sorted(members, key=lambda row: row["member_id"])
    if len({row["member_id"] for row in ordered}) != len(ordered):
        raise ValueError("duplicate member IDs")
    vectors = np.asarray([row["vector"] for row in ordered], dtype=float)
    if vectors.ndim != 2 or vectors.shape[1] == 0 or not np.isfinite(vectors).all():
        raise ValueError("vectors must form a finite non-empty matrix")
    norms = np.linalg.norm(vectors, axis=1)
    if np.any(norms == 0):
        raise ValueError("zero embedding vector")
    vectors = vectors / norms[:, None]
    labels = [0] if len(ordered) == 1 else AgglomerativeClustering(
        n_clusters=None, metric="cosine", linkage="average", distance_threshold=threshold,
    ).fit_predict(vectors)
    groups = {}
    for index, label in enumerate(labels):
        groups.setdefault(int(label), []).append(index)
    output = []
    for indexes in sorted(groups.values(), key=lambda values: ordered[values[0]]["member_id"]):
        subset = vectors[indexes]
        mean_distance = (1 - np.clip(subset @ subset.T, -1, 1)).mean(axis=1)
        representatives, seen_documents = [], set()
        for local_index in np.argsort(mean_distance, kind="stable"):
            row = ordered[indexes[int(local_index)]]
            if row["document_id"] not in seen_documents:
                representatives.append(row["member_id"])
                seen_documents.add(row["document_id"])
            if len(representatives) == 3:
                break
        output.append({
            "members": [ordered[index] for index in indexes],
            "representative_member_ids": representatives,
            "document_count": len({ordered[index]["document_id"] for index in indexes}),
        })
    return output
