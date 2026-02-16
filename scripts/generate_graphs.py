#!/usr/bin/env python3
"""
Graph generator for resp-bench benchmark results.

Reads NDJSON result files and generates performance comparison graphs
for RPS (requests per second) and latency percentiles (p50, p95, p99, p999).

Aggregation Policy:
- Results are filtered by commit_id to ensure only results from the same CI run are compared
- If --commit-id is not provided, the script auto-detects it from the git repository
- Each driver's version is displayed in graph labels but NOT used for filtering

Usage:
    python generate_graphs.py \
        --results results/github-runner/reference/*.json \
        --output graphs/ \
        --phase STEADY

    # Or with explicit commit filter:
    python generate_graphs.py \
        --results results/github-runner/reference/*.json \
        --output graphs/ \
        --phase STEADY \
        --commit-id abc123

Author: Ilia Kolominsky
"""

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Any, NamedTuple

import matplotlib.pyplot as plt
import numpy as np


class AggregationKey(NamedTuple):
    """3-tuple key for aggregating results from the same CI run."""
    commit_id: Optional[str]
    primary_driver_version: Optional[str]
    secondary_driver_version: Optional[str]


def parse_args():
    parser = argparse.ArgumentParser(
        description="Generate performance comparison graphs from benchmark results"
    )
    parser.add_argument(
        "--results",
        nargs="+",
        required=True,
        help="Path(s) to NDJSON result files (supports glob patterns)",
    )
    parser.add_argument(
        "--output",
        default="graphs/",
        help="Output directory for generated graphs (default: graphs/)",
    )
    parser.add_argument(
        "--phase",
        default="STEADY",
        help="Phase ID to extract metrics from (default: STEADY)",
    )
    parser.add_argument(
        "--commit-id",
        help="Filter results by commit ID (default: auto-detect from git repository)",
    )
    parser.add_argument(
        "--primary-driver-version",
        help="Filter results by primary driver version (default: include all versions)",
    )
    parser.add_argument(
        "--secondary-driver-version",
        help="Filter results by secondary driver version (default: include all versions)",
    )
    parser.add_argument(
        "--workload",
        help="Workload name for graph titles",
    )
    return parser.parse_args()


def load_results(file_paths: List[str], phase_filter: str) -> List[Dict[str, Any]]:
    """Load NDJSON result files and extract specified phase data."""
    results = []
    
    for file_path in file_paths:
        path = Path(file_path)
        if not path.exists():
            print(f"Warning: File not found: {file_path}", file=sys.stderr)
            continue
        
        with open(path, "r") as f:
            for line_num, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    data = json.loads(line)
                    # Filter by phase
                    if data.get("phase", {}).get("id") == phase_filter:
                        # Add source file info
                        data["_source_file"] = str(path)
                        results.append(data)
                except json.JSONDecodeError as e:
                    print(f"Warning: JSON parse error in {file_path}:{line_num}: {e}", file=sys.stderr)
    
    return results


def get_current_commit_id() -> Optional[str]:
    """
    Get the current git commit ID (short form) from the repository.
    
    Appends '-dirty' suffix if there are uncommitted changes (matching Java engine behavior).
    
    Returns None if git is not available or we're not in a git repository.
    """
    try:
        # Get short commit hash
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        )
        commit_id = result.stdout.strip()
        
        # Check for uncommitted changes (dirty state)
        status_result = subprocess.run(
            ["git", "status", "--porcelain"],
            capture_output=True,
            text=True,
            check=True,
        )
        if status_result.stdout.strip():
            commit_id += "-dirty"
        
        return commit_id
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def matches_aggregation_key(
    result: Dict[str, Any],
    key: AggregationKey,
) -> bool:
    """
    Check if a result matches the aggregation key.
    
    For each component of the key:
    - If the key component is None, it matches any value
    - If the key component is set, the result must match exactly
    """
    metadata = result.get("metadata", {})
    
    # Check commit_id
    if key.commit_id is not None:
        if metadata.get("commit_id") != key.commit_id:
            return False
    
    # Check primary_driver_version
    if key.primary_driver_version is not None:
        if metadata.get("primary_driver_version") != key.primary_driver_version:
            return False
    
    # Check secondary_driver_version
    if key.secondary_driver_version is not None:
        if metadata.get("secondary_driver_version") != key.secondary_driver_version:
            return False
    
    return True


def aggregate_results(
    results: List[Dict[str, Any]],
    aggregation_key: AggregationKey,
) -> Dict[str, Dict[str, Any]]:
    """
    Aggregate results by driver, computing averages across multiple runs.
    
    Only includes results that match the aggregation key.
    
    Returns a dict keyed by driver label (e.g., "Jedis 5.2.0") with aggregated metrics.
    """
    # Group by driver + version
    grouped = defaultdict(list)
    
    for result in results:
        # Filter by aggregation key
        if not matches_aggregation_key(result, aggregation_key):
            continue
        
        metadata = result.get("metadata", {})
        driver_id = metadata.get("driver_id", "unknown")
        version = metadata.get("primary_driver_version", "unknown")
        secondary = metadata.get("secondary_driver_id")
        
        # Build label
        if secondary:
            label = f"{driver_id} ({secondary}) {version}"
        else:
            label = f"{driver_id} {version}"
        
        grouped[label].append(result)
    
    # Aggregate
    aggregated = {}
    for label, runs in grouped.items():
        agg = {
            "run_count": len(runs),
            "commands": defaultdict(lambda: {
                "rps_values": [],
                "p50_values": [],
                "p95_values": [],
                "p99_values": [],
                "p999_values": [],
            }),
        }
        
        for run in runs:
            duration_ms = run.get("phase", {}).get("duration_ms", 1)
            metrics = run.get("metrics", {})
            
            for cmd_name, cmd_data in metrics.items():
                requests = cmd_data.get("requests", 0)
                rps = requests / (duration_ms / 1000) if duration_ms > 0 else 0
                
                latency = cmd_data.get("latency", {}).get("summary", {})
                
                agg["commands"][cmd_name]["rps_values"].append(rps)
                agg["commands"][cmd_name]["p50_values"].append(latency.get("p50", 0))
                agg["commands"][cmd_name]["p95_values"].append(latency.get("p95", 0))
                agg["commands"][cmd_name]["p99_values"].append(latency.get("p99", 0))
                agg["commands"][cmd_name]["p999_values"].append(latency.get("p999", 0))
        
        # Compute averages
        for cmd_name, cmd_data in agg["commands"].items():
            cmd_data["rps_avg"] = np.mean(cmd_data["rps_values"]) if cmd_data["rps_values"] else 0
            cmd_data["p50_avg"] = np.mean(cmd_data["p50_values"]) if cmd_data["p50_values"] else 0
            cmd_data["p95_avg"] = np.mean(cmd_data["p95_values"]) if cmd_data["p95_values"] else 0
            cmd_data["p99_avg"] = np.mean(cmd_data["p99_values"]) if cmd_data["p99_values"] else 0
            cmd_data["p999_avg"] = np.mean(cmd_data["p999_values"]) if cmd_data["p999_values"] else 0
        
        aggregated[label] = agg
    
    return aggregated


def create_horizontal_bar_chart(
    data: Dict[str, float],
    run_counts: Dict[str, int],
    title: str,
    xlabel: str,
    output_path: Path,
    color: str = "#4CAF50",
):
    """Create a horizontal bar chart for the given metric."""
    if not data:
        print(f"Warning: No data for {title}", file=sys.stderr)
        return
    
    # Sort by value (descending for RPS, ascending for latency)
    is_latency = "latency" in title.lower()
    sorted_items = sorted(data.items(), key=lambda x: x[1], reverse=not is_latency)
    
    labels = []
    values = []
    for driver, value in sorted_items:
        run_count = run_counts.get(driver, 1)
        labels.append(f"{driver} (n={run_count})")
        values.append(value)
    
    fig, ax = plt.subplots(figsize=(12, max(4, len(labels) * 0.5)))
    
    y_pos = np.arange(len(labels))
    bars = ax.barh(y_pos, values, color=color, edgecolor="black", linewidth=0.5)
    
    ax.set_yticks(y_pos)
    ax.set_yticklabels(labels)
    ax.set_xlabel(xlabel)
    ax.set_title(title, fontsize=14, fontweight="bold")
    
    # Add value labels on bars
    for bar, value in zip(bars, values):
        width = bar.get_width()
        if is_latency:
            label = f"{value:,.0f}"
        else:
            label = f"{value:,.0f}"
        ax.text(
            width + max(values) * 0.01,
            bar.get_y() + bar.get_height() / 2,
            label,
            ha="left",
            va="center",
            fontsize=9,
        )
    
    # Adjust x-axis to fit labels
    ax.set_xlim(0, max(values) * 1.15)
    
    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close()
    
    print(f"Generated: {output_path}")


def generate_graphs(
    aggregated: Dict[str, Dict[str, Any]],
    output_dir: Path,
    workload_name: Optional[str] = None,
):
    """Generate all graphs from aggregated results."""
    output_dir.mkdir(parents=True, exist_ok=True)
    
    # Collect all commands across all drivers
    all_commands = set()
    for driver_data in aggregated.values():
        all_commands.update(driver_data["commands"].keys())
    
    # Build run count lookup
    run_counts = {driver: data["run_count"] for driver, data in aggregated.items()}
    
    # Color palette
    rps_color = "#2196F3"  # Blue
    latency_colors = {
        "p50": "#4CAF50",   # Green
        "p95": "#FF9800",   # Orange
        "p99": "#F44336",   # Red
        "p999": "#9C27B0",  # Purple
    }
    
    workload_suffix = f" - {workload_name}" if workload_name else ""
    
    # Generate RPS graphs per command
    for cmd in sorted(all_commands):
        rps_data = {}
        for driver, data in aggregated.items():
            if cmd in data["commands"]:
                rps_data[driver] = data["commands"][cmd]["rps_avg"]
        
        if rps_data:
            create_horizontal_bar_chart(
                rps_data,
                run_counts,
                f"Throughput - {cmd} Command{workload_suffix}",
                "Requests per Second (RPS)",
                output_dir / f"rps-{cmd}.png",
                color=rps_color,
            )
    
    # Generate latency graphs per percentile
    for percentile, color in latency_colors.items():
        for cmd in sorted(all_commands):
            latency_data = {}
            for driver, data in aggregated.items():
                if cmd in data["commands"]:
                    latency_data[driver] = data["commands"][cmd][f"{percentile}_avg"]
            
            if latency_data:
                create_horizontal_bar_chart(
                    latency_data,
                    run_counts,
                    f"Latency {percentile.upper()} - {cmd} Command{workload_suffix}",
                    "Latency (microseconds)",
                    output_dir / f"latency-{percentile}-{cmd}.png",
                    color=color,
                )


def main():
    args = parse_args()
    
    # Expand glob patterns
    from glob import glob
    all_files = []
    for pattern in args.results:
        expanded = glob(pattern)
        if expanded:
            all_files.extend(expanded)
        else:
            all_files.append(pattern)  # Try as literal path
    
    if not all_files:
        print("Error: No result files found", file=sys.stderr)
        sys.exit(1)
    
    print(f"Loading {len(all_files)} result file(s)...")
    results = load_results(all_files, args.phase)
    
    if not results:
        print(f"Error: No results found for phase '{args.phase}'", file=sys.stderr)
        sys.exit(1)
    
    print(f"Found {len(results)} result record(s) for phase '{args.phase}'")
    
    # Determine commit_id for filtering
    commit_id = args.commit_id
    if not commit_id:
        # Auto-detect from git repository
        commit_id = get_current_commit_id()
        if commit_id:
            print(f"Using commit ID from git: {commit_id}")
        else:
            print(
                "Error: Could not determine commit ID. "
                "Please provide --commit-id or run from a git repository.",
                file=sys.stderr,
            )
            sys.exit(1)
    else:
        print(f"Using explicit commit ID: {commit_id}")
    
    # Build aggregation key - only filter by commit_id by default
    # Driver versions are NOT used for filtering (they appear in labels only)
    aggregation_key = AggregationKey(
        commit_id=commit_id,
        primary_driver_version=args.primary_driver_version,
        secondary_driver_version=args.secondary_driver_version,
    )
    
    # Aggregate
    aggregated = aggregate_results(results, aggregation_key)
    
    if not aggregated:
        print("Error: No results after filtering", file=sys.stderr)
        sys.exit(1)
    
    print(f"Aggregated results for {len(aggregated)} driver(s)")
    
    # Generate graphs
    output_dir = Path(args.output)
    generate_graphs(aggregated, output_dir, args.workload)
    
    print(f"\nGraphs generated in: {output_dir}")


if __name__ == "__main__":
    main()
