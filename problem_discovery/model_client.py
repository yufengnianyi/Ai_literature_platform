"""OpenAI-compatible DashScope transport with validated, content-addressed caching."""

import json
import math
import os
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from urllib.parse import urlsplit

import requests

from storage import digest, read_json, read_jsonl, timestamp, write_json


@dataclass(frozen=True)
class ModelConfig:
    base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    chat_model: str = "qwen3-max-2026-01-23"
    embedding_model: str = "text-embedding-v4"
    dimensions: int = 1024
    timeout: float = 90
    max_attempts: int = 3

    def __post_init__(self):
        parsed = urlsplit(self.base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("base URL must be HTTP(S)")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("base URL cannot contain credentials, query, or fragment")
        if self.dimensions < 1 or self.timeout <= 0 or not 1 <= self.max_attempts <= 5:
            raise ValueError("invalid model configuration")


class PermanentModelError(RuntimeError):
    pass


class FatalModelError(PermanentModelError):
    """Invalid credentials affect the whole run, not one paper."""


class ModelClient:
    def __init__(self, config, cache_dir, telemetry_path, fixture=None, use_cache=True,
                 session=None, retry_delay=1):
        self.config = config
        self.cache_dir = Path(cache_dir)
        self.telemetry_path = Path(telemetry_path)
        self.fixture = read_json(fixture) if fixture else None
        if self.fixture and self.fixture.get("fixture_only") is not True:
            raise ValueError("fixture file must declare fixture_only=true")
        self.backend = "SIMULATED" if self.fixture else "DASHSCOPE"
        self.use_cache = use_cache
        self.session = session or requests.Session()
        self.retry_delay = retry_delay
        self.active_mode = None
        self.api_key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
        self.events = read_jsonl(self.telemetry_path) if self.telemetry_path.exists() else []
        self.initial_events = len(self.events)

    def public_config(self):
        return {**asdict(self.config), "backend": self.backend,
                "actual_dimensions": self.fixture["dimensions"] if self.fixture else self.config.dimensions,
                "fixture_hash": digest(self.fixture) if self.fixture else None}

    def check_corpus(self, corpus):
        if self.fixture:
            known = {row["document_id"]: row for row in self.fixture["documents"]}
            for row in corpus:
                reference = known.get(row["document_id"])
                if not reference or any(row[field] != reference[field] for field in ("title", "abstract")):
                    raise ValueError("simulation only accepts the exact bundled fixture corpus")
        elif corpus and not self.api_key:
            raise ValueError("DASHSCOPE_API_KEY is not set; use an explicit test fixture for offline tests")

    def chat(self, system, payload, validator, stage):
        request = {
            "model": self.config.chat_model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            "temperature": 0,
            "enable_thinking": False,
            "max_tokens": 4096,
            "response_format": {"type": "json_object"},
        }

        def decode(raw):
            content = raw["choices"][0]["message"]["content"]
            if not isinstance(content, str):
                raise ValueError("chat content must be text")
            text = content.strip()
            if text.startswith("```") and text.endswith("```"):
                text = text.split("\n", 1)[1].rsplit("```", 1)[0].strip()
            value = json.loads(text)
            if not isinstance(value, dict):
                raise ValueError("chat output must be a JSON object")
            return validator(value)

        return self._call("chat/completions", request, decode, stage)

    def embed(self, texts):
        if not texts:
            return []
        if len(texts) > 10:
            raise ValueError("embedding batch cannot exceed 10 texts")
        request = {"model": self.config.embedding_model, "input": texts,
                   "dimensions": self.config.dimensions, "encoding_format": "float"}

        def decode(raw):
            data = raw["data"]
            if len(data) != len(texts) or sorted(row["index"] for row in data) != list(range(len(texts))):
                raise ValueError("embedding response indexes do not match input")
            vectors = [row["embedding"] for row in sorted(data, key=lambda item: item["index"])]
            expected = self.fixture["dimensions"] if self.fixture else self.config.dimensions
            for vector in vectors:
                if not isinstance(vector, list) or len(vector) != expected:
                    raise ValueError("embedding dimension mismatch")
                if any(isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x)
                       for x in vector) or not any(vector):
                    raise ValueError("embedding contains non-finite values or is zero")
            return vectors

        return self._call("embeddings", request, decode, "embedding")

    def _call(self, endpoint, request, decode, stage):
        key = digest({"config": self.public_config(), "endpoint": endpoint, "request": request})
        cache_path = self.cache_dir / f"{key}.json"
        if self.use_cache and cache_path.exists():
            try:
                cached = read_json(cache_path)
                if cached["request_hash"] != key:
                    raise ValueError("cache key mismatch")
                result = decode(cached["response"])
                self._event(stage, key, True, True, 0, {}, None)
                return result
            except (ValueError, TypeError, KeyError, IndexError, OSError):
                # Invalid cached outputs must be revalidated and regenerated.
                pass
        last_error = None
        for attempt in range(self.config.max_attempts):
            started = time.perf_counter()
            raw = {}
            try:
                raw = self._transport(endpoint, request, stage)
                result = decode(raw)
                if self.use_cache:
                    write_json(cache_path, {"request_hash": key, "created_at": timestamp(), "response": raw})
                self._event(stage, key, False, True, time.perf_counter() - started, raw.get("usage", {}), None)
                return result
            except (requests.RequestException, ValueError, TypeError, KeyError, IndexError, RuntimeError) as error:
                last_error = error
                self._event(stage, key, False, False, time.perf_counter() - started,
                            raw.get("usage", {}) if isinstance(raw, dict) else {}, self.safe_error(error))
                if isinstance(error, PermanentModelError):
                    break
                if attempt + 1 < self.config.max_attempts:
                    time.sleep(self.retry_delay * (2 ** attempt))
        error_type = FatalModelError if isinstance(last_error, FatalModelError) else RuntimeError
        raise error_type(f"{stage} failed: {self.safe_error(last_error)}") from last_error

    def _transport(self, endpoint, request, stage):
        if self.fixture:
            return self._simulate(endpoint, request, stage)
        if not self.api_key:
            raise FatalModelError("DASHSCOPE_API_KEY is not set")
        response = self.session.post(
            self.config.base_url.rstrip("/") + "/" + endpoint,
            headers={"Authorization": f"Bearer {self.api_key}"},
            json=request, timeout=self.config.timeout,
        )
        if response.status_code >= 400:
            if response.status_code in {401, 403}:
                raise FatalModelError(f"HTTP {response.status_code} from {endpoint}")
            error_type = RuntimeError if response.status_code in {408, 429} or response.status_code >= 500 else PermanentModelError
            raise error_type(f"HTTP {response.status_code} from {endpoint}")
        result = response.json()
        if not isinstance(result, dict):
            raise ValueError("provider response must be a JSON object")
        return result

    def safe_error(self, error):
        text = f"{type(error).__name__}: {error}"
        return text.replace(self.api_key, "[redacted]")[:500] if self.api_key else text[:500]

    def _event(self, stage, key, cached, success, elapsed, usage, error):
        usage = usage if isinstance(usage, dict) else {}
        event = {"at": timestamp(), "stage": stage, "request_hash": key, "cached": cached,
                 "mode": self.active_mode,
                 "success": success, "backend": self.backend, "elapsed_ms": round(elapsed * 1000),
                 "input_tokens": usage.get("prompt_tokens", usage.get("input_tokens")),
                 "output_tokens": usage.get("completion_tokens", usage.get("output_tokens")),
                 "total_tokens": usage.get("total_tokens"), "error": error}
        self.events.append(event)
        self.telemetry_path.parent.mkdir(parents=True, exist_ok=True)
        with self.telemetry_path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(event, ensure_ascii=False) + "\n")

    def metrics(self, mode=None):
        events = [event for event in self.events if mode is None or event.get("mode") == mode]
        calls = [event for event in events if not event["cached"]]
        tokens = [event["total_tokens"] for event in calls if event["total_tokens"] is not None]
        return {"backend": self.backend, "attempts": len(calls),
                "real_provider_attempts": len(calls) if not self.fixture else 0,
                "cache_hits": sum(event["cached"] for event in events),
                "failed_attempts": sum(not event["success"] for event in calls),
                "reported_total_tokens": sum(tokens) if tokens else None,
                "calls_with_token_usage": len(tokens), "monetary_cost": None,
                "elapsed_ms": sum(event["elapsed_ms"] for event in calls),
                "events_this_invocation": sum(mode is None or event.get("mode") == mode
                                              for event in self.events[self.initial_events:])}

    def _simulate(self, endpoint, request, stage):
        documents = self.fixture["documents"]
        if endpoint == "embeddings":
            vectors = {}
            for doc in documents:
                vectors[doc["title"] + "\n" + doc["abstract"]] = doc["vector"]
                for question in doc["extraction"]["questions"]:
                    vectors[question["research_object"] + "\n" + question["question"]] = doc["vector"]
            data = [{"index": index, "embedding": vectors[text]} for index, text in enumerate(request["input"])]
            return {"data": data, "usage": {"total_tokens": 0}}
        payload = json.loads(request["messages"][-1]["content"])
        known = {doc["document_id"]: doc for doc in documents}
        if stage == "extraction":
            result = known[payload["document_id"]]["extraction"]
        else:
            groups = {}
            for member in payload["members"]:
                groups.setdefault(known[member["document_id"]]["topic"], []).append(member)
            common = []
            for topic, members in groups.items():
                members = [member for member in members if known[member["document_id"]]["extraction"]["questions"]]
                if not members:
                    continue
                common.append({"question": f"SIMULATED: What do these papers investigate about {topic}?",
                               "member_ids": [m["member_id"] for m in members],
                               "evidence": [{"member_id": m["member_id"],
                                             "quote": known[m["document_id"]]["abstract"]} for m in members]})
            result = {"topic": "SIMULATED: " + "; ".join(groups),
                      "status": "READY" if len(groups) == 1 and common else "NEEDS_SPLIT", "common_questions": common}
        return {"choices": [{"message": {"content": json.dumps(result)}}],
                "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}}
