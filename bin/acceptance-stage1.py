#!/usr/bin/env python3
"""T1.16 stage-1 real-acceptance driver.

Pure logic (scenario table, reservation ledger, stop policy, pacing, redaction,
request templating, dry-run) is importable WITHOUT openai/anthropic and never
opens a socket. The official SDKs are imported lazily inside ``live_send`` only.

Safety invariants enforced here:
  * append-only JSONL reservation persisted (fsync) BEFORE any send; a crash
    after reserve but before confirm still counts the call as USED;
  * one run id across resume; stop new sends at 600s OR 60 calls, whichever first;
  * concurrency 1, max_tokens 1024, SDK retries 0, gateway fallbacks [];
  * credentials ONLY from the environment variable named by API_KEY_ENV; the
    value never enters the repo, the CLI, the ledger or the report;
  * gateway RSS/CPU is observed only (no SLO inference).

Run the no-network unit tests:
    python3 -m unittest discover -s bin -p 'test_acceptance_stage1.py'
Dry-run (no network, no credentials required):
    python3 bin/acceptance-stage1.py --dry-run --out-dir /tmp/acceptance-dry
"""
import argparse
import copy
import hashlib
import json
import os
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

API_KEY_ENV = "LLMGW_ACCEPTANCE_API_KEY"
PROVIDER_LABEL = "deepseek"

REPO = Path(__file__).resolve().parent.parent
DEFAULT_SCENARIOS = REPO / "tools" / "acceptance" / "stage1" / "scenarios.json"
DEFAULT_FIXTURES = REPO / "tools" / "acceptance" / "stage1" / "fixtures"

ENTRIES = ("chat", "responses", "messages")
KIND_COUNTS = {"text": 2, "stream": 1, "tool": 2, "thinking": 2, "image": 1, "cancel": 1}
TOKEN_FIELD = {"chat": "max_tokens", "messages": "max_tokens", "responses": "max_output_tokens"}
MAX_TOKENS = 1024
WINDOW_SECONDS = 600
CAP = 60


class ConfigError(Exception):
    """Invalid configuration, scenario table or invariant."""


class CapReachedError(Exception):
    """The hard call cap has been reached; no fresh reservation is allowed."""


# --------------------------------------------------------------------------- #
# Scenario table
# --------------------------------------------------------------------------- #
def load_scenarios(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    scenarios = data["scenarios"] if isinstance(data, dict) else data
    if not isinstance(scenarios, list):
        raise ConfigError("scenarios table must be a list")
    return scenarios


def validate_scenarios(scenarios):
    if len(scenarios) != 27:
        raise ConfigError(f"expected 27 scenarios, got {len(scenarios)}")
    ids = []
    for s in scenarios:
        for key in ("id", "entry", "kind", "expectation", "fixture", "step"):
            if key not in s:
                raise ConfigError(f"scenario missing {key}: {s}")
        if not isinstance(s["id"], str) or not s["id"]:
            raise ConfigError(f"scenario id must be a non-empty string: {s}")
        if s["entry"] not in ENTRIES:
            raise ConfigError(f"unknown entry {s['entry']}")
        if s["kind"] not in KIND_COUNTS:
            raise ConfigError(f"unknown kind {s['kind']}")
        if s["expectation"] not in ("must-succeed", "expected-rejection"):
            raise ConfigError(f"unknown expectation {s['expectation']}")
        ids.append(s["id"])
    if len(ids) != len(set(ids)):
        raise ConfigError("scenario ids must be unique")
    for entry in ENTRIES:
        rows = [s for s in scenarios if s["entry"] == entry]
        if len(rows) != 9:
            raise ConfigError(f"entry {entry} must have 9 scenarios, got {len(rows)}")
        counts = {}
        for s in rows:
            counts[s["kind"]] = counts.get(s["kind"], 0) + 1
        if counts != KIND_COUNTS:
            raise ConfigError(f"entry {entry} kind counts {counts} != {KIND_COUNTS}")
        neg = [s for s in rows if s["expectation"] == "expected-rejection"]
        if len(neg) != 1 or neg[0]["kind"] != "text":
            raise ConfigError(f"entry {entry} must have exactly one text expected-rejection")
    must = sum(1 for s in scenarios if s["expectation"] == "must-succeed")
    if must != 24:
        raise ConfigError(f"expected 24 must-succeed scenarios, got {must}")


def rotation_order(scenarios):
    """Round-robin across entries so the first three sends are chat/responses/messages."""
    buckets = {entry: [s for s in scenarios if s["entry"] == entry] for entry in ENTRIES}
    order = []
    while any(buckets.values()):
        for entry in ENTRIES:
            if buckets[entry]:
                order.append(buckets[entry].pop(0))
    return order


# --------------------------------------------------------------------------- #
# Request templating + hashing
# --------------------------------------------------------------------------- #
def build_request(scenario, fixtures_dir, model):
    fixture = Path(fixtures_dir) / scenario["fixture"]
    data = json.loads(fixture.read_text(encoding="utf-8"))
    step = next((st for st in data["steps"] if st["name"] == scenario["step"]), None)
    if step is None:
        raise ConfigError(f"step {scenario['step']} not found in {fixture}")
    request = copy.deepcopy(step["request"])
    request["model"] = model
    request[TOKEN_FIELD[scenario["entry"]]] = MAX_TOKENS
    return request


def request_sha256(obj):
    blob = json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------- #
# Append-only reservation ledger (crash -> used)
# --------------------------------------------------------------------------- #
class ReservationLedger:
    def __init__(self, path, cap=CAP):
        self.path = Path(path)
        self.cap = cap
        self._results = []
        self.used = 0
        self.run_id = None
        if self.path.exists():
            for line in self.path.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                record = json.loads(line)
                kind = record.get("type")
                if kind == "meta":
                    self.run_id = record.get("run_id")
                    self.cap = record.get("cap", cap)
                elif kind == "reservation":
                    self.used += 1
                    if self.run_id is None:
                        self.run_id = record.get("run_id")
                elif kind == "result":
                    self._results.append(record)
        created = not self.path.exists()
        if self.run_id is None:
            self.run_id = uuid.uuid4().hex
        self._fh = self.path.open("a", encoding="utf-8")
        if created:
            self._append({"type": "meta", "run_id": self.run_id, "cap": self.cap})

    def _append(self, record):
        self._fh.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
        self._fh.flush()
        os.fsync(self._fh.fileno())

    @property
    def remaining(self):
        return max(0, self.cap - self.used)

    def reserve(self, scenario_id, request_sha256):
        if self.used >= self.cap:
            raise CapReachedError(f"cap {self.cap} reached")
        self.used += 1
        seq = self.used
        self._append({
            "type": "reservation", "run_id": self.run_id, "seq": seq,
            "scenario_id": scenario_id, "request_sha256": request_sha256,
            "ts": time.time(),
        })
        return seq

    def record_result(self, seq, payload):
        record = {"type": "result", "run_id": self.run_id, "seq": seq}
        record.update(payload)
        self._append(record)
        self._results.append(record)

    def results(self):
        return list(self._results)

    def close(self):
        if self._fh is not None and not self._fh.closed:
            self._fh.flush()
            os.fsync(self._fh.fileno())
            self._fh.close()


# --------------------------------------------------------------------------- #
# Stop policy + window guard (injectable clock)
# --------------------------------------------------------------------------- #
@dataclass
class StopPolicy:
    window_seconds: int = WINDOW_SECONDS
    cap: int = CAP

    def should_stop(self, elapsed, used):
        if elapsed >= self.window_seconds:
            return (True, "window")
        if used >= self.cap:
            return (True, "cap")
        return (False, None)


class WindowGuard:
    def __init__(self, clock, policy, ledger, start):
        self._clock = clock
        self._policy = policy
        self._ledger = ledger
        self._start = start

    def stop_reason(self):
        elapsed = self._clock() - self._start
        return self._policy.should_stop(elapsed, self._ledger.used)


class Pacer:
    """Low-rate rotation: spread ``count`` sends across the window, never a burst."""

    def __init__(self, count, window_seconds=WINDOW_SECONDS):
        self.count = count
        self.window_seconds = window_seconds

    def send_time(self, index):
        return (index + 1) * self.window_seconds / (self.count + 1)


# --------------------------------------------------------------------------- #
# Redaction + credentials
# --------------------------------------------------------------------------- #
def _redact(value, secret):
    if not secret:
        return value
    if isinstance(value, str):
        return value.replace(secret, "[REDACTED]")
    if isinstance(value, dict):
        return {k: _redact(v, secret) for k, v in value.items()}
    if isinstance(value, list):
        return [_redact(v, secret) for v in value]
    return value


def make_result_record(scenario_id, seq, ts, request_sha256, classification,
                       usage, ttft, elapsed, secret, note=None):
    record = {
        "scenario_id": scenario_id,
        "seq": seq,
        "ts": ts,
        "request_sha256": request_sha256,
        "classification": classification,
        "usage": usage,
        "ttft": ttft,
        "elapsed": elapsed,
        "api_key_env": API_KEY_ENV,
        "provider": PROVIDER_LABEL,
    }
    if note is not None:
        record["note"] = note
    return _redact(record, secret)


def read_api_key():
    value = os.environ.get(API_KEY_ENV)
    if not value:
        raise ConfigError(f"{API_KEY_ENV} is not set; credentials must come from the environment")
    return value


# --------------------------------------------------------------------------- #
# Resource sampling (observation only, injectable)
# --------------------------------------------------------------------------- #
class NullResourceSampler:
    def sample(self):
        return {"observed": False, "rss_bytes": None, "cpu_percent": None}


class FakeResourceSampler:
    def __init__(self, samples):
        self._samples = list(samples)
        self._index = 0

    def sample(self):
        if not self._samples:
            return {"observed": False, "rss_bytes": None, "cpu_percent": None}
        sample = self._samples[self._index % len(self._samples)]
        self._index += 1
        return {**sample, "observed": True}


class ProcResourceSampler:
    """Observe gateway RSS/CPU from /proc (Linux) or psutil if available. Never infers SLO."""

    def __init__(self, pid):
        self.pid = pid
        self._last_cpu = None
        self._last_time = None

    def sample(self):
        if not self.pid:
            return {"observed": False, "rss_bytes": None, "cpu_percent": None}
        try:
            statm = Path(f"/proc/{self.pid}/statm").read_text().split()
            rss_bytes = int(statm[1]) * os.sysconf("SC_PAGE_SIZE")
        except (OSError, ValueError, IndexError):
            return {"observed": False, "rss_bytes": None, "cpu_percent": None}
        cpu_percent = None
        try:
            fields = Path(f"/proc/{self.pid}/stat").read_text().split()
            ticks = int(fields[13]) + int(fields[14])
            now = time.monotonic()
            if self._last_cpu is not None:
                dt = now - self._last_time
                if dt > 0:
                    cpu_percent = 100.0 * (ticks - self._last_cpu) / os.sysconf("SC_CLK_TCK") / dt
            self._last_cpu = ticks
            self._last_time = now
        except (OSError, ValueError, IndexError):
            pass
        return {"observed": True, "rss_bytes": rss_bytes, "cpu_percent": cpu_percent}


# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #
@dataclass
class AcceptanceConfig:
    scenarios_path: Path = DEFAULT_SCENARIOS
    fixtures_dir: Path = DEFAULT_FIXTURES
    base_url: str = "http://127.0.0.1:8080"
    model: str = "deepseek-flash"
    route_id: str = "acceptance"
    gateway_pid: object = None
    max_tokens: int = MAX_TOKENS
    concurrency: int = 1
    max_retries: int = 0
    fallbacks: tuple = ()
    cap: int = CAP
    window_seconds: int = WINDOW_SECONDS

    def validate(self):
        if self.max_tokens != MAX_TOKENS:
            raise ConfigError(f"max_tokens must be {MAX_TOKENS}")
        if self.concurrency != 1:
            raise ConfigError("concurrency must be 1")
        if self.max_retries != 0:
            raise ConfigError("max_retries must be 0 (no SDK retry)")
        if len(self.fallbacks) != 0:
            raise ConfigError("fallbacks must be empty (no gateway fallback)")
        if not self.base_url:
            raise ConfigError("base_url is required")


def build_sdk_config(config):
    return {
        "base_url": config.base_url,
        "model": config.model,
        "max_retries": config.max_retries,
        "fallbacks": list(config.fallbacks),
        "max_tokens": config.max_tokens,
        "concurrency": config.concurrency,
    }


# --------------------------------------------------------------------------- #
# Dry-run (no network, no credentials required)
# --------------------------------------------------------------------------- #
def _prove_crash_resume(out_dir):
    scratch = Path(out_dir) / "crash-resume-probe.jsonl"
    if scratch.exists():
        scratch.unlink()
    ledger = ReservationLedger(scratch, cap=CAP)
    for i in range(3):
        ledger.reserve(f"probe-{i}", f"sha-{i}")
    ledger.close()  # crash: no result records written
    reopened = ReservationLedger(scratch)
    ok = reopened.used == 3 and len(reopened.results()) == 0
    reopened.close()
    scratch.unlink()
    return ok


def run_dry_run(config, out_dir, sampler):
    config.validate()
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    scenarios = load_scenarios(config.scenarios_path)
    validate_scenarios(scenarios)
    order = rotation_order(scenarios)
    secret = os.environ.get(API_KEY_ENV, "")

    ledger = ReservationLedger(out_dir / "ledger.jsonl", cap=config.cap)
    pacer = Pacer(count=len(order), window_seconds=config.window_seconds)
    samples = []
    rows = []
    for index, scenario in enumerate(order):
        request = build_request(scenario, config.fixtures_dir, config.model)
        sha = request_sha256(request)
        seq = ledger.reserve(scenario["id"], sha)
        sample = sampler.sample()
        samples.append(sample)
        record = make_result_record(
            scenario_id=scenario["id"], seq=seq, ts=time.time(), request_sha256=sha,
            classification="dry-run", usage=None, ttft=None, elapsed=None, secret=secret,
            note=f"planned send at t={pacer.send_time(index):.1f}s",
        )
        ledger.record_result(seq, record)
        rows.append({
            "id": scenario["id"], "entry": scenario["entry"], "kind": scenario["kind"],
            "expectation": scenario["expectation"], "fixture": scenario["fixture"],
            "step": scenario["step"], "request_sha256": sha, "classification": "dry-run",
        })
    run_id = ledger.run_id
    used = ledger.used
    ledger.close()

    crash_ok = _prove_crash_resume(out_dir)
    report = {
        "run_id": run_id,
        "mode": "dry-run",
        "scenario_count": len(order),
        "used": used,
        "cap": config.cap,
        "window_seconds": config.window_seconds,
        "max_tokens": config.max_tokens,
        "concurrency": config.concurrency,
        "max_retries": config.max_retries,
        "fallbacks": list(config.fallbacks),
        "api_key_env": API_KEY_ENV,
        "provider": PROVIDER_LABEL,
        "scenarios": rows,
        "resource_samples": samples,
        "ledger_crash_resume_ok": crash_ok,
    }
    report_path = out_dir / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {**report, "report_path": str(report_path), "ledger_path": str(out_dir / "ledger.jsonl")}


# --------------------------------------------------------------------------- #
# Live send (lazy SDK imports; only path that touches the network)
# --------------------------------------------------------------------------- #
def live_send(config, scenario, request, secret, sampler):
    """Send one templated request through the gateway using the official SDK.

    SDKs are imported here so the pure logic stays importable without them.
    Returns a redacted result record. Classification: success / expected-rejection
    / provider-error / unknown.
    """
    import anthropic  # lazy: keep the pure logic importable without SDKs
    import openai  # lazy: only the live-send path needs the official clients

    entry = scenario["entry"]
    started = time.monotonic()
    ttft = None
    usage = None
    classification = "unknown"
    note = None
    sample = sampler.sample()
    try:
        if entry == "messages":
            client = anthropic.Anthropic(api_key=secret, base_url=config.base_url,
                                         max_retries=config.max_retries, timeout=120)
            kwargs = {k: v for k, v in request.items() if k != "stream"}
            if request.get("stream"):
                with client.messages.stream(**kwargs) as stream:
                    for _ in stream.text_stream:
                        if ttft is None:
                            ttft = time.monotonic() - started
                    final = stream.get_final_message()
                usage = getattr(final, "usage", None)
                usage = usage.model_dump() if hasattr(usage, "model_dump") else usage
            else:
                message = client.messages.create(**kwargs)
                ttft = time.monotonic() - started
                usage = message.usage.model_dump() if hasattr(message.usage, "model_dump") else message.usage
            classification = "success"
        else:
            client = openai.OpenAI(api_key=secret, base_url=config.base_url.rstrip("/") + "/v1",
                                   max_retries=config.max_retries, timeout=120)
            if entry == "responses":
                kwargs = {k: v for k, v in request.items() if k != "stream"}
                if request.get("stream"):
                    with client.responses.create(stream=True, **kwargs) as stream:
                        for event in stream:
                            if ttft is None:
                                ttft = time.monotonic() - started
                        final = getattr(stream, "get_final_response", lambda: None)()
                    usage = getattr(final, "usage", None) if final else None
                    usage = usage.model_dump() if hasattr(usage, "model_dump") else usage
                else:
                    response = client.responses.create(**kwargs)
                    ttft = time.monotonic() - started
                    usage = response.usage.model_dump() if hasattr(response.usage, "model_dump") else response.usage
                classification = "success"
            else:
                kwargs = dict(request)
                if kwargs.get("stream"):
                    with client.chat.completions.create(**kwargs) as stream:
                        for chunk in stream:
                            if ttft is None:
                                ttft = time.monotonic() - started
                            if getattr(chunk, "usage", None):
                                usage = chunk.usage.model_dump() if hasattr(chunk.usage, "model_dump") else chunk.usage
                else:
                    completion = client.chat.completions.create(**kwargs)
                    ttft = time.monotonic() - started
                    usage = completion.usage.model_dump() if hasattr(completion.usage, "model_dump") else completion.usage
                classification = "success"
    except (openai.APIStatusError, anthropic.APIStatusError) as error:
        status = getattr(error, "status_code", None)
        if scenario["expectation"] == "expected-rejection" and status == 400:
            classification = "expected-rejection"
        else:
            classification = "provider-error"
        note = f"status={status}"
    except Exception as error:  # noqa: BLE001 - record, never crash the window
        classification = "unknown"
        note = f"{type(error).__name__}"
    elapsed = time.monotonic() - started
    return make_result_record(
        scenario_id=scenario["id"], seq=0, ts=time.time(),
        request_sha256=request_sha256(request), classification=classification,
        usage=usage, ttft=ttft, elapsed=elapsed, secret=secret, note=note,
    ), sample


def run_live(config, out_dir, sampler):
    config.validate()
    secret = read_api_key()
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    scenarios = load_scenarios(config.scenarios_path)
    validate_scenarios(scenarios)
    order = rotation_order(scenarios)
    ledger = ReservationLedger(out_dir / "ledger.jsonl", cap=config.cap)
    policy = StopPolicy(window_seconds=config.window_seconds, cap=config.cap)
    guard = WindowGuard(time.monotonic, policy, ledger, start=time.monotonic())
    pacer = Pacer(count=len(order), window_seconds=config.window_seconds)
    started = time.monotonic()
    rows = []
    for index, scenario in enumerate(order):
        stop, reason = guard.stop_reason()
        if stop:
            break
        target = started + pacer.send_time(index)
        delay = target - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        request = build_request(scenario, config.fixtures_dir, config.model)
        sha = request_sha256(request)
        seq = ledger.reserve(scenario["id"], sha)  # persisted BEFORE send
        record, sample = live_send(config, scenario, request, secret, sampler)
        record["seq"] = seq
        ledger.record_result(seq, record)
        rows.append({**record, "resource_sample": sample})
    run_id = ledger.run_id
    used = ledger.used
    ledger.close()
    report = {
        "run_id": run_id, "mode": "live", "scenario_count": len(order), "used": used,
        "cap": config.cap, "window_seconds": config.window_seconds,
        "max_tokens": config.max_tokens, "concurrency": config.concurrency,
        "max_retries": config.max_retries, "fallbacks": list(config.fallbacks),
        "api_key_env": API_KEY_ENV, "provider": PROVIDER_LABEL, "scenarios": rows,
    }
    report_path = out_dir / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {**report, "report_path": str(report_path), "ledger_path": str(out_dir / "ledger.jsonl")}


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="validate config/count/report paths without network")
    parser.add_argument("--scenarios", type=Path, default=DEFAULT_SCENARIOS)
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURES)
    parser.add_argument("--out-dir", type=Path, default=Path("acceptance-out"))
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--model", default="deepseek-flash")
    parser.add_argument("--route-id", default="acceptance")
    parser.add_argument("--gateway-pid", type=int, default=None)
    parser.add_argument("--window-seconds", type=int, default=WINDOW_SECONDS)
    parser.add_argument("--cap", type=int, default=CAP)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    config = AcceptanceConfig(
        scenarios_path=args.scenarios, fixtures_dir=args.fixtures, base_url=args.base_url,
        model=args.model, route_id=args.route_id, gateway_pid=args.gateway_pid,
        cap=args.cap, window_seconds=args.window_seconds,
    )
    if args.dry_run:
        result = run_dry_run(config, args.out_dir, sampler=NullResourceSampler())
    else:
        sampler = ProcResourceSampler(args.gateway_pid) if args.gateway_pid else NullResourceSampler()
        result = run_live(config, args.out_dir, sampler=sampler)
    print(json.dumps({k: result[k] for k in ("run_id", "mode", "scenario_count", "used", "cap",
                                             "report_path", "ledger_path")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())