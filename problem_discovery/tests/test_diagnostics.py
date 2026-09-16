"""Numerical fixtures are synthetic; these tests do not measure scientific quality."""

from copy import deepcopy
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from experiments.diagnose_thresholds import analyze_members, diagnose_run
from storage import write_json


def member(identifier, document, vector):
    return {"member_id": identifier, "document_id": document, "title": "SYNTHETIC", "vector": vector}


class DiagnosticsTests(unittest.TestCase):
    def test_empty_and_singleton(self):
        empty = analyze_members([], [0.35])
        self.assertIsNone(empty["minimum_cross_document_distance"])
        self.assertEqual(empty["thresholds"][0]["clusters"], 0)
        single = analyze_members([member("a", "a", [1, 0])], [0.35])
        self.assertEqual(single["thresholds"][0]["single_document_clusters"], 1)

    def test_same_paper_is_not_cross_document_evidence(self):
        data = [member("a1", "a", [1, 0]), member("a2", "a", [1, 0]), member("b1", "b", [0, 1])]
        before = deepcopy(data)
        result = analyze_members(data, [1.1, 0.35, 0.35])
        low, high = result["thresholds"]
        self.assertEqual(low["clusters"], 2)
        self.assertEqual(low["single_member_clusters"], 1)
        self.assertEqual(low["cross_document_clusters"], 0)
        self.assertEqual(high["cross_document_clusters"], 1)
        self.assertEqual(high["documents_in_cross_clusters"], 2)
        self.assertEqual(result["minimum_cross_document_distance"], 1.0)
        self.assertEqual(data, before)

    def test_invalid_thresholds_and_vectors(self):
        for thresholds in ([], [0], [3], [float("nan")], [float("inf")]):
            with self.assertRaises(ValueError):
                analyze_members([], thresholds)
        with self.assertRaises(ValueError):
            analyze_members([member("a", "a", [0, 0])], [0.35])

    def test_output_keeps_missing_documents_and_source_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run, output = root / "run", root / "analysis"
            write_json(run / "results.json", {"backend": "SIMULATED", "status": "PARTIAL",
                       "corpus": [{"document_id": "a"}, {"document_id": "b"}], "modes": {"abstract": {}}})
            write_json(run / "manifest.json", {"config_hash": "test"})
            write_json(run / "abstract" / "embeddings.json", [member("a", "a", [1, 0])])
            before = {str(p): p.read_bytes() for p in run.rglob("*.json")}
            result = diagnose_run(run, output, [0.35])
            self.assertEqual(result["modes"]["abstract"]["documents_without_vectors"], ["b"])
            self.assertEqual(result["scientific_acceptance"], "PENDING_EXPERT_DECISION")
            self.assertTrue((output / "diagnostics.md").is_file())
            self.assertEqual(before, {str(p): p.read_bytes() for p in run.rglob("*.json")})
            with self.assertRaises(ValueError):
                diagnose_run(run, output, [0.35])


if __name__ == "__main__":
    unittest.main()
