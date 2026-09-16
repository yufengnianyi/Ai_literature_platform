"""Human judgments are explicit inputs, never inferred from a model's own labels."""

from pathlib import Path

from storage import csv_text, digest, new_id, read_csv, read_json, timestamp, write_json, write_text

HUMAN_FIELDS = ["reference_question_count", "covered_question_count", "supported", "grouping_correct",
                "decision", "review_minutes", "reviewer", "notes"]
FIXED_FIELDS = ["row_id", "kind", "mode", "document_id", "item_id", "status", "text", "evidence", "available_questions"]
FIELDS = FIXED_FIELDS + HUMAN_FIELDS


def fixed_matches(left, right):
    return all(left.get(field, "").replace("\r\n", "\n") == right[field].replace("\r\n", "\n")
               for field in FIXED_FIELDS)


def review_rows(result):
    rows = []

    def add(kind, mode, item, text, status, document_id="", evidence="", available=""):
        fixed = dict(kind=kind, mode=mode, document_id=document_id, item_id=item, status=status,
                     text=text, evidence=evidence, available_questions=str(available))
        # Review files may be opened in spreadsheets; literature text is untrusted.
        fixed = {key: "'" + value if value.lstrip().startswith(("=", "+", "-", "@")) else value
                 for key, value in fixed.items()}
        rows.append({"row_id": digest(fixed), **fixed, **dict.fromkeys(HUMAN_FIELDS, "")})

    documents = {row["document_id"]: row for row in result["corpus"]}
    for mode, data in result["modes"].items():
        availability = {identifier: set() for identifier in documents}
        for cluster in data["clusters"]:
            for question in cluster["common_questions"]:
                for anchor in question["evidence"]:
                    availability[anchor["document_id"]].add(question["common_question_id"])
        for document in data["documents"]:
            identifier = document["document_id"]
            add("document", mode, identifier, documents[identifier]["title"], document["status"], identifier,
                documents[identifier]["abstract"], len(availability[identifier]))
        for extraction in data["extractions"]:
            for question in extraction["questions"]:
                add("extraction", mode, question["question_id"], question["question"], extraction["status"],
                    extraction["document_id"], question["quote"])
        for cluster in data["clusters"]:
            add("cluster", mode, cluster["cluster_id"], cluster["topic"], cluster["status"])
            for member in cluster["members"]:
                add("membership", mode, cluster["cluster_id"] + ":" + member["member_id"],
                    cluster["topic"] + " | " + member.get("question", member["title"]), cluster["status"],
                    member["document_id"], member["abstract"])
            for question in cluster["common_questions"]:
                add("question", mode, question["common_question_id"], question["question"], cluster["status"],
                    evidence="\n".join(anchor["document_id"] + ": " + anchor["quote"] for anchor in question["evidence"]))
    return rows


def write_review(path, result):
    path = Path(path)
    expected = review_rows(result)
    old_rows = read_csv(path) if path.exists() else []
    old = {row["row_id"]: row for row in old_rows}
    for row in expected:
        previous = old.get(row["row_id"])
        if previous and fixed_matches(previous, row):
            row.update({field: previous.get(field, "") for field in HUMAN_FIELDS})
    new_text = csv_text(expected, FIELDS)
    if old_rows and path.read_bytes().decode("utf-8") != new_text:
        write_text(path.with_name(new_id("review-backup") + ".csv"),
                   path.read_bytes().decode("utf-8"))
    write_text(path, new_text)


def integer(value, field):
    if not value.isdigit():
        raise ValueError(f"{field} must be a non-negative integer")
    return int(value)


def judged_metric(rows, field, positive, allowed):
    judged = [row for row in rows if row[field].strip()]
    values = [row[field].strip().lower() for row in judged]
    if any(value not in allowed for value in values):
        raise ValueError(f"invalid {field}; allowed: {sorted(allowed)}")
    correct = sum(value in positive for value in values)
    pending = len(rows) - len(judged)
    return {"value": correct / len(rows) if rows and pending == 0 else None,
            "reviewed_subset_value": correct / len(judged) if judged else None,
            "correct": correct, "reviewed": len(judged), "total": len(rows), "pending": pending}


def evaluate_run(run_dir, review_path=None):
    run_dir = Path(run_dir)
    result = read_json(run_dir / "results.json")
    expected = {row["row_id"]: row for row in review_rows(result)}
    supplied = read_csv(review_path or run_dir / "review.csv")
    seen = set()
    for row in supplied:
        identifier = row.get("row_id")
        if identifier not in expected or identifier in seen:
            raise ValueError("unknown or duplicate review row; use this run's review.csv")
        if not fixed_matches(row, expected[identifier]):
            raise ValueError("review source fields changed; edit only judgment columns")
        seen.add(identifier)
        expected[identifier].update({field: row.get(field, "").strip() for field in HUMAN_FIELDS})
    # Missing CSV rows remain pending, rather than disappearing from denominators.
    rows = list(expected.values())
    gold_counts = {}
    for row in rows:
        if row["kind"] == "document" and row["reference_question_count"]:
            count = integer(row["reference_question_count"], "reference_question_count")
            identifier = row["document_id"]
            if identifier in gold_counts and gold_counts[identifier] != count:
                raise ValueError("A/B reference question counts must agree for the same document")
            gold_counts[identifier] = count
    evaluation = {"evaluated_at": timestamp(), "backend": result["backend"], "modes": {},
                  "scientific_acceptance": "SIMULATION_ONLY" if result["backend"] == "SIMULATED" else "PENDING_EXPERT_DECISION"}
    for mode, data in result["modes"].items():
        own = [row for row in rows if row["mode"] == mode]
        docs = [row for row in own if row["kind"] == "document"]
        total_reference = covered = reviewed_docs = 0
        minutes, timed_docs = 0, 0
        for row in docs:
            reference = gold_counts.get(row["document_id"])
            raw_covered = row["covered_question_count"]
            if raw_covered and reference is None:
                raise ValueError("coverage needs an independently supplied reference count")
            if reference is not None:
                total_reference += reference
                available = int(row["available_questions"])
                value = integer(raw_covered, "covered_question_count") if raw_covered else None
                if available == 0 or reference == 0:
                    if value not in {None, 0}:
                        raise ValueError("documents without output or reference questions cannot have positive coverage")
                    value = 0
                if value is not None:
                    if value > reference:
                        raise ValueError("covered count exceeds reference count")
                    covered += value
                    reviewed_docs += 1
            if row["review_minutes"]:
                value = float(row["review_minutes"])
                if not 0 <= value < float("inf"):
                    raise ValueError("review_minutes must be finite and non-negative")
                minutes += value
                timed_docs += 1
        by_kind = {kind: [row for row in own if row["kind"] == kind]
                   for kind in ("question", "extraction", "membership", "cluster")}
        metrics = {
            "directory_question_support": judged_metric(by_kind["question"], "supported", {"yes"}, {"yes", "no"}),
            "extraction_support": judged_metric(by_kind["extraction"], "supported", {"yes"}, {"yes", "no"}),
            "grouping": judged_metric(by_kind["membership"], "grouping_correct", {"yes"}, {"yes", "no"}),
            "directory_usability": judged_metric(by_kind["cluster"], "decision", {"keep", "rename"},
                                                  {"keep", "rename", "merge", "split", "reject"}),
            "coverage": {"value": covered / total_reference if total_reference and reviewed_docs == len(docs) else None,
                         "covered": covered, "known_reference_questions": total_reference,
                         "reviewed_documents": reviewed_docs, "total_documents": len(docs),
                         "pending_documents": len(docs) - reviewed_docs},
            "review_minutes": {"value": minutes if timed_docs == len(docs) and docs else None,
                               "recorded_total": minutes, "timed_documents": timed_docs, "total_documents": len(docs)},
            "document_status_counts": data["document_status_counts"],
            "model_usage": data["telemetry"],
            "failed_summary_count": sum(cluster["status"] in {"FAILED", "PARTIAL"} for cluster in data["clusters"]),
            "unrepresented_members": sum(len(cluster["unrepresented_member_ids"]) for cluster in data["clusters"]),
            "singleton_share": sum(len(cluster["members"]) == 1 for cluster in data["clusters"]) / len(data["clusters"])
                               if data["clusters"] else None,
        }
        evaluation["modes"][mode] = metrics
    comparison = {}
    if set(evaluation["modes"]) == {"abstract", "question"}:
        for metric in ("directory_question_support", "grouping", "directory_usability", "coverage", "review_minutes"):
            a = evaluation["modes"]["abstract"][metric]["value"]
            b = evaluation["modes"]["question"][metric]["value"]
            comparison[metric + "_B_minus_A"] = b - a if a is not None and b is not None else None
    evaluation["comparison"] = comparison
    write_json(run_dir / "evaluation.json", evaluation)
    lines = ["# Human Review Evaluation", "", f"Backend: **{result['backend']}**", "",
             "Missing judgments remain pending. Simulation is not evidence of scientific quality.", "",
             "| Mode | Question support | Coverage | Grouping | Directory usability |", "| --- | --- | --- | --- | --- |"]
    for mode, metrics in evaluation["modes"].items():
        values = [metrics[name]["value"] for name in ("directory_question_support", "coverage", "grouping", "directory_usability")]
        lines.append("| " + mode + " | " + " | ".join("PENDING" if value is None else f"{value:.1%}" for value in values) + " |")
    lines += ["", "Rates require completed judgments for their denominator. See evaluation.json for counts, pending items,",
              "failed records, singleton share, unrepresented members, timing, and A/B differences.", ""]
    write_text(run_dir / "evaluation.md", "\n".join(lines))
    return evaluation
