"""Run a reproducible BERTopic baseline over cached document embeddings."""

import argparse
import csv
import json
from datetime import datetime, timezone
from pathlib import Path

import matplotlib
import numpy as np
from bertopic import BERTopic
from bertopic.vectorizers import ClassTfidfTransformer
from hdbscan import HDBSCAN
from scipy.cluster.hierarchy import leaves_list, linkage
from scipy.spatial.distance import squareform
from sklearn.decomposition import PCA
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.metrics.pairwise import cosine_similarity

matplotlib.use("Agg")
import matplotlib.pyplot as plt


def read_embeddings(path):
    rows = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(rows, list) or not rows:
        raise ValueError("embedding input must be a non-empty JSON array")
    rows = sorted(rows, key=lambda row: row.get("document_id", ""))
    identifiers = [row.get("document_id") for row in rows]
    if any(not isinstance(value, str) or not value for value in identifiers):
        raise ValueError("every row must have a document_id")
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("BERTopic abstract input requires one row per document")

    vectors = np.asarray([row.get("vector") for row in rows], dtype=float)
    if vectors.ndim != 2 or vectors.shape[1] == 0 or not np.isfinite(vectors).all():
        raise ValueError("vectors must form a finite, non-empty matrix")
    if np.any(np.linalg.norm(vectors, axis=1) == 0):
        raise ValueError("zero embedding vector")

    documents = []
    for row in rows:
        title = row.get("title") or ""
        abstract = row.get("abstract") or ""
        if not isinstance(title, str) or not isinstance(abstract, str) or not abstract.strip():
            raise ValueError("every row must contain a non-empty abstract")
        documents.append((title.strip() + "\n" + abstract.strip()).strip())
    return rows, documents, vectors


def build_model(args):
    vectorizer = CountVectorizer(
        stop_words="english",
        ngram_range=(1, args.max_ngram),
        min_df=1,
    )
    ctfidf = ClassTfidfTransformer(reduce_frequent_words=True)
    reducer = build_reducer(args.reducer, 5, args.n_neighbors, args.seed)
    clusterer = HDBSCAN(
        min_cluster_size=args.min_topic_size,
        min_samples=args.min_samples,
        metric="euclidean",
        cluster_selection_method="eom",
        prediction_data=True,
    )
    return BERTopic(
        embedding_model=None,
        umap_model=reducer,
        hdbscan_model=clusterer,
        vectorizer_model=vectorizer,
        ctfidf_model=ctfidf,
        nr_topics=None,
        top_n_words=args.top_n_words,
        calculate_probabilities=False,
        verbose=args.verbose,
    )


def build_reducer(kind, n_components, n_neighbors, seed):
    if kind == "pca":
        return PCA(n_components=n_components, svd_solver="randomized", random_state=seed)
    if kind == "umap":
        # Keep UMAP optional: importing Numba can be prohibitively slow on Python 3.14.
        from umap import UMAP

        return UMAP(
            n_neighbors=n_neighbors,
            n_components=n_components,
            min_dist=0.0,
            metric="cosine",
            random_state=seed,
            low_memory=True,
        )
    raise ValueError(f"unsupported reducer: {kind}")


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path, rows):
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )


def json_value(value):
    if value is None:
        return None
    if isinstance(value, np.generic):
        return value.item()
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, float) and not np.isfinite(value):
        return None
    return value


def configure_plots():
    plt.rcParams.update({
        "font.family": "DejaVu Sans",
        "font.size": 9,
        "axes.labelsize": 9,
        "axes.titlesize": 11,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "legend.fontsize": 7,
        "pdf.fonttype": 42,
        "ps.fonttype": 42,
        "savefig.facecolor": "white",
    })


def topic_colors(topic_ids):
    palettes = [plt.get_cmap(name).colors for name in ("tab20", "tab20b", "tab20c")]
    colors = [color for palette in palettes for color in palette]
    return {topic_id: colors[index % len(colors)] for index, topic_id in enumerate(topic_ids)}


def save_figure(figure, output, stem):
    figure.savefig(output / f"{stem}.png", dpi=400, bbox_inches="tight")
    figure.savefig(output / f"{stem}.pdf", bbox_inches="tight")
    plt.close(figure)


def export_figures(output, rows, vectors, document_records, model, config):
    configure_plots()
    topic_ids = sorted({row["topic_id"] for row in document_records if row["topic_id"] != -1})
    colors = topic_colors(topic_ids)
    counts = {topic_id: sum(row["topic_id"] == topic_id for row in document_records) for topic_id in topic_ids}
    outlier_count = sum(row["topic_id"] == -1 for row in document_records)
    keywords = {
        topic_id: [term for term, _ in (model.get_topic(topic_id) or [])]
        for topic_id in topic_ids
    }

    reducer_kind = config["parameters"]["reducer"]
    plot_reducer = build_reducer(
        reducer_kind,
        2,
        config["parameters"]["n_neighbors"],
        config["parameters"]["seed"],
    )
    coordinates = plot_reducer.fit_transform(vectors)
    if reducer_kind == "pca":
        variance = plot_reducer.explained_variance_ratio_ * 100
        x_label = f"PC1 ({variance[0]:.1f}% variance)"
        y_label = f"PC2 ({variance[1]:.1f}% variance)"
        projection_note = "Two-dimensional PCA is for visualization only; clustering uses a separate five-component PCA."
    else:
        x_label = "UMAP dimension 1"
        y_label = "UMAP dimension 2"
        projection_note = "Two-dimensional UMAP is for visualization only; clustering uses a separate five-dimensional projection."
    coordinate_rows = []
    for row, record, point in zip(rows, document_records, coordinates, strict=True):
        coordinate_rows.append({
            "document_id": row["document_id"],
            "topic_id": record["topic_id"],
            "umap_x": float(point[0]),
            "umap_y": float(point[1]),
        })
    with (output / "document-map.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=coordinate_rows[0].keys())
        writer.writeheader()
        writer.writerows(coordinate_rows)

    figure, axis = plt.subplots(figsize=(9.2, 6.8), constrained_layout=True)
    topics_array = np.asarray([row["topic_id"] for row in document_records])
    outliers = topics_array == -1
    if outliers.any():
        axis.scatter(
            coordinates[outliers, 0], coordinates[outliers, 1],
            c="#B8B8B8", marker="x", s=16, linewidths=0.6, alpha=0.75,
            label=f"Outliers (n={outliers.sum()})",
        )
    for topic_id in topic_ids:
        selected = topics_array == topic_id
        axis.scatter(
            coordinates[selected, 0], coordinates[selected, 1],
            color=colors[topic_id], s=18, alpha=0.78, linewidths=0,
            label=f"T{topic_id} (n={counts[topic_id]})",
        )
        center = np.median(coordinates[selected], axis=0)
        axis.text(
            center[0], center[1], f"T{topic_id}", ha="center", va="center", fontsize=7.5,
            bbox={"boxstyle": "round,pad=0.18", "facecolor": "white", "edgecolor": colors[topic_id], "alpha": 0.9},
        )
    method_label = f"{reducer_kind.upper()}-HDBSCAN"
    axis.set(title=f"BERTopic document map ({method_label}, n={len(rows)})", xlabel=x_label, ylabel=y_label)
    handles, labels = axis.get_legend_handles_labels()
    if len(handles) <= 18:
        axis.legend(handles, labels, loc="center left", bbox_to_anchor=(1.01, 0.5), frameon=False)
    axis.text(
        0, -0.13,
        projection_note,
        transform=axis.transAxes, fontsize=7, color="#444444",
    )
    save_figure(figure, output, "figure-1-document-map")

    ordered = sorted(topic_ids, key=lambda topic_id: counts[topic_id])[-25:]
    figure, axis = plt.subplots(figsize=(9.2, max(4.8, len(ordered) * 0.34)), constrained_layout=True)
    topic_labels = [f"T{topic_id}  " + " / ".join(keywords[topic_id][:4]) for topic_id in ordered]
    values = [counts[topic_id] for topic_id in ordered]
    axis.barh(range(len(ordered)), values, color=[colors[topic_id] for topic_id in ordered], height=0.72)
    axis.set_yticks(range(len(ordered)), labels=topic_labels)
    axis.set(title="Largest topics and c-TF-IDF descriptors", xlabel="Number of documents", ylabel="")
    axis.grid(axis="x", color="#DDDDDD", linewidth=0.6)
    axis.set_axisbelow(True)
    for index, value in enumerate(values):
        axis.text(value, index, f" {value}", va="center", fontsize=7)
    save_figure(figure, output, "figure-2-topic-sizes")

    all_topic_ids = sorted(model.get_topics().keys())
    row_lookup = {topic_id: index for index, topic_id in enumerate(all_topic_ids)}
    selected_rows = [row_lookup[topic_id] for topic_id in topic_ids]
    similarity = cosine_similarity(model.c_tf_idf_[selected_rows])
    if len(topic_ids) > 1:
        distance = 1 - np.clip(similarity, 0, 1)
        np.fill_diagonal(distance, 0)
        order = leaves_list(linkage(squareform(distance, checks=False), method="average"))
    else:
        order = np.asarray([0])
    ordered_ids = [topic_ids[int(index)] for index in order]
    ordered_similarity = similarity[np.ix_(order, order)]
    with (output / "topic-similarity.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["topic_id", *ordered_ids])
        for topic_id, values in zip(ordered_ids, ordered_similarity, strict=True):
            writer.writerow([topic_id, *[float(value) for value in values]])

    figure, axis = plt.subplots(figsize=(7.2, 6.3), constrained_layout=True)
    image = axis.imshow(ordered_similarity, cmap="cividis", vmin=0, vmax=1, interpolation="nearest")
    step = max(1, len(ordered_ids) // 25)
    ticks = np.arange(0, len(ordered_ids), step)
    tick_labels = [f"T{ordered_ids[index]}" for index in ticks]
    axis.set_xticks(ticks, labels=tick_labels, rotation=90)
    axis.set_yticks(ticks, labels=tick_labels)
    axis.set(title="Topic similarity based on c-TF-IDF", xlabel="Topic", ylabel="Topic")
    colorbar = figure.colorbar(image, ax=axis, fraction=0.046, pad=0.04)
    colorbar.set_label("Cosine similarity")
    save_figure(figure, output, "figure-3-topic-similarity")

    if reducer_kind == "pca":
        projection_detail = (
            f"PC1 and PC2 explain {variance[0]:.1f}% and {variance[1]:.1f}% of embedding variance, respectively."
        )
    else:
        projection_detail = "Axes are the first two dimensions of an independently fitted UMAP projection."
    captions = [
        "# Manuscript-ready figure captions",
        "",
        "**Figure 1. Document-level embedding projection and BERTopic assignments.** "
        f"Points represent {len(rows):,} article title-abstract records embedded with the model declared in the embedding manifest. "
        f"Colors indicate {len(topic_ids)} non-outlier HDBSCAN topics; grey crosses indicate {outlier_count:,} documents assigned to noise. "
        f"{projection_detail} The two-dimensional projection was fitted independently for visualization and did not determine cluster membership.",
        "",
        "**Figure 2. Topic prevalence and lexical descriptors.** "
        "Bars show the number of documents assigned to each non-outlier topic. Labels report the four highest-ranked class-based TF-IDF terms. "
        "Noise documents are excluded from topic bars and remain available in the document-level output.",
        "",
        "**Figure 3. Similarity among topic representations.** "
        "Cells show pairwise cosine similarity between class-based TF-IDF topic vectors. Topics are ordered by average-linkage hierarchical clustering; "
        "the ordering aids visual comparison and does not merge or redefine BERTopic assignments.",
        "",
        "Method note: clustering used "
        f"{method_label} with min_cluster_size={config['parameters']['min_topic_size']}, "
        f"min_samples={config['parameters']['min_samples']}, and random seed={config['parameters']['seed']}. "
        "These figures are descriptive outputs of the sampled corpus. Topic validity, stability, and correspondence to expert research questions require separate evaluation.",
        "",
    ]
    (output / "figure-captions.md").write_text("\n".join(captions), encoding="utf-8")


def export_results(output, rows, topics, probabilities, model, config, vectors=None):
    output.mkdir(parents=True, exist_ok=False)
    topic_info = model.get_topic_info()
    topic_records = [
        {key: json_value(value) for key, value in record.items()}
        for record in topic_info.to_dict(orient="records")
    ]

    document_records = []
    for index, (row, topic) in enumerate(zip(rows, topics, strict=True)):
        probability = None if probabilities is None else json_value(probabilities[index])
        document_records.append({
            "document_id": row["document_id"],
            "title": row.get("title") or "",
            "year": row.get("year"),
            "doi": row.get("doi") or "",
            "topic_id": int(topic),
            "probability": probability,
            "is_outlier": int(topic) == -1,
        })

    keywords = []
    representatives = []
    for topic_id in sorted(set(int(value) for value in topics)):
        terms = model.get_topic(topic_id) or []
        keywords.append({
            "topic_id": topic_id,
            "keywords": [{"term": term, "score": float(score)} for term, score in terms],
        })
        representatives.append({
            "topic_id": topic_id,
            "documents": model.get_representative_docs(topic_id) or [],
        })

    write_json(output / "config.json", config)
    write_json(output / "topic-info.json", topic_records)
    write_json(output / "topic-keywords.json", keywords)
    write_json(output / "representative-documents.json", representatives)
    write_jsonl(output / "document-topics.jsonl", document_records)
    write_jsonl(output / "outliers.jsonl", [row for row in document_records if row["is_outlier"]])

    with (output / "topic-info.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=topic_records[0].keys())
        writer.writeheader()
        writer.writerows(topic_records)

    non_outlier_topics = {int(value) for value in topics if int(value) != -1}
    outlier_count = sum(int(value) == -1 for value in topics)
    metrics = {
        "input_documents": len(rows),
        "topic_count_excluding_outliers": len(non_outlier_topics),
        "outlier_documents": outlier_count,
        "outlier_share": outlier_count / len(rows),
        "human_evaluation": "PENDING",
    }
    write_json(output / "metrics.json", metrics)
    if vectors is not None:
        export_figures(output, rows, vectors, document_records, model, config)

    lines = [
        "# BERTopic Abstract Baseline",
        "",
        "Experimental output. Topics describe patterns in this corpus; they are not scientific importance judgments.",
        "",
        f"Input documents: {len(rows)}",
        f"Topics excluding outliers: {len(non_outlier_topics)}",
        f"Outliers: {outlier_count} ({metrics['outlier_share']:.1%})",
        "",
        "## Topics",
        "",
    ]
    counts = {int(record["Topic"]): int(record["Count"]) for record in topic_records}
    for item in keywords:
        topic_id = item["topic_id"]
        terms = ", ".join(term["term"] for term in item["keywords"])
        label = "Outliers" if topic_id == -1 else f"Topic {topic_id}"
        lines.append(f"- {label}: {counts.get(topic_id, 0)} documents; {terms}")
    lines += ["", "Expert mapping to Q1-Q10: PENDING", ""]
    if vectors is not None:
        lines += [
            "## Figures",
            "",
            f"- Figure 1: document-level {config['parameters']['reducer'].upper()} projection; visualization does not determine cluster membership.",
            "- Figure 2: topic size and the four highest c-TF-IDF descriptors.",
            "- Figure 3: topic c-TF-IDF cosine similarity ordered by average-linkage hierarchy.",
            "- Figures are saved as 400 DPI PNG and vector PDF; source values are CSV files.",
            "",
        ]
    (output / "report.md").write_text("\n".join(lines), encoding="utf-8")
    return metrics


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--embeddings", type=Path, required=True, help="Existing embeddings.json from problem_discovery")
    parser.add_argument("--output", type=Path, required=True, help="New output directory")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--reducer", choices=("pca", "umap"), default="umap")
    parser.add_argument("--n-neighbors", type=int, default=15)
    parser.add_argument("--min-topic-size", type=int, default=10)
    parser.add_argument("--min-samples", type=int, default=5)
    parser.add_argument("--max-ngram", type=int, choices=(1, 2, 3), default=3)
    parser.add_argument("--top-n-words", type=int, default=15)
    parser.add_argument("--verbose", action="store_true")
    return parser.parse_args(argv)


def main():
    args = parse_args()
    model = build_model(args)
    rows, documents, vectors = read_embeddings(args.embeddings)
    if len(rows) <= args.n_neighbors:
        raise SystemExit("n_neighbors must be smaller than the document count")
    if args.min_topic_size < 2 or args.min_topic_size > len(rows):
        raise SystemExit("min_topic_size must be between 2 and the document count")
    if args.min_samples < 1:
        raise SystemExit("min_samples must be positive")
    config = {
        "kind": "bertopic-abstract-baseline",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "input": str(args.embeddings.resolve()),
        "input_documents": len(rows),
        "embedding_dimensions": int(vectors.shape[1]),
        "embedding_source": "PRECOMPUTED",
        "api_calls": 0,
        "topic_limit": None,
        "parameters": {key: getattr(args, key) for key in (
            "seed", "reducer", "n_neighbors", "min_topic_size", "min_samples", "max_ngram", "top_n_words"
        )},
    }
    topics, probabilities = model.fit_transform(documents, embeddings=vectors)
    metrics = export_results(args.output, rows, topics, probabilities, model, config, vectors=vectors)
    print(json.dumps({"output": str(args.output.resolve()), **metrics}, ensure_ascii=False))


if __name__ == "__main__":
    main()
