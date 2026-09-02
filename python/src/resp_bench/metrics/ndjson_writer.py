"""Writes benchmark metrics as NDJSON (one JSON object per phase).

The output schema matches every other engine exactly (see
docs/CONFIG_SPECIFICATION.md): ``metadata`` / ``phase`` / ``totals`` /
``metrics`` blocks, latency ``unit: "us"``, integer ``summary`` percentiles,
uppercased command keys, and an HDR block with the base64 compressed payload.
"""

from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from typing import Optional

from .collector import MetricsCollector
from .hdr_encoder import encode_base64


def _iso8601_utc(dt: Optional[datetime]) -> Optional[str]:
    if dt is None:
        return None
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


class NdjsonWriter:
    def __init__(self, path: str) -> None:
        self._output_path = path
        self._commit_id = None
        self._driver_id = None
        self._primary_driver_version = None
        self._secondary_driver_id = None
        self._secondary_driver_version = None

    def set_metadata(
        self,
        *,
        commit_id: Optional[str],
        driver_id: Optional[str],
        primary_driver_version: Optional[str],
        secondary_driver_id: Optional[str] = None,
        secondary_driver_version: Optional[str] = None,
    ) -> None:
        self._commit_id = commit_id
        self._driver_id = driver_id
        self._primary_driver_version = primary_driver_version
        self._secondary_driver_id = secondary_driver_id
        self._secondary_driver_version = secondary_driver_version

    def write_phase_results(
        self,
        *,
        phase_id: str,
        status: str,
        connections: int,
        collector: MetricsCollector,
    ) -> None:
        parent = os.path.dirname(self._output_path)
        if parent:
            os.makedirs(parent, exist_ok=True)

        payload = self._build_phase_json(phase_id, status, connections, collector)
        with open(self._output_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(payload) + "\n")

    def _build_phase_json(self, phase_id, status, connections, collector) -> dict:
        result: dict = {}

        if self._commit_id or self._driver_id:
            metadata: dict = {}
            if self._commit_id:
                metadata["commit_id"] = self._commit_id
            metadata["timestamp"] = _iso8601_utc(datetime.now(timezone.utc))
            if self._driver_id:
                metadata["driver_id"] = self._driver_id
            if self._primary_driver_version:
                metadata["primary_driver_version"] = self._primary_driver_version
            if self._secondary_driver_id:
                metadata["secondary_driver_id"] = self._secondary_driver_id
            if self._secondary_driver_version:
                metadata["secondary_driver_version"] = self._secondary_driver_version
            result["metadata"] = metadata

        result["phase"] = {
            "id": phase_id,
            "status": status,
            "start_timestamp": _iso8601_utc(collector.start_time),
            "finish_timestamp": _iso8601_utc(collector.end_time),
            "duration_ms": collector.duration_millis(),
            "connections": connections,
        }

        result["totals"] = {
            "requests": collector.total_requests,
            "errors": collector.total_errors,
        }

        result["metrics"] = self._build_command_metrics(collector)
        return result

    def _build_command_metrics(self, collector: MetricsCollector) -> dict:
        metrics: dict = {}
        for cmd_name, cmd_metrics in collector.command_metrics.items():
            latency: dict = {
                "unit": "us",
                "count": cmd_metrics.count(),
                "summary": {
                    "min": int(cmd_metrics.min()),
                    "p50": int(cmd_metrics.percentile(50)),
                    "p95": int(cmd_metrics.percentile(95)),
                    "p99": int(cmd_metrics.percentile(99)),
                    "p999": int(cmd_metrics.percentile(99.9)),
                    "max": int(cmd_metrics.max()),
                },
            }
            latency["hdr"] = {
                "format": "hdr",
                "sigfig": 3,
                "payload_b64": encode_base64(cmd_metrics.histogram),
            }
            metrics[cmd_name] = {
                "requests": cmd_metrics.requests,
                "errors": cmd_metrics.errors,
                "latency": latency,
            }
        return metrics
