"""有界合成 ADB/HTTP 回归；不代表任何真实设备采集。"""
import gzip
import hashlib
import http.client
import json
import os
from datetime import datetime, timezone, timedelta
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from fingerprint_backend import ApiError, Bridge, EXPORT_DIR, MAX_JSON, Server, make_download_name, resolve_adb
from fingerprint_store import FingerprintStore

TMP = Path(__file__).resolve().parents[1] / ".codex/tmp/backend-tests"
TMP.mkdir(parents=True, exist_ok=True)


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=TMP)
        self.config = Path(self.temp.name) / "config.json"

    def tearDown(self):
        self.temp.cleanup()

    def write(self, value):
        self.config.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def test_explicit_config_and_cli_override_environment(self):
        self.write({"adb_path": "./Android 工具/adb.exe"})
        with patch.dict(os.environ, {"FINGERLENS_ADB": "environment-adb"}):
            expected = str((self.config.parent / "Android 工具/adb.exe").resolve())
            self.assertEqual(resolve_adb(self.config), expected)
            self.assertEqual(resolve_adb(self.config, "cli-adb"), "cli-adb")

    def test_null_empty_whitespace_and_missing_key_use_environment_or_path(self):
        for settings in [{}, {"adb_path": None}, {"adb_path": ""}, {"adb_path": "  \t "}]:
            self.write(settings)
            with patch.dict(os.environ, {"FINGERLENS_ADB": "environment-adb"}):
                self.assertEqual(resolve_adb(self.config), "environment-adb")
            with patch.dict(os.environ, {}, clear=True):
                self.assertEqual(resolve_adb(self.config), "adb")
            with patch.dict(os.environ, {"FINGERLENS_ADB": "  "}):
                self.assertEqual(resolve_adb(self.config), "adb")

    def test_invalid_type_path_json_and_oversized_file_fail_clearly(self):
        for value in [[], None, {"adb_path": False}, {"adb_path": 123}, {"adb_path": []}, {"adb_path": "adb\0.exe"}, {"adb_path": "a" * 32768}]:
            self.write(value)
            with self.assertRaises(ValueError):
                resolve_adb(self.config)
        for data in [b"{", b"\xff", b" " * (64 * 1024 + 1)]:
            self.config.write_bytes(data)
            with self.assertRaises(ValueError):
                resolve_adb(self.config)

    def test_path_with_spaces_is_one_subprocess_argument(self):
        self.write({"adb_path": "./Android 工具/adb.exe"})
        adb = resolve_adb(self.config)
        with patch("fingerprint_backend.subprocess.run", return_value=subprocess.CompletedProcess([], 0, b"ok", b"")) as run:
            self.assertEqual(Bridge(adb, self.config.parent).command(["version"]), "ok")
            self.assertEqual(run.call_args.args[0], [adb, "version"])
            self.assertNotIn("shell", run.call_args.kwargs)

    def test_database_is_disabled_without_connection_settings(self):
        self.assertFalse(FingerprintStore(None).enabled)
        self.assertFalse(FingerprintStore({"enabled": False}).enabled)
        with self.assertRaises(ValueError):
            FingerprintStore({"enabled": True, "host": "127.0.0.1"})


class FakeBridge(Bridge):
    def __init__(self, output, **kwargs):
        super().__init__("synthetic-adb", output, **kwargs)
        self.calls = []
        self.cancelled = set()
        self.mismatch = False
        self.block = False
        self.offline = False
        self.raw = '原始中文\n  空白\u0000'.encode()
        self.packed = gzip.compress(self.raw)

    def command(self, args, **options):
        self.calls.append(args)
        if args == ["devices", "-l"]:
            return "List of devices attached\nfixture-A\tdevice model:SYNTHETIC\nfixture-B\tdevice\nfixture-C\toffline\n"
        if self.offline:
            raise ApiError("device offline", 502)
        if "broadcast" in args:
            request_id = args[-1]
            if args[-4].endswith(".CANCEL"):
                self.cancelled.add(request_id)
            return "Broadcast completed: result=-1\n"
        if "cat" in args:
            request_id = args[-1].split("bridge-")[-1].removesuffix(".json")
            state = "cancelled" if request_id in self.cancelled else "running" if self.block else "partial"
            return json.dumps({"data": {"requestId": "wrong" if self.mismatch else request_id, "sessionId": request_id,
                "state": state, "progress": 50, "text": "合成进度", "uid": 12345,
                "fileName": f"fingerprint-{request_id}.json.gz", "bytes": len(self.packed),
                "jsonBytes": len(self.raw), "sha256": hashlib.sha256(self.raw).hexdigest()}})
        return "Status: ok"

    def transfer(self, serial, remote, target, limit):
        self.calls.append([serial, "synthetic-transfer", remote])
        target.write_bytes(self.packed)


def wait_done(bridge, job_id):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if bridge.snapshot(job_id)["state"] in {"ready", "cancelled", "error"}:
            return bridge.snapshot(job_id)
        time.sleep(0.01)
    raise AssertionError("合成任务没有收尾")


class SlowStore:
    enabled = True

    def __init__(self):
        self.started = threading.Event()
        self.release = threading.Event()

    def ingest(self, path, sha256, serial=None):
        self.started.set()
        if not self.release.wait(2):
            raise AssertionError("slow store was not released")
        return 42


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=TMP)
        self.bridge = FakeBridge(Path(self.temp.name))

    def tearDown(self):
        for job in list(self.bridge.jobs):
            self.bridge.cancel(job)
            wait_done(self.bridge, job)
        self.temp.cleanup()

    def test_parallel_devices_reject_duplicate_device(self):
        self.bridge.block = True
        first = self.bridge.start("fixture-A")
        second = self.bridge.start("fixture-B")
        self.assertNotEqual(first["id"], second["id"])
        with self.assertRaises(ApiError) as error:
            self.bridge.start("fixture-A")
        self.assertEqual(error.exception.status, 409)
        self.bridge.cancel(first["id"])
        self.bridge.cancel(second["id"])
        self.assertEqual(wait_done(self.bridge, first["id"])["collectionState"], "cancelled")
        self.assertEqual(wait_done(self.bridge, second["id"])["collectionState"], "cancelled")

    def test_database_ingest_does_not_block_ready(self):
        store = SlowStore()
        bridge = FakeBridge(Path(self.temp.name), store=store)
        job = bridge.start("fixture-A")
        result = wait_done(bridge, job["id"])
        self.assertEqual(result["state"], "ready")
        self.assertEqual(result["databaseState"], "pending")
        self.assertTrue(store.started.wait(1))
        store.release.set()
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if bridge.snapshot(job["id"]).get("databaseState") == "stored":
                break
            time.sleep(0.01)
        self.assertEqual(bridge.snapshot(job["id"])["databaseState"], "stored")

    def test_only_requested_uuid_and_one_transfer_original_bytes(self):
        job = self.bridge.start("fixture-A")
        result = wait_done(self.bridge, job["id"])
        self.assertEqual(result["state"], "ready")
        self.assertEqual(result["collectionState"], "partial")
        self.assertRegex(result["downloadName"], rf"^fixture-A_\d{{8}}T\d{{6}}[+-]\d{{4}}_{job['id'][:8]}\.json\.gz$")
        self.assertEqual(gzip.decompress(self.bridge.result(job["id"]).read_bytes()), self.bridge.raw)
        cats = [call for call in self.bridge.calls if "cat" in call]
        self.assertTrue(all(call[-1] == f"{EXPORT_DIR}/bridge-{job['id']}.json" for call in cats))
        self.assertEqual(len([call for call in self.bridge.calls if "synthetic-transfer" in call]), 1)

    def test_invalid_serial_and_offline_are_rejected(self):
        for serial in [None, [], "a;touch x", "--help", "x" * 129]:
            with self.assertRaises(ApiError):
                self.bridge.start(serial)
        with self.assertRaises(ApiError) as error:
            self.bridge.start("fixture-C")
        self.assertEqual(error.exception.status, 409)

    def test_wrong_uuid_cannot_import_other_device_result(self):
        self.bridge.mismatch = True
        job = self.bridge.start("fixture-A")
        self.assertIn("会话不匹配", wait_done(self.bridge, job["id"])["message"])
        with self.assertRaises(ApiError):
            self.bridge.result(job["id"])

    def test_disconnect_releases_device_and_preserves_error(self):
        self.bridge.block = True
        job = self.bridge.start("fixture-A")
        self.bridge.offline = True
        self.assertEqual(wait_done(self.bridge, job["id"])["state"], "error")
        self.assertNotIn("fixture-A", self.bridge.active)

    def test_timeout_requests_cancel(self):
        self.bridge.block = True
        self.bridge.timeout = 0.05
        job = self.bridge.start("fixture-A")
        result = wait_done(self.bridge, job["id"])
        self.assertEqual(result["state"], "error")
        self.assertIn(job["id"], self.bridge.cancelled)

    def test_invalid_filename_size_and_hash_rejected(self):
        job = {"id": "00000000-0000-0000-0000-000000000000", "serial": "fixture-A"}
        base = {"fileName": f"fingerprint-{job['id']}.json.gz", "bytes": len(self.bridge.packed),
                "jsonBytes": len(self.bridge.raw), "sha256": "0" * 64}
        for fields in [{"fileName": "../../secret"}, {"bytes": 0}, {"jsonBytes": MAX_JSON + 1}, {"sha256": "x"}]:
            with self.assertRaises(ApiError):
                self.bridge._download(job, {**base, **fields})
        with self.assertRaisesRegex(ApiError, "摘要校验失败"):
            self.bridge._download(job, base)

    def test_download_name_uses_local_time_and_sanitizes_serial(self):
        created_at = datetime(2026, 10, 10, 20, 0, tzinfo=timezone(timedelta(hours=8))).timestamp()
        self.assertEqual(
            make_download_name("19271FDF600C72", created_at, "21f7dcb6-415b-44d1-a44f-0e0a77ce2049", ".json.gz"),
            "19271FDF600C72_20261010T200000+0800_21f7dcb6.json.gz",
        )
        self.assertEqual(
            make_download_name("127.0.0.1:31823", created_at, "21f7dcb6-415b-44d1-a44f-0e0a77ce2049", ".json"),
            "127.0.0.1_31823_20261010T200000+0800_21f7dcb6.json",
        )


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=TMP)
        self.server = Server(0, FakeBridge(Path(self.temp.name)))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.temp.cleanup()

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        connection.request(method, path, body, headers or {})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_devices_envelope_and_host_origin_protection(self):
        status, result = self.request("GET", "/api/devices")
        self.assertEqual(status, 200)
        self.assertEqual(set(result), {"data", "message", "code"})
        for headers in [{"Host": "attacker.example"}, {"Origin": "https://attacker.example"}]:
            self.assertEqual(self.request("GET", "/api/devices", headers=headers)[0], 403)

    def test_disabled_database_search_is_safe_and_bounded(self):
        status, result = self.request("GET", "/api/fingerprints/search?q=samsung")
        self.assertEqual(status, 200)
        self.assertEqual(result["data"], [])
        self.assertEqual(self.request("GET", "/api/fingerprints/search?q=")[0], 400)

    def test_mutation_requires_json_custom_header_and_bounded_body(self):
        self.assertEqual(self.request("POST", "/api/jobs", '{}')[0], 403)
        headers = {"Content-Type": "application/json", "X-FingerLens": "1"}
        for body, expected in [("[1]", 400), ("{", 400), ('"' + "a" * 1024 + '"', 413), ('{"serial":"fixture-C"}', 409)]:
            self.assertEqual(self.request("POST", "/api/jobs", body, headers)[0], expected)

    def test_file_download_uses_user_download_name(self):
        job = self.server.bridge.start("fixture-A")
        result = wait_done(self.server.bridge, job["id"])
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        connection.request("GET", f"/api/jobs/{job['id']}/file")
        response = connection.getresponse()
        response.read()
        connection.close()
        self.assertEqual(response.status, 200)
        self.assertEqual(response.getheader("Content-Disposition"), f'attachment; filename="{result["downloadName"]}"')


if __name__ == "__main__":
    unittest.main()
