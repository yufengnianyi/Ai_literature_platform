#!/usr/bin/env python3
"""Run the expert Q1-Q8 extractor on the nine manually classified evaluation papers."""

from __future__ import annotations

import csv
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path
from typing import Any
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
PROMPT_ROOT = ROOT / "src/main/resources/prompts/evidence/expert-q8"
OUTPUT_ROOT = ROOT / "outputs/expert-q8-extraction-20260923"
PROFILE_VERSION = "expert_q8_20260921_v1"
MODEL = os.getenv("DASHSCOPE_CHAT_MODEL", "qwen3-max-2026-01-23")
ENDPOINT = os.getenv(
    "DASHSCOPE_COMPATIBLE_ENDPOINT",
    "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
)

CASES: list[tuple[str, tuple[str, ...]]] = [
    ("0c9e1f06-6984-46b5-bba2-29021688c5ad", ("Q1", "Q2", "Q3")),
    ("034e7aff-0a54-4050-ad4a-ffe4e589cd2b", ("Q3", "Q4")),
    ("160e49ff-8ba2-4fad-a222-ce1afd232c6c", ("Q4",)),
    ("03cf6af5-5477-4917-8c96-56f7db5418ce", ("Q5", "Q7")),
    ("14ff899b-a254-445c-a394-c14653a294bf", ("Q6", "Q8")),
    ("02678a42-6cc4-4fbc-88bc-b1580d3afa5d", ("Q7",)),
    ("016332b5-552c-4365-ab4c-718d5571db88", ("Q8",)),
    ("0608ccd1-4748-4f24-9f1b-59875ebd1a27", ("Q4", "Q8")),
    ("455957a3-2a12-479d-83e6-3d7d386e9b70", ("Q5", "Q7")),
]

Q8_TYPES = {"分子诊断", "病原检测", "病害监测", "流行病学", "风险预测", "预警模型"}


def load_dotenv() -> None:
    path = ROOT / ".env"
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip()


def field_contract(field: dict[str, Any], prefix: str = "") -> tuple[str, str, str]:
    key = prefix + field["field_key"]
    label = ("证据:" if prefix else "") + field["label_zh"]
    rule = (
        f"- {label} [{key}]: {field.get('extraction_rule', '')}; "
        f"type: {field.get('type_in_source', 'text')}; "
        f"source-required: {field.get('required_in_source', 'unspecified')}; "
        f"illustrative example only: {field.get('example_in_source', '')}"
    )
    return key, label, rule


def profiles() -> dict[str, dict[str, Any]]:
    source = json.loads(read_text(PROMPT_ROOT / "definitions.json"))
    result: dict[str, dict[str, Any]] = {}
    for question in source["questions"]:
        keys: list[str] = []
        headers: list[str] = []
        rules: list[str] = []
        for field in question["fields"]:
            key, label, rule = field_contract(field)
            keys.append(key)
            headers.append(label)
            rules.append(rule)
        for field in source["common_evidence_fields"]:
            if field["field_key"] in {"record_id", "paper_id"}:
                continue
            key, label, rule = field_contract(field, "common.")
            keys.append(key)
            headers.append(label)
            rules.append(rule)
        question_id = question["question_id"]
        result[question_id] = {
            "id": question_id,
            "title": question["title_zh"],
            "row_unit": question["core_relation"],
            "keys": keys,
            "headers": headers,
            "guidance": "\n".join(rules),
            "system": read_text(PROMPT_ROOT / "common-extraction-system.txt")
            + "\n"
            + read_text(PROMPT_ROOT / f"{question_id.lower()}-extraction-system.txt"),
        }
    return result


def paper_metadata(document_id: str) -> dict[str, str]:
    root = ROOT / "data/rag" / document_id
    header_path = root / "header.tei.xml"
    xml = ElementTree.parse(header_path).getroot()
    title = ""
    abstract = ""
    for element in xml.iter():
        local = element.tag.rsplit("}", 1)[-1]
        if local == "title" and not title:
            title = " ".join("".join(element.itertext()).split())
        if local == "abstract" and not abstract:
            abstract = " ".join("".join(element.itertext()).split())
    doi = ""
    manifest = root / "artifact-manifest.json"
    if manifest.exists():
        data = json.loads(manifest.read_text(encoding="utf-8"))
        doi = str(data.get("doi") or data.get("canonicalDoi") or "")
        title = str(data.get("title") or title)
    return {"title": title, "abstract": abstract, "doi": doi}


def load_chunks(document_id: str, question_id: str, abstract: str) -> list[dict[str, Any]]:
    root = ROOT / "data/rag" / document_id
    chunks: list[dict[str, Any]] = []
    document_jsonl = root / "document.jsonl"
    if document_jsonl.exists():
        for line in document_jsonl.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            item = json.loads(line)
            chunks.append(
                {
                    "id": item.get("chunk_id", ""),
                    "section": item.get("section_path", ""),
                    "text": item.get("text", ""),
                    "order": int(item.get("chunk_index") or len(chunks) + 1),
                }
            )
    if abstract and not any(c["section"].lower() == "abstract" for c in chunks):
        chunks.insert(
            0,
            {
                "id": f"{document_id}:abstract",
                "section": "Abstract",
                "text": abstract,
                "order": 0,
            },
        )
    if question_id == "Q5":
        tables = root / "tables.jsonl"
        if tables.exists():
            for index, line in enumerate(tables.read_text(encoding="utf-8").splitlines(), 1):
                if not line.strip():
                    continue
                table = json.loads(line)
                chunks.append(
                    {
                        "id": f"{document_id}:table:{table.get('tableRef', index)}",
                        "section": f"Table {table.get('label') or table.get('tableRef') or index}",
                        "text": table.get("markdown") or "",
                        "order": 10000 + index,
                    }
                )
    if not chunks:
        raise RuntimeError(f"No chunks available for {document_id}")
    return chunks


def extraction_prompt(
    document_id: str,
    metadata: dict[str, str],
    profile: dict[str, Any],
    chunks: list[dict[str, Any]],
) -> str:
    header = " | ".join(profile["headers"])
    rendered_chunks = "".join(
        f"\n--- chunk_id={chunk['id']}; section={chunk['section']} ---\n{chunk['text']}"
        for chunk in chunks
    )
    return f"""
Profile version: {PROFILE_VERSION}
Record unit: {profile['row_unit']}
Split independent objects and experimental contexts; preserve value-condition-source correspondence.
Required header row:
| {header} |
Field definitions (examples are not facts):
{profile['guidance']}
Task: Extract {profile['id']} evidence ({profile['title']}) from this paper.

Document metadata:
- document_id: {document_id}
- title: {metadata['title']}
- authors:
- publication_year:
- journal:
- doi: {metadata['doi']}

Supplied chunks:
{rendered_chunks}
""".strip()


def retry_prompt(base: str, error: Exception, headers: list[str]) -> str:
    return (
        base
        + f"\n\nPrevious output failed parsing: {error}\n\n"
        + "Return the complete corrected Markdown table only. Use exactly this header row:\n"
        + "| "
        + " | ".join(headers)
        + " |\nInclude one separator row. Do not add prose, JSON, code fences, N/A, 未报道, or 未知.\n"
        + "If no evidence qualifies, return the required header and separator rows with no data rows."
    )


def call_model(
    system: str,
    user: str,
    api_key: str,
    max_attempts: int = 3,
    response_format: dict[str, str] | None = None,
) -> tuple[str, dict[str, int], int]:
    last_error: Exception | None = None
    for attempt in range(1, max_attempts + 1):
        payload = {
            "model": MODEL,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0,
            "enable_thinking": False,
        }
        if response_format:
            payload["response_format"] = response_format
        request = urllib.request.Request(
            ENDPOINT,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                body = json.loads(response.read().decode("utf-8"))
            elapsed_ms = round((time.perf_counter() - started) * 1000)
            content = body["choices"][0]["message"]["content"]
            usage = body.get("usage") or {}
            return content, {
                "input": int(usage.get("prompt_tokens") or usage.get("input_tokens") or 0),
                "output": int(usage.get("completion_tokens") or usage.get("output_tokens") or 0),
                "total": int(usage.get("total_tokens") or 0),
            }, elapsed_ms
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, KeyError, ValueError) as error:
            last_error = error
            if attempt == max_attempts:
                break
            time.sleep(attempt * 2)
    raise RuntimeError(f"Model request failed after {max_attempts} attempts: {last_error}")


def split_markdown_row(line: str) -> list[str]:
    cells: list[str] = []
    cell: list[str] = []
    escaped = False
    for char in line[1:-1]:
        if escaped:
            cell.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == "|":
            cells.append("".join(cell).strip())
            cell = []
        else:
            cell.append(char)
    if escaped:
        cell.append("\\")
    cells.append("".join(cell).strip())
    return cells


def parse_markdown(markdown: str, profile: dict[str, Any]) -> list[list[str]]:
    lines = [line.strip() for line in markdown.splitlines() if line.strip()]
    expected = profile["headers"]
    header_index = -1
    for index, line in enumerate(lines):
        if line.startswith("|") and line.endswith("|") and split_markdown_row(line) == expected:
            header_index = index
            break
    if header_index < 0:
        raise ValueError(f"Header mismatch for {profile['id']}")
    if header_index + 1 >= len(lines):
        raise ValueError("Missing separator row")
    separator = split_markdown_row(lines[header_index + 1])
    # Some compatible models emit two dashes, spaces around alignment colons, or
    # a visually valid separator with one empty trailing cell.  The exact header
    # match above already fixes the schema, so accept harmless separator variants.
    if not separator or not all(re.fullmatch(r"\s*:?-{1,}:?\s*", cell) for cell in separator):
        raise ValueError("Invalid separator row")
    rows: list[list[str]] = []
    seen: set[tuple[str, ...]] = set()
    for line in lines[header_index + 2 :]:
        if not (line.startswith("|") and line.endswith("|")):
            continue
        cells = split_markdown_row(line)
        if len(cells) != len(expected):
            raise ValueError(f"Expected {len(expected)} cells, got {len(cells)}")
        if not any(cells):
            continue
        if profile["id"] == "Q8" and cells[0] not in Q8_TYPES:
            raise ValueError(f"Invalid Q8 record_type: {cells[0]}")
        key = tuple(cells)
        if key not in seen:
            seen.add(key)
            rows.append(cells)
    return rows


def extract_json_object(text: str) -> dict[str, Any]:
    start = text.find("{")
    if start < 0:
        raise ValueError("No JSON object in translation response")
    # Decode the first complete object.  Providers sometimes append a second
    # correction object or a short explanation despite the JSON-only contract.
    parsed, _ = json.JSONDecoder().raw_decode(text[start:])
    if not isinstance(parsed, dict):
        raise ValueError("Translation response is not a JSON object")
    return parsed


def translate_rows(
    title: str,
    records: list[dict[str, Any]],
    api_key: str,
) -> tuple[str, dict[str, list[str]], dict[str, int], int, int]:
    translated: dict[str, list[str]] = {}
    title_zh = ""
    usage_total = {"input": 0, "output": 0, "total": 0}
    elapsed_total = 0
    calls = 0
    for start in range(0, max(1, len(records)), 8):
        batch = records[start : start + 8]
        if not batch and start > 0:
            continue
        items = [
            {"id": record["translation_id"], "cells": record["cells"]}
            for record in batch
        ]
        system = (
            "你是科学文献结构化结果翻译器。将英文叙述翻译成准确、简洁的中文。"
            "必须保留基因/蛋白/化合物编号、拉丁学名、数值、单位、比较符、引物序列、"
            "chunk_id 和 Figure/Table 编号。空字符串仍为空。不得新增或删除事实。"
            "只返回 JSON：{\"title_zh\":\"...\",\"items\":[{\"id\":\"...\",\"cells\":[\"...\"]}]}。"
            "每个 items 的 id、顺序和 cells 数量必须与输入一致。"
        )
        user = json.dumps({"title": title, "items": items}, ensure_ascii=False)
        last_error: Exception | None = None
        for attempt in range(1, 4):
            content, usage, elapsed_ms = call_model(system, user, api_key)
            calls += 1
            for key in usage_total:
                usage_total[key] += usage[key]
            elapsed_total += elapsed_ms
            try:
                parsed = extract_json_object(content)
                by_id = {str(item["id"]): item["cells"] for item in parsed.get("items", [])}
                if set(by_id) != {item["id"] for item in items}:
                    raise ValueError("Translation ids do not match")
                for item in items:
                    cells = by_id[item["id"]]
                    if len(cells) != len(item["cells"]):
                        raise ValueError(f"Translation cell count mismatch for {item['id']}")
                    translated[item["id"]] = [str(value) for value in cells]
                title_zh = str(parsed.get("title_zh") or title_zh)
                last_error = None
                break
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
                last_error = error
                user += f"\n上次响应校验失败：{error}。请返回完整修正后的 JSON。"
        if last_error is not None:
            raise RuntimeError(f"Translation failed: {last_error}")
    return title_zh or title, translated, usage_total, elapsed_total, calls


def write_csv(path: Path, headers: list[str], rows: list[list[Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(headers)
        writer.writerows(rows)


def main() -> int:
    load_dotenv()
    api_key = os.getenv("DASHSCOPE_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("DASHSCOPE_API_KEY is required")
    profile_map = profiles()
    missing_inputs = [
        document_id
        for document_id, _ in CASES
        if not (ROOT / "data/rag" / document_id / "header.tei.xml").exists()
    ]
    if missing_inputs:
        raise RuntimeError("Missing local paper inputs: " + ", ".join(missing_inputs))
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    started_wall = time.perf_counter()
    all_records: list[dict[str, Any]] = []
    paper_stats: dict[str, dict[str, Any]] = {}
    calls_log: list[list[Any]] = []

    for document_id, question_ids in CASES:
        metadata = paper_metadata(document_id)
        stats = {
            "document_id": document_id,
            "title": metadata["title"],
            "title_zh": "",
            "questions": ";".join(question_ids),
            "counts": defaultdict(int),
            "extract_input": 0,
            "extract_output": 0,
            "extract_total": 0,
            "translate_input": 0,
            "translate_output": 0,
            "translate_total": 0,
            "extract_ms": 0,
            "translate_ms": 0,
            "extract_calls": 0,
            "translate_calls": 0,
            "status": "SUCCESS",
            "error": "",
        }
        paper_stats[document_id] = stats
        paper_records: list[dict[str, Any]] = []
        print(f"[{document_id}] {metadata['title']}", flush=True)
        for question_id in question_ids:
            profile = profile_map[question_id]
            chunks = load_chunks(document_id, question_id, metadata["abstract"])
            base_prompt = extraction_prompt(document_id, metadata, profile, chunks)
            prompt = base_prompt
            last_error: Exception | None = None
            rows: list[list[str]] = []
            for attempt in range(1, 4):
                try:
                    content, usage, elapsed_ms = call_model(profile["system"], prompt, api_key)
                    stats["extract_calls"] += 1
                    stats["extract_input"] += usage["input"]
                    stats["extract_output"] += usage["output"]
                    stats["extract_total"] += usage["total"]
                    stats["extract_ms"] += elapsed_ms
                    calls_log.append(
                        [document_id, question_id, "提取", attempt, usage["input"], usage["output"], usage["total"], elapsed_ms, "SUCCESS", ""]
                    )
                    rows = parse_markdown(content, profile)
                    last_error = None
                    break
                except Exception as error:  # noqa: BLE001 - evaluation must record provider/parser errors
                    last_error = error
                    calls_log.append([document_id, question_id, "提取", attempt, 0, 0, 0, 0, "FAILED", str(error)])
                    prompt = retry_prompt(base_prompt, error, profile["headers"])
            if last_error is not None:
                stats["status"] = "PARTIAL_FAILED"
                stats["error"] += f"{question_id}: {last_error}; "
                print(f"  {question_id}: FAILED {last_error}", flush=True)
                continue
            stats["counts"][question_id] = len(rows)
            print(f"  {question_id}: {len(rows)} records", flush=True)
            for row_index, cells in enumerate(rows, 1):
                record = {
                    "document_id": document_id,
                    "title": metadata["title"],
                    "question_id": question_id,
                    "record_index": row_index,
                    "translation_id": f"{question_id}-{row_index}",
                    "profile": profile,
                    "cells": cells,
                }
                paper_records.append(record)
                all_records.append(record)
        try:
            title_zh, translations, usage, elapsed_ms, calls = translate_rows(
                metadata["title"], paper_records, api_key
            )
            stats["title_zh"] = title_zh
            stats["translate_input"] += usage["input"]
            stats["translate_output"] += usage["output"]
            stats["translate_total"] += usage["total"]
            stats["translate_ms"] += elapsed_ms
            stats["translate_calls"] += calls
            for record in paper_records:
                record["cells_zh"] = translations.get(record["translation_id"], record["cells"])
                record["title_zh"] = title_zh
            calls_log.append(
                [document_id, "ALL", "翻译", calls, usage["input"], usage["output"], usage["total"], elapsed_ms, "SUCCESS", ""]
            )
        except Exception as error:  # noqa: BLE001
            stats["status"] = "PARTIAL_FAILED"
            stats["error"] += f"translation: {error}; "
            stats["title_zh"] = metadata["title"]
            for record in paper_records:
                record["cells_zh"] = record["cells"]
                record["title_zh"] = metadata["title"]
            calls_log.append([document_id, "ALL", "翻译", 0, 0, 0, 0, 0, "FAILED", str(error)])

    result_rows: list[list[Any]] = []
    field_rows: list[list[Any]] = []
    for record in all_records:
        original = {
            key: value
            for key, value in zip(record["profile"]["keys"], record["cells"])
            if value
        }
        translated = {
            key: value
            for key, value in zip(record["profile"]["keys"], record["cells_zh"])
            if value
        }
        result_rows.append(
            [
                record["document_id"],
                record["title"],
                record["title_zh"],
                record["question_id"],
                record["record_index"],
                json.dumps(original, ensure_ascii=False),
                json.dumps(translated, ensure_ascii=False),
            ]
        )
        for key, label, original_value, translated_value in zip(
            record["profile"]["keys"],
            record["profile"]["headers"],
            record["cells"],
            record["cells_zh"],
        ):
            if not original_value and not translated_value:
                continue
            field_rows.append(
                [
                    record["document_id"], record["title"], record["title_zh"],
                    record["question_id"], record["record_index"], key, label,
                    original_value, translated_value,
                ]
            )

    stats_rows: list[list[Any]] = []
    for document_id, _ in CASES:
        stats = paper_stats[document_id]
        count_text = ";".join(f"{qid}={stats['counts'].get(qid, 0)}" for qid in stats["questions"].split(";"))
        row_total = sum(stats["counts"].values())
        stats_rows.append(
            [
                document_id, stats["title"], stats["title_zh"], stats["questions"],
                count_text, row_total,
                stats["extract_calls"], stats["translate_calls"],
                stats["extract_input"], stats["extract_output"], stats["extract_total"],
                stats["translate_input"], stats["translate_output"], stats["translate_total"],
                stats["extract_input"] + stats["translate_input"],
                stats["extract_output"] + stats["translate_output"],
                stats["extract_total"] + stats["translate_total"],
                stats["extract_ms"], stats["translate_ms"],
                stats["extract_ms"] + stats["translate_ms"],
                stats["status"], stats["error"],
            ]
        )

    wall_ms = round((time.perf_counter() - started_wall) * 1000)
    total_input = sum(row[14] for row in stats_rows)
    total_output = sum(row[15] for row in stats_rows)
    total_tokens = sum(row[16] for row in stats_rows)
    total_api_ms = sum(row[19] for row in stats_rows)
    total_records = len(all_records)

    write_csv(
        OUTPUT_ROOT / "提取结果_双语.csv",
        ["文献ID", "英文标题", "中文标题", "问题", "记录序号", "原文字段JSON", "中文字段JSON"],
        result_rows,
    )
    write_csv(
        OUTPUT_ROOT / "提取字段_双语长表.csv",
        ["文献ID", "英文标题", "中文标题", "问题", "记录序号", "字段键", "字段中文名", "原文值", "中文翻译"],
        field_rows,
    )
    write_csv(
        OUTPUT_ROOT / "每篇文章统计.csv",
        [
            "文献ID", "英文标题", "中文标题", "分类问题", "分题提取条数", "总提取条数",
            "提取调用次数", "翻译调用次数", "提取输入Token", "提取输出Token", "提取总Token",
            "翻译输入Token", "翻译输出Token", "翻译总Token", "本篇输入Token", "本篇输出Token",
            "本篇总Token", "提取耗时毫秒", "翻译耗时毫秒", "本篇API耗时毫秒", "状态", "错误",
        ],
        stats_rows,
    )
    write_csv(
        OUTPUT_ROOT / "调用明细.csv",
        ["文献ID", "问题", "阶段", "尝试或调用数", "输入Token", "输出Token", "总Token", "耗时毫秒", "状态", "错误"],
        calls_log,
    )
    write_csv(
        OUTPUT_ROOT / "本轮汇总.csv",
        ["模型", "文献数", "分类任务数", "提取记录数", "输入Token", "输出Token", "总Token", "API累计耗时毫秒", "实际墙钟耗时毫秒", "输出目录"],
        [[MODEL, len(CASES), sum(len(qids) for _, qids in CASES), total_records,
          total_input, total_output, total_tokens, total_api_ms, wall_ms, str(OUTPUT_ROOT)]],
    )
    print(
        json.dumps(
            {
                "papers": len(CASES),
                "tasks": sum(len(qids) for _, qids in CASES),
                "records": total_records,
                "input_tokens": total_input,
                "output_tokens": total_output,
                "total_tokens": total_tokens,
                "api_elapsed_ms": total_api_ms,
                "wall_elapsed_ms": wall_ms,
                "output": str(OUTPUT_ROOT),
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    return 0 if all(stats["status"] == "SUCCESS" for stats in paper_stats.values()) else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
