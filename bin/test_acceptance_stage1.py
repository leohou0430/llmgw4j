"""T1.16 stage-1 real-acceptance driver unittest (NO network, system python3, no SDK deps).

The driver's pure logic must be importable WITHOUT openai/anthropic at module top
level; SDKs are imported lazily inside the live-send path only. These tests never
open a socket and never call the live-send path.

Run: python3 -m unittest discover -s bin -p 'test_acceptance_stage1.py'
"""
import importlib.util
import json
import os
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

BIN = Path(__file__).resolve().parent
REPO = BIN.parent
DRIVER_PATH = BIN / "acceptance-stage1.py"
SCENARIOS_PATH = REPO / "tools" / "acceptance" / "stage1" / "scenarios.json"
FIXTURES_DIR = REPO / "tools" / "acceptance" / "stage1" / "fixtures"


def _load_driver():
    spec = importlib.util.spec_from_file_location("acceptance_stage1", DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["acceptance_stage1"] = module
    spec.loader.exec_module(module)
    return module


mod = _load_driver()


class FakeClock:
    """Injectable monotonic clock for stop-logic tests."""

    def __init__(self, start=0.0):
        self._now = start

    def set(self, value):
        self._now = value

    def advance(self, delta):
        self._now += delta

    def __call__(self):
        return self._now


def _config(tmp, **overrides):
    kwargs = dict(
        scenarios_path=SCENARIOS_PATH,
        fixtures_dir=FIXTURES_DIR,
        base_url="http://127.0.0.1:8080",
        model="deepseek-flash",
        route_id="acceptance",
        gateway_pid=None,
    )
    kwargs.update(overrides)
    return mod.AcceptanceConfig(**kwargs)


class ScenarioTableTests(unittest.TestCase):
    def setUp(self):
        self.scenarios = mod.load_scenarios(SCENARIOS_PATH)

    def test_exactly_27_entries(self):
        self.assertEqual(len(self.scenarios), 27)

    def test_nine_per_entry(self):
        for entry in ("chat", "responses", "messages"):
            count = sum(1 for s in self.scenarios if s["entry"] == entry)
            self.assertEqual(count, 9, entry)

    def test_required_kind_counts_per_entry(self):
        expected = {"text": 2, "stream": 1, "tool": 2, "thinking": 2, "image": 1, "cancel": 1}
        for entry in ("chat", "responses", "messages"):
            counts = {}
            for s in self.scenarios:
                if s["entry"] == entry:
                    counts[s["kind"]] = counts.get(s["kind"], 0) + 1
            self.assertEqual(counts, expected, entry)

    def test_stable_unique_ids(self):
        ids = [s["id"] for s in self.scenarios]
        self.assertEqual(len(ids), len(set(ids)))
        for sid in ids:
            self.assertIsInstance(sid, str)
            self.assertTrue(sid)

    def test_expectation_split(self):
        must = [s for s in self.scenarios if s["expectation"] == "must-succeed"]
        neg = [s for s in self.scenarios if s["expectation"] == "expected-rejection"]
        self.assertEqual(len(must), 24)
        self.assertEqual(len(neg), 3)
        # one expected-rejection negative per entry, all of kind text (s7 unknown-field)
        for entry in ("chat", "responses", "messages"):
            entry_neg = [s for s in neg if s["entry"] == entry]
            self.assertEqual(len(entry_neg), 1)
            self.assertEqual(entry_neg[0]["kind"], "text")

    def test_validate_scenarios_accepts_table(self):
        mod.validate_scenarios(self.scenarios)  # must not raise

    def test_validate_rejects_wrong_count(self):
        with self.assertRaises(mod.ConfigError):
            mod.validate_scenarios(self.scenarios[:26])

    def test_every_scenario_maps_to_real_fixture_step(self):
        for s in self.scenarios:
            fixture = FIXTURES_DIR / s["fixture"]
            self.assertTrue(fixture.exists(), s["fixture"])
            data = json.loads(fixture.read_text(encoding="utf-8"))
            names = [st["name"] for st in data["steps"]]
            self.assertIn(s["step"], names, s["id"])


class ReservationLedgerTests(unittest.TestCase):
    def test_crash_before_send_counts_used(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ledger.jsonl"
            ledger = mod.ReservationLedger(path, cap=60)
            for i in range(5):
                ledger.reserve(f"scenario-{i}", f"sha-{i}")
            # simulate crash: close WITHOUT writing any result records
            ledger.close()
            reopened = mod.ReservationLedger(path)
            # unknown commits (reserved, never confirmed) still count as USED
            self.assertEqual(reopened.used, 5)
            self.assertEqual(len(reopened.results()), 0)
            reopened.close()

    def test_resume_keeps_run_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ledger.jsonl"
            first = mod.ReservationLedger(path, cap=60)
            run_id = first.run_id
            first.reserve("s", "sha")
            first.close()
            resumed = mod.ReservationLedger(path)
            self.assertEqual(resumed.run_id, run_id)
            self.assertEqual(resumed.used, 1)
            resumed.close()

    def test_cap_blocks_fresh_reservation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ledger.jsonl"
            ledger = mod.ReservationLedger(path, cap=3)
            ledger.reserve("a", "sha")
            ledger.reserve("b", "sha")
            ledger.reserve("c", "sha")
            self.assertEqual(ledger.used, 3)
            self.assertEqual(ledger.remaining, 0)
            with self.assertRaises(mod.CapReachedError):
                ledger.reserve("d", "sha")
            ledger.close()

    def test_reservation_persisted_with_fsync_append_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ledger.jsonl"
            ledger = mod.ReservationLedger(path, cap=60)
            ledger.reserve("a", "sha")
            ledger.reserve("b", "sha")
            ledger.close()
            lines = [json.loads(x) for x in path.read_text().splitlines() if x.strip()]
            reservations = [r for r in lines if r.get("type") == "reservation"]
            self.assertEqual(len(reservations), 2)
            # append-only: reopening and reserving adds, never rewrites
            again = mod.ReservationLedger(path)
            again.reserve("c", "sha")
            again.close()
            lines2 = [json.loads(x) for x in path.read_text().splitlines() if x.strip()]
            self.assertEqual(len([r for r in lines2 if r.get("type") == "reservation"]), 3)

    def test_result_record_does_not_reduce_used(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ledger.jsonl"
            ledger = mod.ReservationLedger(path, cap=60)
            seq = ledger.reserve("a", "sha")
            ledger.record_result(seq, {"classification": "success"})
            self.assertEqual(ledger.used, 1)
            ledger.close()


class StopLogicTests(unittest.TestCase):
    def test_window_branch_with_injectable_clock(self):
        clock = FakeClock(start=1000.0)
        policy = mod.StopPolicy(window_seconds=600, cap=60)
        ledger = mock.Mock(used=0)
        guard = mod.WindowGuard(clock, policy, ledger, start=1000.0)
        clock.set(1599.0)
        self.assertEqual(guard.stop_reason(), (False, None))
        clock.set(1600.0)  # exactly 600s elapsed
        stop, reason = guard.stop_reason()
        self.assertTrue(stop)
        self.assertEqual(reason, "window")

    def test_cap_branch_with_injectable_clock(self):
        clock = FakeClock(start=0.0)
        policy = mod.StopPolicy(window_seconds=600, cap=60)
        ledger = mock.Mock(used=60)
        guard = mod.WindowGuard(clock, policy, ledger, start=0.0)
        clock.set(10.0)  # far below window
        stop, reason = guard.stop_reason()
        self.assertTrue(stop)
        self.assertEqual(reason, "cap")

    def test_neither_continues(self):
        policy = mod.StopPolicy(window_seconds=600, cap=60)
        self.assertEqual(policy.should_stop(599.0, 59), (False, None))

    def test_whichever_first(self):
        policy = mod.StopPolicy(window_seconds=600, cap=60)
        self.assertEqual(policy.should_stop(600.0, 0), (True, "window"))
        self.assertEqual(policy.should_stop(0.0, 60), (True, "cap"))
        self.assertEqual(policy.should_stop(700.0, 60)[0], True)

    def test_window_does_not_auto_extend(self):
        clock = FakeClock(start=0.0)
        policy = mod.StopPolicy(window_seconds=600, cap=60)
        guard = mod.WindowGuard(clock, policy, mock.Mock(used=1), start=0.0)
        clock.set(601.0)
        self.assertTrue(guard.stop_reason()[0])
        # advancing further never re-opens the window
        clock.set(1200.0)
        self.assertTrue(guard.stop_reason()[0])


class PacingTests(unittest.TestCase):
    def test_low_rate_rotation_spread_across_window(self):
        pacer = mod.Pacer(count=27, window_seconds=600)
        times = [pacer.send_time(i) for i in range(27)]
        self.assertLessEqual(times[-1], 600.0)
        # not a burst: consecutive sends are spaced, not all at t=0
        self.assertGreater(times[1] - times[0], 1.0)
        self.assertGreater(times[-1] - times[0], 300.0)

    def test_rotation_interleaves_entries(self):
        scenarios = mod.load_scenarios(SCENARIOS_PATH)
        order = mod.rotation_order(scenarios)
        self.assertEqual(len(order), 27)
        first_three = [s["entry"] for s in order[:3]]
        self.assertEqual(set(first_three), {"chat", "responses", "messages"})


class RedactionTests(unittest.TestCase):
    SECRET = "sk-FAKE-SECRET-DO-NOT-LEAK-1234567890"

    def test_secret_never_in_result_record(self):
        record = mod.make_result_record(
            scenario_id="chat-s1-text-multiturn",
            seq=1,
            ts=123.0,
            request_sha256="abc",
            classification="success",
            usage={"total_tokens": 8},
            ttft=0.1,
            elapsed=0.5,
            secret=self.SECRET,
        )
        blob = json.dumps(record)
        self.assertNotIn(self.SECRET, blob)
        self.assertEqual(record["api_key_env"], mod.API_KEY_ENV)
        self.assertEqual(record["provider"], mod.PROVIDER_LABEL)
        self.assertNotIn("api_key", record)
        self.assertNotIn("secret", record)

    def test_redact_scrubs_secret_from_any_field(self):
        record = mod.make_result_record(
            scenario_id="x", seq=1, ts=0.0, request_sha256="a",
            classification="unknown", usage=None, ttft=None, elapsed=None,
            secret=self.SECRET, note=f"accidental {self.SECRET} leak",
        )
        self.assertNotIn(self.SECRET, json.dumps(record))

    def test_read_api_key_from_env_only(self):
        with mock.patch.dict(os.environ, {mod.API_KEY_ENV: self.SECRET}):
            self.assertEqual(mod.read_api_key(), self.SECRET)
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(mod.ConfigError):
                mod.read_api_key()

    def test_cli_has_no_api_key_argument(self):
        parser = mod.build_parser()
        for action in parser._actions:
            for opt in action.option_strings:
                self.assertNotIn("api-key", opt)
                self.assertNotIn("api_key", opt)
                self.assertNotIn("secret", opt)
                self.assertNotIn("token", opt)
        with self.assertRaises(SystemExit):
            parser.parse_args(["--api-key", self.SECRET])

    def test_dry_run_report_and_ledger_never_contain_secret(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {mod.API_KEY_ENV: self.SECRET}):
                result = mod.run_dry_run(_config(tmp), out_dir=Path(tmp),
                                         sampler=mod.NullResourceSampler())
            for key in ("report_path", "ledger_path"):
                text = Path(result[key]).read_text(encoding="utf-8")
                self.assertNotIn(self.SECRET, text)
            self.assertNotIn(self.SECRET, json.dumps(result))


class InvariantTests(unittest.TestCase):
    def test_build_request_enforces_max_tokens(self):
        scenarios = mod.load_scenarios(SCENARIOS_PATH)
        for entry, field in (("chat", "max_tokens"), ("messages", "max_tokens"),
                             ("responses", "max_output_tokens")):
            scenario = next(s for s in scenarios if s["entry"] == entry and s["kind"] == "text"
                            and s["expectation"] == "must-succeed")
            request = mod.build_request(scenario, FIXTURES_DIR, model="deepseek-flash")
            self.assertEqual(request[field], 1024, entry)
            self.assertEqual(request["model"], "deepseek-flash")

    def test_sdk_config_invariants(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg = mod.build_sdk_config(_config(tmp))
            self.assertEqual(cfg["max_retries"], 0)
            self.assertEqual(cfg["fallbacks"], [])
            self.assertEqual(cfg["max_tokens"], 1024)
            self.assertEqual(cfg["concurrency"], 1)

    def test_config_validate_rejects_bad_invariants(self):
        with tempfile.TemporaryDirectory() as tmp:
            for bad in ({"max_tokens": 2048}, {"concurrency": 2},
                        {"max_retries": 1}, {"fallbacks": ("backup",)}):
                with self.assertRaises(mod.ConfigError):
                    _config(tmp, **bad).validate()

    def test_request_sha256_stable(self):
        a = mod.request_sha256({"b": 1, "a": 2})
        b = mod.request_sha256({"a": 2, "b": 1})
        self.assertEqual(a, b)
        self.assertEqual(len(a), 64)


class ResourceSamplerTests(unittest.TestCase):
    def test_fake_sampler_injectable(self):
        sampler = mod.FakeResourceSampler([{"rss_bytes": 100, "cpu_percent": 1.5}])
        sample = sampler.sample()
        self.assertEqual(sample["rss_bytes"], 100)
        self.assertEqual(sample["cpu_percent"], 1.5)

    def test_null_sampler_observes_nothing(self):
        sample = mod.NullResourceSampler().sample()
        self.assertFalse(sample["observed"])
        self.assertIsNone(sample["rss_bytes"])

    def test_dry_run_records_samples_without_slo(self):
        with tempfile.TemporaryDirectory() as tmp:
            sampler = mod.FakeResourceSampler([{"rss_bytes": 10, "cpu_percent": 0.1}])
            result = mod.run_dry_run(_config(tmp), out_dir=Path(tmp), sampler=sampler)
            self.assertIn("resource_samples", result)
            self.assertNotIn("slo", json.dumps(result).lower())


class DryRunTests(unittest.TestCase):
    def test_dry_run_end_to_end_no_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            live_calls = []

            def _boom(*a, **k):
                live_calls.append((a, k))
                raise AssertionError("live_send must never be called in dry-run")

            with mock.patch.object(mod, "live_send", _boom), \
                    mock.patch.object(socket, "socket", side_effect=AssertionError("no socket")), \
                    mock.patch.object(socket, "create_connection", side_effect=AssertionError("no conn")):
                result = mod.run_dry_run(_config(tmp), out_dir=Path(tmp),
                                         sampler=mod.NullResourceSampler())
            self.assertEqual(live_calls, [])
            self.assertTrue(Path(result["report_path"]).exists())
            self.assertTrue(Path(result["ledger_path"]).exists())
            self.assertEqual(result["scenario_count"], 27)
            self.assertEqual(result["mode"], "dry-run")
            self.assertGreater(result["used"], 0)

    def test_dry_run_exercises_crash_resume_ledger(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = mod.run_dry_run(_config(tmp), out_dir=Path(tmp),
                                     sampler=mod.NullResourceSampler())
            # the dry-run proves crash->used semantics on a scratch ledger
            self.assertTrue(result["ledger_crash_resume_ok"])

    def test_dry_run_report_has_required_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = mod.run_dry_run(_config(tmp), out_dir=Path(tmp),
                                     sampler=mod.NullResourceSampler())
            report = json.loads(Path(result["report_path"]).read_text(encoding="utf-8"))
            for key in ("run_id", "scenario_count", "used", "cap", "window_seconds",
                        "max_tokens", "concurrency", "max_retries", "fallbacks",
                        "api_key_env", "provider", "scenarios"):
                self.assertIn(key, report)
            self.assertEqual(report["api_key_env"], mod.API_KEY_ENV)
            self.assertEqual(report["fallbacks"], [])
            self.assertEqual(report["max_retries"], 0)

    def test_cli_dry_run_default_no_live(self):
        with tempfile.TemporaryDirectory() as tmp:
            argv = ["--dry-run", "--scenarios", str(SCENARIOS_PATH),
                    "--fixtures", str(FIXTURES_DIR), "--out-dir", tmp]
            with mock.patch.object(mod, "live_send", side_effect=AssertionError("no live")), \
                    mock.patch.object(socket, "socket", side_effect=AssertionError("no socket")):
                code = mod.main(argv)
            self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()