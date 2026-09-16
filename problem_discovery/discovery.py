"""CLI: prepare a corpus, run A/B discovery, then evaluate explicit human judgments."""

import argparse
import json
import os
import sys
from pathlib import Path

from storage import OUTPUTS, ROOT, new_id


def parser():
    root = argparse.ArgumentParser(description="Traceable literature research-question discovery")
    commands = root.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare", help="Freeze existing TEI or exported JSONL inputs")
    source = prepare.add_mutually_exclusive_group()
    source.add_argument("--artifact-root", type=Path, default=ROOT.parent / "data" / "rag")
    source.add_argument("--input-jsonl", type=Path)
    prepare.add_argument("--ids", type=Path, help="Optional UTF-8 file with one document ID per line")
    prepare.add_argument("--limit", type=int, default=100, help="Maximum selected papers; 0 means all")
    prepare.add_argument("--seed", type=int, default=42)
    prepare.add_argument("--output-dir", type=Path)
    run = commands.add_parser("run", help="Run direct-abstract and/or extracted-question clustering")
    run.add_argument("--corpus", type=Path, required=True)
    run.add_argument("--mode", choices=("abstract", "question", "both"), default="both")
    run.add_argument("--output-dir", type=Path)
    run.add_argument("--distance-threshold", type=float, default=0.35)
    run.add_argument("--base-url", default=os.environ.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    run.add_argument("--chat-model", default=os.environ.get("DASHSCOPE_CHAT_MODEL", "qwen3-max-2026-01-23"))
    run.add_argument("--embedding-model", default=os.environ.get("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v4"))
    run.add_argument("--dimensions", type=int, default=1024)
    run.add_argument("--timeout", type=float, default=90)
    run.add_argument("--max-attempts", type=int, default=3)
    run.add_argument("--resume", action="store_true")
    run.add_argument("--no-cache", action="store_true", help="New runs only: independent model calls for stability experiments")
    run.add_argument("--fixture", type=Path, help="Explicit simulated provider data; only accepts its matching corpus")
    run.add_argument("--prompts-dir", type=Path, default=ROOT / "prompts")
    evaluate = commands.add_parser("evaluate", help="Evaluate manually completed review.csv")
    evaluate.add_argument("--run-dir", type=Path, required=True)
    evaluate.add_argument("--review", type=Path)
    return root


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        if args.command == "prepare":
            from corpus import prepare_corpus
            output = args.output_dir or OUTPUTS / new_id("prepared")
            result = prepare_corpus(output, args.artifact_root, args.input_jsonl, args.ids, args.limit, args.seed)
            print(json.dumps({"output_dir": str(output.resolve()), **result}, ensure_ascii=False, indent=2))
        elif args.command == "run":
            from model_client import ModelClient, ModelConfig
            from pipeline import run_experiment
            if args.resume and (args.output_dir is None or args.no_cache):
                raise ValueError("--resume requires --output-dir and cannot be combined with --no-cache")
            output = args.output_dir or OUTPUTS / new_id("run")
            config = ModelConfig(args.base_url, args.chat_model, args.embedding_model, args.dimensions,
                                 args.timeout, args.max_attempts)
            client = ModelClient(config, OUTPUTS / ".cache", output / "attempts.jsonl",
                                 fixture=args.fixture, use_cache=not args.no_cache)
            result = run_experiment(args.corpus, output, client, args.mode, args.distance_threshold,
                                    args.resume, args.prompts_dir)
            print(json.dumps({"output_dir": str(output.resolve()), "status": result["status"],
                              "backend": result["backend"], "telemetry": result["telemetry"]}, ensure_ascii=False, indent=2))
            return 0 if result["status"] == "COMPLETED" else 2
        else:
            from evaluation import evaluate_run
            result = evaluate_run(args.run_dir, args.review)
            print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError, RuntimeError, ImportError) as error:
        message = f"{type(error).__name__}: {error}"
        key = os.environ.get("DASHSCOPE_API_KEY", "")
        print(message.replace(key, "[redacted]") if key else message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
