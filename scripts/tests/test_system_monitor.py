"""Tests for system_monitor.py — /proc readers, SystemMonitor thread lifecycle, NDJSON output."""
import json
import os
import subprocess
import time
import pytest
from pathlib import Path
from system_monitor import (
    read_cpu_jiffies, get_cpu_count, read_process_rss,
    read_process_group_rss, read_system_memory, SystemMonitor,
)


class TestProcReaders:
    def test_read_cpu_jiffies_returns_tuple(self):
        total, idle = read_cpu_jiffies()
        assert isinstance(total, int)
        assert isinstance(idle, int)
        assert total > 0
        assert idle > 0
        assert total > idle

    def test_cpu_percent_from_two_readings(self):
        t1, i1 = read_cpu_jiffies()
        time.sleep(0.1)
        t2, i2 = read_cpu_jiffies()
        delta_total = t2 - t1
        delta_idle = i2 - i1
        assert delta_total > 0
        cpu_pct = (1.0 - delta_idle / delta_total) * 100.0
        assert 0.0 <= cpu_pct <= 100.0

    def test_get_cpu_count(self):
        count = get_cpu_count()
        assert count >= 1

    def test_read_own_process_rss(self):
        rss = read_process_rss(os.getpid())
        assert rss is not None
        assert rss > 0
        # Should be at least a few MB for the Python process
        assert rss > 1_000_000

    def test_read_nonexistent_pid_returns_none(self):
        assert read_process_rss(999999999) is None

    def test_read_system_memory(self):
        total, available = read_system_memory()
        assert total is not None
        assert available is not None
        assert total > 0
        assert available > 0
        assert total >= available

    def test_read_process_group_rss_own_pgid(self):
        pgid = os.getpgid(os.getpid())
        rss = read_process_group_rss(pgid)
        # May include this process and others in same group
        assert rss is not None
        assert rss > 0

    def test_read_process_group_rss_none_pgid(self):
        assert read_process_group_rss(None) is None


class TestSystemMonitorLifecycle:
    def test_start_stop(self, tmp_path):
        output = tmp_path / "test.system.ndjson"
        monitor = SystemMonitor(str(output), interval=0.1)
        monitor.start()
        time.sleep(0.5)
        monitor.stop()
        assert monitor._thread is not None
        assert not monitor._thread.is_alive()

    def test_context_manager(self, tmp_path):
        output = tmp_path / "test.system.ndjson"
        with SystemMonitor(str(output), interval=0.1):
            time.sleep(0.5)
        # Thread should be stopped after exiting context
        assert output.exists()

    def test_writes_ndjson_samples(self, tmp_path):
        output = tmp_path / "test.system.ndjson"
        with SystemMonitor(str(output), interval=0.1):
            time.sleep(1.0)  # Should produce ~8 samples (after initial wait)

        lines = [l for l in output.read_text().strip().split("\n") if l]
        assert len(lines) >= 2, f"Expected ≥2 samples, got {len(lines)}"

    def test_creates_parent_directory(self, tmp_path):
        output = tmp_path / "subdir" / "deep" / "test.system.ndjson"
        with SystemMonitor(str(output), interval=0.1):
            time.sleep(0.5)
        assert output.exists()


class TestNdjsonFormat:
    @pytest.fixture
    def sample_records(self, tmp_path):
        output = tmp_path / "test.system.ndjson"
        with SystemMonitor(str(output), interval=0.1):
            time.sleep(1.0)
        lines = [l for l in output.read_text().strip().split("\n") if l]
        return [json.loads(line) for line in lines]

    def test_each_line_is_valid_json(self, sample_records):
        assert len(sample_records) >= 2

    def test_has_timestamp_fields(self, sample_records):
        for rec in sample_records:
            assert "timestamp_iso" in rec
            assert "timestamp_epoch" in rec
            assert isinstance(rec["timestamp_epoch"], (int, float))
            assert rec["timestamp_epoch"] > 0

    def test_has_cpu_percent(self, sample_records):
        for rec in sample_records:
            assert "cpu_percent" in rec
            assert 0.0 <= rec["cpu_percent"] <= 100.0

    def test_has_cpu_cores(self, sample_records):
        for rec in sample_records:
            assert "cpu_cores" in rec
            assert rec["cpu_cores"] >= 1

    def test_has_memory_fields(self, sample_records):
        for rec in sample_records:
            assert "memory_rss_bytes" in rec
            assert "memory_available_bytes" in rec
            assert "memory_total_bytes" in rec
            # memory_rss_bytes may be null if no target_pgid
            assert rec["memory_available_bytes"] is not None
            assert rec["memory_total_bytes"] is not None
            assert rec["memory_total_bytes"] > 0

    def test_no_internal_fields_in_output(self, sample_records):
        for rec in sample_records:
            for key in rec:
                assert not key.startswith("_"), f"Internal field '{key}' leaked to output"

    def test_rss_null_without_target_pgid(self, sample_records):
        """Without target_pgid, memory_rss_bytes should be null."""
        for rec in sample_records:
            assert rec["memory_rss_bytes"] is None


class TestProcessGroupTracking:
    def test_tracks_subprocess_rss(self, tmp_path):
        """Launch a subprocess that allocates memory, verify RSS is captured."""
        output = tmp_path / "test.system.ndjson"

        # Launch a sleep subprocess in its own process group
        proc = subprocess.Popen(
            ["python3", "-c", "import time; x = bytearray(10_000_000); time.sleep(3)"],
            start_new_session=True,
        )
        pgid = os.getpgid(proc.pid)

        try:
            with SystemMonitor(str(output), interval=0.2, target_pgid=pgid):
                time.sleep(1.5)
        finally:
            proc.terminate()
            proc.wait()

        lines = [l for l in output.read_text().strip().split("\n") if l]
        records = [json.loads(l) for l in lines]

        # At least some records should have non-null RSS
        rss_values = [r["memory_rss_bytes"] for r in records if r["memory_rss_bytes"] is not None]
        assert len(rss_values) > 0, "Expected at least one sample with RSS data"
        # RSS should be > 10MB (our subprocess allocated 10MB)
        assert max(rss_values) > 5_000_000

    def test_handles_dead_process_gracefully(self, tmp_path):
        """If target process dies, RSS becomes null but monitor keeps running."""
        output = tmp_path / "test.system.ndjson"

        # Launch a subprocess that dies quickly
        proc = subprocess.Popen(
            ["python3", "-c", "import time; time.sleep(0.2)"],
            start_new_session=True,
        )
        pgid = os.getpgid(proc.pid)

        with SystemMonitor(str(output), interval=0.1, target_pgid=pgid):
            time.sleep(1.0)  # Process dies at 0.2s, monitor continues until 1.0s

        proc.wait()

        lines = [l for l in output.read_text().strip().split("\n") if l]
        records = [json.loads(l) for l in lines]

        # Should have samples after process died (RSS = null)
        assert len(records) >= 3
        # Later samples should have null RSS (process died)
        late_records = records[len(records)//2:]
        null_rss = [r for r in late_records if r["memory_rss_bytes"] is None]
        assert len(null_rss) > 0, "Expected null RSS after process died"


class TestOverhead:
    def test_proc_reads_are_fast(self):
        """Reading /proc should be <1ms per sample — verify 1000 reads < 500ms."""
        import timeit
        
        def one_sample():
            read_cpu_jiffies()
            read_system_memory()
            read_process_rss(os.getpid())

        elapsed = timeit.timeit(one_sample, number=1000)
        assert elapsed < 0.5, f"1000 /proc reads took {elapsed:.2f}s — expected <0.5s"
