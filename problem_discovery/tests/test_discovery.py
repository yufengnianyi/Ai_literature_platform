import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from clustering import cluster_members
from corpus import artifact_record, content_hash, normalize_record, prepare_corpus
from evaluation import FIELDS, evaluate_run, write_review
from model_client import FatalModelError, ModelClient, ModelConfig
from pipeline import locate_quote, run_experiment, source_batches, validate_extraction, validate_summary
from storage import csv_text, read_csv, read_json, read_jsonl, write_json, write_jsonl, write_text

FIXTURES = ROOT / "tests" / "fixtures"


class TempTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)


class CorpusTests(TempTest):
    def test_jsonl_aliases_dedup_missing_and_invalid(self):
        input_path = self.directory / "input.jsonl"
        write_jsonl(input_path, [
            {"documentId": "a", "title": "A", "abstractText": "Study A.", "doiNormalized": "https://doi.org/10.X/A"},
            {"document_id": "b", "title": "B", "abstract": "Other text.", "doi": "10.x/a"},
            {"document_id": "c", "title": "C", "abstract": ""},
            {"document_id": "d", "title": " A ", "abstract": "Study A."},
            {"document_id": "a", "title": "Different", "abstract": "Different text."},
        ])
        with input_path.open("a", encoding="utf-8") as stream:
            stream.write("not json\n")
        output = self.directory / "prepared"
        report = prepare_corpus(output, input_jsonl=input_path)
        self.assertEqual(report["selected_count"], 1)
        self.assertEqual(report["excluded_counts"], {"DUPLICATE_ID": 1, "DUPLICATE": 2, "MISSING_ABSTRACT": 1, "INPUT_INVALID": 1})
        self.assertEqual(read_jsonl(output / "corpus.jsonl")[0]["doi"], "10.x/a")

    def test_tei_uses_header_abstract_and_full_document_metadata(self):
        folder = self.directory / "paper"
        write_text(folder / "header.tei.xml", '<TEI xmlns="http://www.tei-c.org/ns/1.0"><teiHeader><fileDesc><titleStmt><title/></titleStmt></fileDesc><profileDesc><abstract><p>Header <hi>abstract</hi> text.</p></abstract></profileDesc></teiHeader></TEI>')
        write_text(folder / "document.tei.xml", '<TEI xmlns="http://www.tei-c.org/ns/1.0"><teiHeader><fileDesc><titleStmt><title>Full title</title></titleStmt><sourceDesc><biblStruct><monogr><imprint><date when="2024-01-01"/></imprint></monogr><idno type="DOI">10.X/TEST</idno></biblStruct></sourceDesc></fileDesc><profileDesc><abstract>Full abstract.</abstract></profileDesc></teiHeader><text><body>Ignore body</body></text></TEI>')
        result = artifact_record(folder)
        self.assertEqual(result["abstract"], "Header abstract text.")
        self.assertEqual(result["title"], "Full title")
        self.assertEqual(result["year"], 2024)
        self.assertEqual(result["doi"], "10.x/test")
        self.assertTrue(result["source"]["abstract"].endswith("header.tei.xml"))

    def test_broken_header_falls_back_and_retains_warning(self):
        folder = self.directory / "paper"
        write_text(folder / "header.tei.xml", "<broken")
        write_text(folder / "document.tei.xml", "<TEI><teiHeader><profileDesc><abstract>Actual abstract.</abstract></profileDesc></teiHeader></TEI>")
        row = artifact_record(folder)
        self.assertEqual(row["abstract"], "Actual abstract.")
        self.assertTrue(row["warnings"])

    def test_deterministic_sampling_and_missing_id(self):
        source = FIXTURES / "corpus.jsonl"
        for name in ("one", "two"):
            prepare_corpus(self.directory / name, input_jsonl=source, limit=2, seed=42)
        self.assertEqual(read_jsonl(self.directory / "one/corpus.jsonl"), read_jsonl(self.directory / "two/corpus.jsonl"))
        write_text(self.directory / "ids.txt", "sim-001\nnot-present\n")
        result = prepare_corpus(self.directory / "three", input_jsonl=source, ids_file=self.directory / "ids.txt")
        self.assertEqual(result["selected_count"], 1)
        self.assertEqual(result["excluded_counts"]["MISSING_DOCUMENT"], 1)

    def test_artifact_id_cannot_traverse_directories(self):
        write_text(self.directory / "ids.txt", "../outside\n")
        with self.assertRaisesRegex(ValueError, "directory names"):
            prepare_corpus(self.directory / "out", artifact_root=self.directory, ids_file=self.directory / "ids.txt")


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.doc = read_json(FIXTURES / "model_responses.json")["documents"][0]

    def test_quotes_use_unicode_code_points_and_detect_ambiguity(self):
        text = "\U0001f331Water. Water."
        with self.assertRaisesRegex(ValueError, "multiple"):
            locate_quote(text, "Water.")
        self.assertEqual(locate_quote(text, "Water.", 1)["end_offset"], 7)
        with self.assertRaises(ValueError):
            locate_quote(text, "Water.", 0)
        with self.assertRaises(ValueError):
            locate_quote(text, "invented")

    def test_extraction_rejects_overflow_and_bad_quotes(self):
        value = copy.deepcopy(self.doc["extraction"])
        valid = validate_extraction(value, self.doc)
        self.assertEqual(valid["questions"][0]["start_offset"], 0)
        value["questions"] *= 4
        with self.assertRaisesRegex(ValueError, "three"):
            validate_extraction(value, self.doc)
        value = copy.deepcopy(self.doc["extraction"])
        value["questions"][0]["evidence_quote"] = "Invented evidence."
        with self.assertRaisesRegex(ValueError, "does not occur"):
            validate_extraction(value, self.doc)

    def test_insufficient_status_and_overflow_are_explicit(self):
        self.assertEqual(validate_extraction({"status": "INSUFFICIENT_INFORMATION", "overflow": False, "questions": []}, self.doc)["questions"], [])
        value = copy.deepcopy(self.doc["extraction"])
        value["overflow"] = True
        self.assertTrue(validate_extraction(value, self.doc)["overflow"])
        value["status"] = "INSUFFICIENT_INFORMATION"
        with self.assertRaises(ValueError):
            validate_extraction(value, self.doc)

    def test_summary_rejects_foreign_members_and_missing_anchors(self):
        members = [{**self.doc, "member_id": "m1"}]
        value = {"topic": "Topic", "status": "READY", "common_questions": [
            {"question": "Question?", "member_ids": ["m1"], "evidence": [{"member_id": "m1", "quote": self.doc["abstract"]}]}]}
        self.assertEqual(validate_summary(value, members)["status"], "READY")
        value["common_questions"][0]["member_ids"] = ["other-cluster"]
        with self.assertRaisesRegex(ValueError, "cross-cluster"):
            validate_summary(value, members)
        value["common_questions"][0]["member_ids"] = ["m1"]
        value["common_questions"][0]["evidence"] = []
        with self.assertRaisesRegex(ValueError, "cover exactly"):
            validate_summary(value, members)

    def test_large_groups_are_partitioned_without_losing_members(self):
        members = [{"member_id": str(i), "abstract": "a" * 200} for i in range(29)]
        batches = list(source_batches(members))
        self.assertEqual([len(batch) for batch in batches], [12, 12, 5])
        self.assertEqual([member for batch in batches for member in batch], members)


class ClusteringTests(unittest.TestCase):
    def test_empty_single_and_two_separate_groups(self):
        self.assertEqual(cluster_members([]), [])
        one = {"member_id": "m1", "document_id": "d1", "vector": [1, 0]}
        self.assertEqual(cluster_members([one])[0]["document_count"], 1)
        members = [one, {"member_id": "m2", "document_id": "d2", "vector": [1, 0.01]},
                   {"member_id": "m3", "document_id": "d1", "vector": [0, 1]}]
        groups = cluster_members(members)
        self.assertEqual([len(group["members"]) for group in groups], [2, 1])
        self.assertEqual(groups, cluster_members(list(reversed(members))))

    def test_invalid_vectors_and_thresholds(self):
        for vector in ([0, 0], [float("nan"), 0]):
            with self.assertRaises(ValueError):
                cluster_members([{"member_id": "m", "document_id": "d", "vector": vector}])
        with self.assertRaises(ValueError):
            cluster_members([], 0)


class ClientTests(TempTest):
    def client(self, session, attempts=1, chat_model="test", cache_name="cache"):
        with patch.dict(os.environ, {"DASHSCOPE_API_KEY": "secret-for-test"}):
            return ModelClient(ModelConfig(chat_model=chat_model, dimensions=2, max_attempts=attempts),
                               self.directory / cache_name, self.directory / "events.jsonl", session=session, retry_delay=0)

    def response(self, value, status=200):
        response = Mock(status_code=status)
        response.json.return_value = value
        return response

    def chat_response(self, content):
        return self.response({"choices": [{"message": {"content": content}}], "usage": {"total_tokens": 12}})

    def test_cache_hits_and_prompt_input_model_invalidation(self):
        session = Mock()
        session.post.return_value = self.chat_response('{"ok":true}')
        client = self.client(session)
        for prompt, payload in (("p", {"a": 1}), ("p", {"a": 1}), ("p2", {"a": 1}), ("p2", {"a": 2})):
            self.assertTrue(client.chat(prompt, payload, lambda value: value, "extraction")["ok"])
        self.assertEqual(session.post.call_count, 3)
        changed = self.client(session, chat_model="changed")
        changed.chat("p", {"a": 1}, lambda value: value, "extraction")
        self.assertEqual(session.post.call_count, 4)
        self.assertEqual(client.metrics()["cache_hits"], 1)

    def test_invalid_json_retries_and_is_not_cached(self):
        session = Mock()
        session.post.side_effect = [self.chat_response("invalid"), self.chat_response('{"ok":true}')]
        client = self.client(session, attempts=2)
        self.assertEqual(client.chat("p", {}, lambda value: value, "extraction"), {"ok": True})
        self.assertEqual(session.post.call_count, 2)
        self.assertEqual(client.metrics()["failed_attempts"], 1)
        self.assertEqual(len(list((self.directory / "cache").glob("*.json"))), 1)

    def test_invalid_cached_response_is_revalidated(self):
        session = Mock()
        session.post.return_value = self.chat_response('{"ok":true}')
        client = self.client(session)
        client.chat("p", {}, lambda value: value, "extraction")
        cache = next((self.directory / "cache").glob("*.json"))
        cached = read_json(cache)
        cached["response"]["choices"][0]["message"]["content"] = "invalid"
        write_json(cache, cached)
        client.chat("p", {}, lambda value: value, "extraction")
        self.assertEqual(session.post.call_count, 2)

    def test_authentication_failure_is_not_retried_and_key_not_logged(self):
        session = Mock()
        session.post.return_value = self.response({}, status=401)
        client = self.client(session, attempts=3)
        with self.assertRaises(RuntimeError):
            client.chat("p", {}, lambda value: value, "extraction")
        self.assertEqual(session.post.call_count, 1)
        self.assertNotIn("secret-for-test", (self.directory / "events.jsonl").read_text())

    def test_embedding_reorders_indexes_and_checks_dimensions(self):
        session = Mock()
        session.post.return_value = self.response({"data": [{"index": 1, "embedding": [0, 1]}, {"index": 0, "embedding": [1, 0]}]})
        client = self.client(session)
        self.assertEqual(client.embed(["a", "b"]), [[1, 0], [0, 1]])
        session.post.return_value = self.response({"data": [{"index": 0, "embedding": [0, 0]}]})
        with self.assertRaises(RuntimeError):
            client.embed(["bad"])

    def test_optional_usage_and_mode_metrics(self):
        session = Mock()
        response = self.chat_response('{"ok":true}')
        response.json.return_value["usage"] = None
        session.post.return_value = response
        client = self.client(session)
        for mode in ("abstract", "question"):
            client.active_mode = mode
            client.chat("p", {"mode": mode}, lambda value: value, "summary")
        self.assertEqual(client.metrics("question")["events_this_invocation"], 1)
        self.assertIsNone(client.metrics()["reported_total_tokens"])


class IntegrationTests(TempTest):
    def make_client(self, output, fixture=None):
        return ModelClient(ModelConfig(max_attempts=1), self.directory / "cache", output / "attempts.jsonl",
                           fixture=fixture or FIXTURES / "model_responses.json", retry_delay=0)

    def prepare(self, source=None):
        output = self.directory / "prepared"
        prepare_corpus(output, input_jsonl=source or FIXTURES / "corpus.jsonl", limit=0)
        return output / "corpus.jsonl"

    def run_both(self):
        corpus = self.prepare()
        output = self.directory / "run"
        result = run_experiment(corpus, output, self.make_client(output))
        return corpus, output, result

    def test_both_modes_outputs_pending_review_and_resume(self):
        corpus, output, result = self.run_both()
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(result["backend"], "SIMULATED")
        self.assertEqual(len(result["corpus"]), 5)
        self.assertEqual(len(result["modes"]["question"]["clusters"]), 2)
        for relative in ("report.md", "review.csv", "manifest.json", "metrics.json", "abstract/clusters.json", "question/questions.jsonl"):
            self.assertTrue((output / relative).is_file(), relative)
        evaluation = evaluate_run(output)
        self.assertIsNone(evaluation["modes"]["question"]["coverage"]["value"])
        self.assertEqual(evaluation["scientific_acceptance"], "SIMULATION_ONLY")
        rows = read_csv(output / "review.csv")
        rows[0]["notes"] = "Human annotation must survive resume"
        write_text(output / "review.csv", csv_text(rows, FIELDS))
        before = result["telemetry"]["attempts"]
        resumed = run_experiment(corpus, output, self.make_client(output), resume=True)
        self.assertEqual(resumed["telemetry"]["attempts"], before)
        self.assertGreater(resumed["telemetry"]["cache_hits"], 0)
        self.assertEqual(read_csv(output / "review.csv")[0]["notes"], rows[0]["notes"])
        with self.assertRaisesRegex(ValueError, "configuration changed"):
            run_experiment(corpus, output, self.make_client(output), threshold=0.5, resume=True)

    def test_human_scores_count_missing_outputs(self):
        _, output, _ = self.run_both()
        rows = read_csv(output / "review.csv")
        for row in rows:
            if row["kind"] == "document":
                row["reference_question_count"] = "1"
                row["covered_question_count"] = "1" if int(row["available_questions"]) else "0"
            elif row["kind"] in {"question", "extraction"}:
                row["supported"] = "yes"
            elif row["kind"] == "membership":
                row["grouping_correct"] = "yes"
            elif row["kind"] == "cluster":
                row["decision"] = "keep"
        write_text(output / "review.csv", csv_text(rows, FIELDS))
        evaluated = evaluate_run(output)
        self.assertEqual(evaluated["modes"]["question"]["coverage"]["value"], 0.8)
        self.assertEqual(evaluated["modes"]["question"]["coverage"]["total_documents"], 5)
        self.assertEqual(evaluated["modes"]["question"]["directory_question_support"]["value"], 1)

    def test_deleted_review_rows_remain_pending_and_tampering_fails(self):
        _, output, _ = self.run_both()
        rows = read_csv(output / "review.csv")
        write_text(output / "review.csv", csv_text([], FIELDS))
        evaluation = evaluate_run(output)
        self.assertEqual(evaluation["modes"]["question"]["coverage"]["pending_documents"], 5)
        rows[0]["text"] = "Changed source field"
        write_text(output / "review.csv", csv_text(rows, FIELDS))
        with self.assertRaisesRegex(ValueError, "source fields changed"):
            evaluate_run(output)

    def test_bad_extraction_keeps_document_in_failure_denominator(self):
        corpus = self.prepare()
        bad_fixture = read_json(FIXTURES / "model_responses.json")
        bad_fixture["documents"][0]["extraction"]["questions"][0]["evidence_quote"] = "Invented quote"
        path = self.directory / "bad-fixture.json"
        write_json(path, bad_fixture)
        output = self.directory / "run"
        result = run_experiment(corpus, output, self.make_client(output, path), mode="question")
        self.assertEqual(result["status"], "PARTIAL")
        self.assertEqual(result["modes"]["question"]["document_status_counts"]["EXTRACTION_FAILED"], 1)
        self.assertEqual(len(result["modes"]["question"]["documents"]), 5)

    def test_bad_embedding_isolated_and_can_resume_after_provider_recovery(self):
        corpus = self.prepare()
        output = self.directory / "run"
        client = self.make_client(output)
        original = client._transport

        def transport(endpoint, request, stage):
            response = original(endpoint, request, stage)
            if endpoint == "embeddings":
                for index, text in enumerate(request["input"]):
                    if "Does assay A detect lower" in text:
                        response["data"][index]["embedding"] = [0, 0, 0]
            return response

        client._transport = transport
        result = run_experiment(corpus, output, client, mode="question")
        self.assertEqual(result["status"], "PARTIAL")
        self.assertEqual(result["modes"]["question"]["document_status_counts"]["EMBEDDING_FAILED"], 1)
        recovered = run_experiment(corpus, output, self.make_client(output), mode="question", resume=True)
        self.assertEqual(recovered["status"], "COMPLETED")

    def test_fixture_refuses_real_inputs_and_snapshot_mutation(self):
        corpus = self.prepare()
        rows = read_jsonl(corpus)
        rows[0]["abstract"] = "Actual different paper"
        write_jsonl(corpus, rows)
        output = self.directory / "run"
        with self.assertRaisesRegex(ValueError, "snapshot"):
            run_experiment(corpus, output, self.make_client(output))
        rows[0]["content_hash"] = content_hash(rows[0])
        write_jsonl(corpus, rows)
        with self.assertRaisesRegex(ValueError, "exact bundled fixture"):
            run_experiment(corpus, output, self.make_client(output))

    def test_empty_corpus_and_cli(self):
        source = self.directory / "empty.jsonl"
        write_text(source, "")
        corpus = self.prepare(source)
        output = self.directory / "run"
        result = run_experiment(corpus, output, self.make_client(output))
        self.assertEqual(result["modes"]["question"]["clusters"], [])
        self.assertIsNone(evaluate_run(output)["modes"]["question"]["coverage"]["value"])
        process = subprocess.run([sys.executable, str(ROOT / "discovery.py"), "--help"], capture_output=True, text=True)
        self.assertEqual(process.returncode, 0)
        self.assertIn("prepare", process.stdout)

    def test_auth_failure_aborts_entire_run(self):
        corpus = self.prepare()
        output = self.directory / "run"
        session = Mock()
        session.post.return_value = Mock(status_code=401)
        with patch.dict(os.environ, {"DASHSCOPE_API_KEY": "secret-for-test"}):
            client = ModelClient(ModelConfig(max_attempts=3), self.directory / "cache",
                                 output / "attempts.jsonl", session=session, retry_delay=0)
        with self.assertRaises(FatalModelError):
            run_experiment(corpus, output, client, mode="question")
        self.assertEqual(session.post.call_count, 1)
        self.assertEqual(read_json(output / "manifest.json")["status"], "FAILED")


if __name__ == "__main__":
    unittest.main()
