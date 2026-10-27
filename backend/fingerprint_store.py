"""FingerLens 指纹持久化：保留原文，同时建立可检索的报告/采集项索引。"""
from __future__ import annotations

import gzip
import json
import re
import threading
from pathlib import Path
from typing import Any

MAX_JSON = 64 * 1024 * 1024
NAME_RE = re.compile(r"^[A-Za-z0-9_]{1,64}$")


def _text(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (str, int, float)):
        return str(value)
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def _search_tokens(value: Any, path: str, output: list[str], state: dict[str, int], budget: int = 4 * 1024 * 1024) -> None:
    if state["size"] >= budget:
        return
    output.append(path)
    state["size"] += len(path) + 1
    if isinstance(value, dict):
        for key, child in value.items():
            _search_tokens(child, f"{path}.{key}", output, state, budget)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _search_tokens(child, f"{path}[{index}]", output, state, budget)
    else:
        text = _text(value)
        output.append(text)
        state["size"] += len(text) + 1


class FingerprintStore:
    """可选 MySQL 存储；未启用时零连接、零副作用。"""

    def __init__(self, settings: object, max_json: int = MAX_JSON):
        self.max_json = max_json
        self.lock = threading.RLock()
        self.enabled = False
        self.schema_ready = False
        self.settings: dict[str, Any] = {}
        if settings is None:
            return
        if not isinstance(settings, dict):
            raise ValueError("database 必须为对象或null")
        if settings.get("enabled", False) is not True:
            return
        required = ("host", "port", "user", "password", "name")
        if any(key not in settings for key in required):
            raise ValueError("启用数据库时必须配置 host、port、user、password、name")
        if not all(isinstance(settings[key], str) for key in ("host", "user", "password", "name")):
            raise ValueError("数据库 host、user、password、name 必须为字符串")
        port = settings["port"]
        timeout = settings.get("connect_timeout", 5)
        if type(port) is not int or not 1 <= port <= 65535 or type(timeout) is not int or not 1 <= timeout <= 60:
            raise ValueError("数据库 port 必须为1..65535，connect_timeout必须为1..60")
        if not NAME_RE.fullmatch(settings["name"]):
            raise ValueError("数据库 name 只允许字母、数字和下划线")
        if any("\x00" in settings[key] for key in ("host", "user", "password", "name")):
            raise ValueError("数据库配置不能包含NUL")
        self.settings = {**settings, "port": port, "connect_timeout": timeout}
        self.enabled = True

    def _connect(self):
        if not self.enabled:
            return None
        try:
            import pymysql
        except ImportError as exc:
            raise RuntimeError("已启用MySQL，但未安装PyMySQL：pip install -r backend/requirements.txt") from exc
        return pymysql.connect(
            host=self.settings["host"], port=self.settings["port"], user=self.settings["user"],
            password=self.settings["password"], database=self.settings["name"],
            connect_timeout=self.settings["connect_timeout"], charset="utf8mb4", autocommit=False,
        )

    def _ensure_schema(self, connection) -> None:
        # 业务块：只创建结构，不使用删除类SQL；原文与规范化索引各自保留。
        if self.schema_ready:
            return
        statements = (
            """CREATE TABLE IF NOT EXISTS fingerprint_reports (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                session_id CHAR(36) NOT NULL,
                device_serial VARCHAR(128) NULL,
                schema_version VARCHAR(32) NOT NULL,
                collector_version VARCHAR(128) NOT NULL,
                state VARCHAR(16) NOT NULL,
                started_at VARCHAR(64) NULL,
                finished_at VARCHAR(64) NULL,
                sha256 CHAR(64) NOT NULL,
                raw_bytes BIGINT UNSIGNED NOT NULL,
                raw_document LONGTEXT NOT NULL,
                raw_json JSON NOT NULL,
                imported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                PRIMARY KEY (id), UNIQUE KEY uq_fingerprint_reports_sha256 (sha256),
                KEY ix_fingerprint_reports_session (session_id), KEY ix_fingerprint_reports_serial (device_serial)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
            """CREATE TABLE IF NOT EXISTS fingerprint_items (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                report_id BIGINT UNSIGNED NOT NULL,
                item_index INT UNSIGNED NOT NULL,
                item_key VARCHAR(512) NOT NULL,
                category VARCHAR(64) NOT NULL,
                item_name VARCHAR(512) NOT NULL,
                status VARCHAR(32) NOT NULL,
                data_type VARCHAR(32) NOT NULL,
                source_api VARCHAR(512) NULL,
                value_json JSON NULL,
                content_text MEDIUMTEXT NOT NULL,
                PRIMARY KEY (id), UNIQUE KEY uq_fingerprint_items_order (report_id, item_index),
                KEY ix_fingerprint_items_key (item_key(191)), KEY ix_fingerprint_items_category (category),
                FULLTEXT KEY ft_fingerprint_items_content (content_text),
                CONSTRAINT fk_fingerprint_items_report FOREIGN KEY (report_id) REFERENCES fingerprint_reports(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
        )
        with connection.cursor() as cursor:
            for statement in statements:
                cursor.execute(statement)
        self.schema_ready = True

    def ingest(self, path: Path, sha256: str, serial: str | None = None) -> int | None:
        if not self.enabled:
            return None
        opener = gzip.open if path.suffix == ".gz" else open
        with opener(path, "rb") as source:
            raw = source.read(self.max_json + 1)
        if len(raw) > self.max_json:
            raise ValueError("入库原始JSON超过64 MiB")
        document = json.loads(raw.decode("utf-8"))
        data = document.get("data") if isinstance(document, dict) else None
        if not isinstance(data, dict) or not isinstance(data.get("items"), list):
            raise ValueError("入库文件不是有效FingerLens JSON")
        raw_text = raw.decode("utf-8")
        connection = self._connect()
        if connection is None:
            return None
        try:
            with self.lock:
                self._ensure_schema(connection)
                with connection.cursor() as cursor:
                    cursor.execute(
                        """INSERT INTO fingerprint_reports
                        (session_id, device_serial, schema_version, collector_version, state, started_at, finished_at, sha256, raw_bytes, raw_document, raw_json)
                        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)""",
                        (str(data.get("sessionId", "")), serial, str(data.get("schemaVersion", "")),
                         str(data.get("collectorVersion", "")), str(data.get("state", "")),
                         data.get("startedAt"), data.get("finishedAt"), sha256, len(raw), raw_text,
                         json.dumps(document, ensure_ascii=False, separators=(",", ":"), allow_nan=False)),
                    )
                    report_id = int(cursor.lastrowid)
                    if cursor.rowcount == 1:
                        for index, item in enumerate(data["items"]):
                            if not isinstance(item, dict):
                                continue
                            source = item.get("source") if isinstance(item.get("source"), dict) else {}
                            tokens: list[str] = []
                            _search_tokens(item.get("value"), str(item.get("key", "")), tokens, {"size": 0})
                            tokens.extend((str(item.get("key", "")), str(item.get("name", "")), str(source.get("api", ""))))
                            cursor.execute(
                                """INSERT INTO fingerprint_items
                                (report_id,item_index,item_key,category,item_name,status,data_type,source_api,value_json,content_text)
                                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                                (report_id, index, str(item.get("key", ""))[:512], str(item.get("category", ""))[:64],
                                 str(item.get("name", ""))[:512], str(item.get("status", ""))[:32], str(item.get("dataType", ""))[:32],
                                 str(source.get("api", ""))[:512],
                                 json.dumps(item.get("value"), ensure_ascii=False, separators=(",", ":"), allow_nan=False),
                                 "\n".join(tokens)[:4 * 1024 * 1024]),
                            )
                connection.commit()
                return report_id
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def search(self, query: str, limit: int = 100) -> list[dict[str, Any]]:
        if not isinstance(query, str) or not 1 <= len(query.strip()) <= 256:
            raise ValueError("搜索关键词长度必须为1..256")
        if type(limit) is not int or not 1 <= limit <= 200:
            raise ValueError("搜索结果上限必须为1..200")
        if not self.enabled:
            return []
        connection = self._connect()
        if connection is None:
            return []
        try:
            with self.lock:
                self._ensure_schema(connection)
                with connection.cursor() as cursor:
                    needle = query.strip()
                    cursor.execute(
                        """SELECT report_id AS reportId, item_index AS itemIndex, item_key AS itemKey,
                        item_name AS itemName, category, source_api AS sourceApi
                        FROM fingerprint_items
                        WHERE LOWER(content_text) LIKE LOWER(%s) OR MATCH(content_text) AGAINST (%s IN NATURAL LANGUAGE MODE)
                        ORDER BY report_id DESC, item_index ASC LIMIT %s""",
                        (f"%{needle}%", needle, limit),
                    )
                    return list(cursor.fetchall())
        finally:
            connection.close()
