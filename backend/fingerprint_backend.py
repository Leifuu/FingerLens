"""FingerLens 本地 ADB 网关，无第三方依赖；指纹仅由普通 APK 采集。"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PACKAGE = "com.life.fingerprint"
EXPORT_DIR = f"/sdcard/Android/data/{PACKAGE}/files/exports"
MAX_JSON = 64 * 1024 * 1024
MAX_GZIP = 16 * 1024 * 1024
SERIAL_RE = re.compile(r"[A-Za-z0-9_.:-]{1,128}")
ROOT = Path(__file__).resolve().parents[1]
TERMINAL = {"ready", "cancelled", "error"}


class ApiError(Exception):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def envelope(data=None, message="", code=0):
    return {"data": data, "message": message, "code": code}


def resolve_adb(config: Path, override: str | None = None) -> str:
    # 业务块：启动时读取有界配置；命令行优先，其次配置，再沿用环境变量与PATH。
    with config.open("rb") as source:
        raw = source.read(64 * 1024 + 1)
    if len(raw) > 64 * 1024:
        raise ValueError("配置文件不能超过64 KiB")
    settings = json.loads(raw.decode("utf-8"))
    if not isinstance(settings, dict):
        raise ValueError("配置文件必须是JSON对象")
    configured = settings.get("adb_path")
    if configured is not None and not isinstance(configured, str):
        raise ValueError("adb_path 必须为字符串或null")
    for name, candidate in (("--adb", override), ("adb_path", configured), ("FINGERLENS_ADB", os.environ.get("FINGERLENS_ADB"))):
        if candidate is None or not candidate.strip():
            continue
        candidate = candidate.strip()
        if len(candidate) > 32767 or any(char in candidate for char in "\0\r\n"):
            raise ValueError(f"{name} 路径无效")
        if name == "adb_path" and ("/" in candidate or "\\" in candidate):
            return str((config.parent / candidate).resolve())
        return candidate
    return "adb"


def exact_int(value: object, name: str) -> int:
    """兼容契约中的 int64 包装，同时拒绝普通浮点和超范围输入。"""
    if isinstance(value, dict) and value.get("$type") == "int64":
        value = value.get("value")
    if not isinstance(value, (int, str)) or isinstance(value, bool):
        raise ApiError(f"{name} 必须是 int64")
    try:
        number = int(value)
    except (TypeError, ValueError) as exc:
        raise ApiError(f"{name} 不是有效整数") from exc
    if number <= 0 or number > MAX_JSON:
        raise ApiError(f"{name} 超出范围", 413)
    return number


class Bridge:
    def __init__(self, adb: str, output: Path, timeout=240):
        self.adb = adb
        self.output = output
        self.timeout = timeout
        self.lock = threading.RLock()
        self.jobs: dict[str, dict] = {}
        self.active: dict[str, str] = {}
        self.stopping = threading.Event()

    def command(self, args: list[str], timeout=15, limit=64 * 1024) -> str:
        # argv 不经过本机 shell；远端参数只有常量、严格校验的 serial 和 UUID。
        try:
            proc = subprocess.run([self.adb, *args], capture_output=True, timeout=timeout,
                                  creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        except subprocess.TimeoutExpired as exc:
            raise ApiError("ADB 命令超时，请确认设备连接", 504) from exc
        except OSError as exc:
            raise ApiError("无法启动 ADB，请检查配置文件的 adb_path、--adb 或环境变量 PATH", 503) from exc
        if len(proc.stdout) > limit or len(proc.stderr) > limit:
            raise ApiError("ADB 返回内容超限", 502)
        out = proc.stdout.decode("utf-8", "replace")
        if proc.returncode:
            raise ApiError(proc.stderr.decode("utf-8", "replace").strip() or out.strip() or "ADB 失败", 502)
        return out

    def device_command(self, serial: str, *args: str, **options):
        if not isinstance(serial, str) or not SERIAL_RE.fullmatch(serial):
            raise ApiError("无效的 ADB 设备序列号")
        return self.command(["-s", serial, *args], **options)

    def devices(self):
        output = self.command(["devices", "-l"], timeout=10)
        result = []
        for line in output.splitlines():
            parts = line.split()
            if len(parts) < 2 or not SERIAL_RE.fullmatch(parts[0]) or parts[0] == "List":
                continue
            fields = dict(part.split(":", 1) for part in parts[2:] if ":" in part)
            with self.lock:
                busy = parts[0] in self.active
            result.append({"serial": parts[0], "status": parts[1], "model": fields.get("model", ""), "busy": busy})
        return result

    def start(self, serial: str):
        if not isinstance(serial, str) or not SERIAL_RE.fullmatch(serial):
            raise ApiError("必须选择有效设备")
        if not any(d["serial"] == serial and d["status"] == "device" for d in self.devices()):
            raise ApiError("所选设备已离线或未授权，请刷新设备列表", 409)
        with self.lock:
            if self.stopping.is_set():
                raise ApiError("后端正在关闭", 503)
            if serial in self.active:
                raise ApiError("该设备正在采集，请等待或取消当前任务", 409)
            if len(self.active) >= 4:
                raise ApiError("最多同时采集 4 台设备", 429)
            # 限制内存历史；结果仍保留在工程 .codex/tmp 中。
            for old in list(self.jobs):
                if len(self.jobs) < 64:
                    break
                if self.jobs[old]["state"] in TERMINAL:
                    del self.jobs[old]
            job_id = str(uuid.uuid4())
            job = {"id": job_id, "serial": serial, "state": "starting", "progress": 0,
                   "message": "正在唤起采集 APK", "createdAt": time.time(), "cancel": threading.Event()}
            self.jobs[job_id] = job
            self.active[serial] = job_id
        threading.Thread(target=self._collect, args=(job,), daemon=True).start()
        return self.snapshot(job_id)

    def snapshot(self, job_id):
        with self.lock:
            job = self.jobs.get(job_id)
            if job is None:
                raise ApiError("任务不存在或后端已重启", 404)
            return {key: value for key, value in job.items() if key not in {"cancel", "path"}}

    def update(self, job, **fields):
        with self.lock:
            job.update(fields)

    def cancel(self, job_id):
        with self.lock:
            self.snapshot(job_id)
            job = self.jobs[job_id]
            if job["state"] not in TERMINAL:
                job["cancel"].set()
                job["message"] = "正在请求 APK 取消，保留已完成结果"
        return self.snapshot(job_id)

    def broadcast(self, job, action):
        return self.device_command(job["serial"], "shell", "am", "broadcast", "-n",
                                   f"{PACKAGE}/.CollectionReceiver", "-a", f"{PACKAGE}.action.{action}",
                                   "--es", "requestId", job["id"])

    def _collect(self, job):
        sent = False
        try:
            # Activity 保持可见，控制进程不依赖后台广播短暂存活；不补授设备权限。
            output = self.device_command(job["serial"], "shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
            if "Error:" in output or "Error type" in output:
                raise ApiError("请先在所选设备安装新版采集 APK（1.1.0）", 409)
            output = self.broadcast(job, "COLLECT")
            if not re.search(r"Broadcast completed: result=-1\b", output):
                raise ApiError("APK 未接受采集：请确认已安装 1.1.0 且没有其它采集任务", 409)
            sent = True
            self.update(job, state="collecting", message="等待 APK 发布本轮进度")
            deadline = time.monotonic() + self.timeout
            cancel_deadline = None
            while time.monotonic() < deadline:
                if self.stopping.is_set():
                    job["cancel"].set()
                if job["cancel"].is_set() and cancel_deadline is None:
                    self.broadcast(job, "CANCEL")
                    cancel_deadline = time.monotonic() + 15
                path = f"{EXPORT_DIR}/bridge-{job['id']}.json"
                # 只读这个请求 UUID 的小型进度文件，不取 latest、不取历史报告。
                try:
                    raw = self.device_command(job["serial"], "shell", "cat", path, limit=16 * 1024)
                except ApiError as exc:
                    if "No such file" not in str(exc):
                        raise
                    raw = ""
                if raw:
                    status = json.loads(raw)["data"]
                    if status.get("requestId") != job["id"] or status.get("sessionId") != job["id"]:
                        raise ApiError("APK 进度文件会话不匹配", 502)
                    state = status.get("state")
                    if state not in {"running", "complete", "partial", "cancelled", "interrupted"}:
                        raise ApiError("APK 返回未知采集状态", 502)
                    progress = status.get("progress")
                    if type(progress) is not int or not 0 <= progress <= 100:
                        raise ApiError("APK 返回非法进度", 502)
                    self.update(job, progress=progress, message=str(status.get("text", ""))[:4096], uid=status.get("uid"))
                    if state != "running":
                        self._download(job, status)
                        self.update(job, state="cancelled" if state == "cancelled" else "ready", collectionState=state)
                        return
                if cancel_deadline is not None and time.monotonic() >= cancel_deadline:
                    raise ApiError("APK 未确认取消；已有设备文件保留，请在 APK 中检查采集状态", 504)
                time.sleep(1)
            raise ApiError("等待采集超时（240 秒）；已请求取消，设备已有文件保留", 504)
        except Exception as exc:
            if sent:
                try:
                    self.broadcast(job, "CANCEL")
                except Exception:
                    pass
            self.update(job, state="error", message=str(exc)[:4096])
        finally:
            with self.lock:
                self.active.pop(job["serial"], None)

    def _download(self, job, status):
        name = status.get("fileName")
        if name not in {f"fingerprint-{job['id']}.json", f"fingerprint-{job['id']}.json.gz"}:
            raise ApiError("APK 返回非法文件名", 502)
        size = exact_int(status.get("bytes"), "压缩文件大小")
        json_size = exact_int(status.get("jsonBytes"), "JSON 文件大小")
        max_size = MAX_GZIP if name.endswith(".gz") else MAX_JSON
        if size > max_size:
            raise ApiError("APK 导出文件大小超限", 413)
        sha = status.get("sha256")
        if not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{64}", sha):
            raise ApiError("APK 未提供有效文件摘要", 502)
        self.update(job, state="transferring", message="采集结束，正在传输并校验原始文件")
        directory = self.output / job["id"]
        directory.mkdir(parents=True, exist_ok=False)
        partial = directory / (name + ".part")
        self.transfer(job["serial"], f"{EXPORT_DIR}/{name}", partial, size)
        if partial.stat().st_size != size:
            raise ApiError("ADB 传输文件大小不一致", 502)
        digest, total = hashlib.sha256(), 0
        opener = gzip.open if name.endswith(".gz") else open
        with opener(partial, "rb") as source:
            while chunk := source.read(65536):
                total += len(chunk)
                if total > MAX_JSON:
                    raise ApiError("解压后指纹超过 64 MiB", 413)
                digest.update(chunk)
        if total != json_size or digest.hexdigest() != sha:
            raise ApiError("指纹原始字节摘要校验失败", 502)
        target = directory / name
        partial.replace(target)
        self.update(job, path=target, fileName=name, bytes=size, jsonBytes=total, sha256=sha,
                    message="原始文件校验完成，正在导入浏览器")

    def transfer(self, serial, remote, target, limit):
        # 读取过程中限额，不能只在 adb pull 结束后检查任意大小的设备文件。
        process = subprocess.Popen([self.adb, "-s", serial, "exec-out", "cat", remote],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        timer = threading.Timer(40, lambda: process.kill() if process.poll() is None else None)
        timer.daemon = True
        timer.start()
        try:
            total = 0
            with target.open("xb") as destination:
                while chunk := process.stdout.read(65536):
                    total += len(chunk)
                    if total > limit:
                        raise ApiError("设备传输超过声明大小，已终止", 413)
                    destination.write(chunk)
            if process.wait() != 0:
                raise ApiError("ADB 文件传输失败或超时", 502)
        finally:
            timer.cancel()
            if process.poll() is None:
                process.kill()
            process.wait()
            process.stdout.close()
            process.stderr.close()

    def result(self, job_id):
        with self.lock:
            self.snapshot(job_id)
            job = self.jobs[job_id]
            if job["state"] not in {"ready", "cancelled"} or "path" not in job:
                raise ApiError("采集结果尚未就绪", 409)
            return job["path"]


class Handler(BaseHTTPRequestHandler):
    server_version = "FingerLens/1.0"

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def reply(self, data=None, message="", status=200):
        body = json.dumps(envelope(data, message, 0 if status < 400 else status), ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def guard(self, mutate=False):
        # 无跨域授权；Host 防 DNS rebinding，Origin + 自定义头防网页跨站调用 ADB。
        if self.headers.get("Host") not in {f"127.0.0.1:{self.server.server_port}", "127.0.0.1:5173"}:
            raise ApiError("拒绝非本地 Host", 403)
        origin = self.headers.get("Origin")
        if origin and origin not in {"http://127.0.0.1:5173", f"http://127.0.0.1:{self.server.server_port}"}:
            raise ApiError("拒绝跨站来源", 403)
        if mutate and (self.headers.get("X-FingerLens") != "1" or self.headers.get("Content-Type") != "application/json"):
            raise ApiError("必须使用本地 JSON 控制请求", 403)

    def do_GET(self):
        try:
            self.guard()
            path = urlsplit(self.path).path
            if path == "/api/health":
                self.reply({"service": "FingerLens", "version": "1.0"}); return
            if path == "/api/devices":
                self.reply(self.server.bridge.devices()); return
            match = re.fullmatch(r"/api/jobs/([a-f0-9-]{36})(/file)?", path)
            if not match:
                raise ApiError("接口不存在", 404)
            if not match[2]:
                self.reply(self.server.bridge.snapshot(match[1])); return
            file = self.server.bridge.result(match[1])
            self.send_response(200)
            self.send_header("Content-Type", "application/gzip" if file.suffix == ".gz" else "application/json")
            self.send_header("Content-Disposition", f'attachment; filename="{file.name}"')
            self.send_header("Content-Length", str(file.stat().st_size))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            with file.open("rb") as source:
                while chunk := source.read(65536):
                    self.wfile.write(chunk)
        except ApiError as exc:
            self.reply(message=str(exc), status=exc.status)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.reply(message="后端读取失败，请检查本地服务与 ADB", status=500)

    def do_POST(self):
        try:
            self.guard(mutate=True)
            if self.headers.get("Transfer-Encoding"):
                raise ApiError("不接受分块请求", 400)
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                raise ApiError("请求长度无效")
            if not 1 <= length <= 1024:
                raise ApiError("请求体必须为 1..1024 字节", 413)
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(payload, dict):
                raise ApiError("请求必须为 JSON 对象")
            path = urlsplit(self.path).path
            if path == "/api/jobs":
                self.reply(self.server.bridge.start(payload.get("serial")), status=202); return
            match = re.fullmatch(r"/api/jobs/([a-f0-9-]{36})/cancel", path)
            if match:
                self.reply(self.server.bridge.cancel(match[1])); return
            raise ApiError("接口不存在", 404)
        except ApiError as exc:
            self.reply(message=str(exc), status=exc.status)
        except (ValueError, UnicodeError):
            self.reply(message="非法 JSON 请求", status=400)
        except Exception:
            self.reply(message="后端执行失败，请检查本地服务与 ADB", status=500)

    def log_message(self, *_):
        pass


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port, bridge):
        self.bridge = bridge
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(("127.0.0.1", port), Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request); return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release(); raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--config", type=Path, default=ROOT / "backend/config.json", help="配置文件路径，默认backend/config.json")
    parser.add_argument("--adb", help="覆盖配置文件中的ADB路径")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("port 必须为 1024..65535")
    try:
        adb = resolve_adb(args.config, args.adb)
    except (OSError, ValueError) as exc:
        parser.error(f"读取ADB配置失败（{args.config}）：{exc}")
    bridge = Bridge(adb, ROOT / ".codex/tmp/backend/jobs")
    with Server(args.port, bridge) as server:
        print(f"FingerLens backend http://127.0.0.1:{args.port}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            bridge.stopping.set()
            time.sleep(2)


if __name__ == "__main__":
    main()
