import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from run import build_reducer, export_results, read_embeddings


class FakeTopicModel:
    def get_topic_info(self):
        import pandas as pd

        return pd.DataFrame([
            {"Topic": -1, "Count": 1, "Name": "-1_outlier"},
            {"Topic": 0, "Count": 1, "Name": "0_resistance"},
        ])

    def get_topic(self, topic_id):
        return [("outlier", 0.1)] if topic_id == -1 else [("resistance", 0.8)]

    def get_representative_docs(self, topic_id):
        return ["Representative abstract"] if topic_id == 0 else []


class BaselineTest(unittest.TestCase):
    def test_pca_reducer_is_reproducible(self):
        vectors = np.arange(60, dtype=float).reshape(10, 6)
        first = build_reducer("pca", 2, n_neighbors=5, seed=42).fit_transform(vectors)
        second = build_reducer("pca", 2, n_neighbors=5, seed=42).fit_transform(vectors)
        np.testing.assert_allclose(first, second)

    def test_read_embeddings_validates_one_row_per_document(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "embeddings.json"
            rows = [
                {"document_id": "a", "title": "A", "abstract": "Alpha", "vector": [1, 0]},
                {"document_id": "a", "title": "B", "abstract": "Beta", "vector": [0, 1]},
            ]
            path.write_text(json.dumps(rows), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "one row per document"):
                read_embeddings(path)

    def test_export_writes_expected_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            rows = [
                {"document_id": "a", "title": "A", "abstract": "Alpha", "year": 2024},
                {"document_id": "b", "title": "B", "abstract": "Beta", "year": 2025},
            ]
            metrics = export_results(
                output,
                rows,
                np.asarray([0, -1]),
                np.asarray([0.9, 0.1]),
                FakeTopicModel(),
                {"kind": "test"},
            )
            self.assertEqual(metrics["topic_count_excluding_outliers"], 1)
            self.assertEqual(metrics["outlier_documents"], 1)
            for name in (
                "config.json", "topic-info.json", "topic-keywords.json",
                "representative-documents.json", "document-topics.jsonl",
                "outliers.jsonl", "topic-info.csv", "metrics.json", "report.md",
            ):
                self.assertTrue((output / name).is_file(), name)


if __name__ == "__main__":
    unittest.main()
