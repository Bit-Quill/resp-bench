"""Loads driver and workload configuration from JSON files.

Deserialization mirrors the Ruby ConfigLoader exactly (field names, defaults)
so the shared configs/ JSON files are consumed identically across engines.
"""

from __future__ import annotations

import json
from typing import Any, Dict

from .command_config import CommandConfig
from .completion_config import CompletionConfig
from .driver_config import DriverConfig
from .keyspace_config import KeyspaceConfig
from .phase_config import PhaseConfig
from .workload_config import WorkloadConfig


class ConfigLoader:
    @staticmethod
    def load_driver_config(path: str) -> DriverConfig:
        with open(path, encoding="utf-8") as f:
            return ConfigLoader.parse_driver_config(json.load(f))

    @staticmethod
    def load_workload_config(path: str) -> WorkloadConfig:
        with open(path, encoding="utf-8") as f:
            return ConfigLoader.parse_workload_config(json.load(f))

    @staticmethod
    def parse_driver_config(data: Dict[str, Any]) -> DriverConfig:
        return DriverConfig(
            schema_version=data.get("schema_version", "1.0"),
            description=data.get("description"),
            driver_id=data.get("driver_id"),
            mode=data.get("mode", "standalone"),
            command_timeout_ms=data.get("command_timeout_ms"),
            tls=data.get("tls"),
            auth=data.get("auth"),
            specific_driver_config=data.get("specific_driver_config") or {},
        )

    @staticmethod
    def parse_workload_config(data: Dict[str, Any]) -> WorkloadConfig:
        phases = [ConfigLoader._parse_phase(p) for p in data.get("phases", [])]
        return WorkloadConfig(
            schema_version=data.get("schema_version", "1.0"),
            benchmark_profile=data.get("benchmark_profile") or {},
            phases=phases,
        )

    @staticmethod
    def _parse_phase(data: Dict[str, Any]) -> PhaseConfig:
        return PhaseConfig(
            id=data.get("id"),
            description=data.get("description"),
            connections=data.get("connections"),
            cps_limit=data.get("cps_limit", -1),
            rps_limit=data.get("rps_limit", -1),
            pipeline_depth=data.get("pipeline_depth", 1),
            warmup_requests=data.get("warmup_requests", 1),
            completion=ConfigLoader._parse_completion(data.get("completion", {})),
            keyspace=ConfigLoader._parse_keyspace(data.get("keyspace", {})),
            commands=[ConfigLoader._parse_command(c) for c in data.get("commands", [])],
        )

    @staticmethod
    def _parse_completion(data: Dict[str, Any]) -> CompletionConfig:
        return CompletionConfig(
            type=data.get("type"),
            seconds=data.get("seconds"),
            requests=data.get("requests"),
        )

    @staticmethod
    def _parse_keyspace(data: Dict[str, Any]) -> KeyspaceConfig:
        return KeyspaceConfig(
            keys_count=data.get("keys_count"),
            key_size_bytes=data.get("key_size_bytes"),
            key_prefix=data.get("key_prefix"),
            generation_alg=data.get("generation_alg", "sequential_int"),
            seed=data.get("seed"),
        )

    @staticmethod
    def _parse_command(data: Dict[str, Any]) -> CommandConfig:
        return CommandConfig(
            command=data.get("command"),
            weight=data.get("weight"),
            data_size_bytes=data.get("data_size_bytes"),
        )
