# Interactive Graph Generator

`generate_interactive_graphs.py` produces self-contained interactive HTML files with Plotly.js charts from benchmark results. It supports two directory layouts (auto-detected) and generates RPS scalability, latency percentile, CPU usage, and delta comparison charts.

## Quick Start

```bash
# From legacy layout (subdirectory-per-client-count)
python scripts/generate_interactive_graphs.py \
    results/m5.metal/high-throughput/ \
    --output graphs/interactive/m5.metal/ \
    --title "m5.metal → cache.r7g.2xlarge (Valkey 8.2.0)"

# From flat layout (run_benchmark_matrix.py output)
python scripts/generate_interactive_graphs.py \
    results/valkey-glide-thread-sweep/ \
    --output graphs/interactive/valkey-glide-sweep/ \
    --title "Valkey-Glide Thread Config Sweep"

# With custom reference driver for delta charts
python scripts/generate_interactive_graphs.py \
    results/valkey-glide-thread-sweep/ \
    --output graphs/interactive/valkey-glide-sweep/ \
    --reference spring-data-valkey-glide@cb=16,tw=16,pool_size=connections
```

## Supported Layouts

### Legacy Layout

Subdirectories per client count (the original output format):

```
results/
    1-clients/
        jedis.ndjson
        valkey-glide.ndjson
        spring-data-valkey-glide.ndjson
    4-clients/
        jedis.ndjson
        ...
    64-clients/
        ...
```

Each `.ndjson` file contains multiple STEADY phase records (one per iteration). The directory name encodes the client count; the filename encodes the driver/series.

### Flat Layout

Produced by `run_benchmark_matrix.py`. One NDJSON per series, all client counts inside:

```
results/
    spring-data-valkey-glide@cb=8,tw=8,pool_size=connections.ndjson
    spring-data-valkey-glide@cb=16,tw=16,pool_size=connections.ndjson
    *.cpu.ndjson
    _manifest.json  (optional)
```

The script groups STEADY records by `phase.connections` from the NDJSON itself. The `_manifest.json` provides config metadata for rich legends.

**Layout detection is automatic**: if the directory contains `N-clients/` subdirectories, legacy mode is used. Otherwise, flat mode.

## Charts Generated

| Chart | Description |
|-------|-------------|
| **RPS Scalability** | Total workload throughput (RPS) vs client count per series |
| **RPS Delta** | % advantage of reference series vs each other series |
| **Latency p50/p95/p99/p99.9** | Per-command latency percentiles vs client count (one chart per command × percentile) |
| **CPU Scalability** | Client-side CPU% during STEADY phase vs client count (if `.cpu.ndjson` exists) |
| **CPU Efficiency** | RPS per CPU% vs client count |
| **CPU Efficiency Delta** | % advantage of reference in RPS/CPU% |

## Outlier Filtering

Before averaging, each set of iteration results is filtered using 4-method consensus outlier detection:

1. **Modified Z-Score** (MAD-based, threshold=3.5)
2. **IQR** (box-plot, multiplier=1.5)
3. **Percentage Deviation from Median** (threshold=15%)
4. **Grubbs' Test** (alpha=0.05)

A run is discarded when flagged by ≥2 methods. The same outlier indices are applied to latency and CPU data for consistency.

## Color Scheme

### Legacy Mode (single series per driver)

Each driver family has a fixed color:
- **spring-data-valkey-***: greens/teals
- **spring-data-redis-***: blues
- **Low-level drivers** (jedis, lettuce, redisson, valkey-glide): reds/oranges/purples

### Flat Mode (multiple variants of the same driver)

When a driver has multiple config variants, auto-shading generates distinct colors:
- All variants of the same driver share the same **hue** but vary in **lightness**
- Different **line dash patterns** (solid, dashed, dotted, dash-dot) further distinguish variants
- Legend groups variants by driver family for easy toggle

## `_manifest.json` and Rich Legends

When a `_manifest.json` is present (produced by the matrix runner), the graph script uses it to build descriptive legend labels instead of raw filenames:

```
Raw filename:  spring-data-valkey-glide@cb=16,tw=16,pool_size=connections
Rich legend:   spring-data-valkey-glide | tokio=16, callback=16, pool_size=connections (0.2.0 / valkey-glide 2.2.3)
```

Without a manifest, the filename stem is used as-is.

## Reference Driver / Delta Charts

Delta charts show the percentage advantage of one **reference** series over all others. By default, `spring-data-valkey-glide` is the reference.

- For **legacy mode**: specify a driver name (e.g., `--reference valkey-glide`)
- For **flat mode**: specify the full series label (filename stem)
- For **single-driver sweeps** (all variants from one driver): the first variant in the manifest is auto-selected as reference

Positive delta = reference is faster. Negative = reference is slower.

## CLI Reference

```
python scripts/generate_interactive_graphs.py --help

positional arguments:
  results_dir          Directory containing benchmark results

optional arguments:
  --output, -o         Output directory for HTML file (default: graphs/interactive/)
  --title, -t          Title prefix for the report
  --reference, -r      Reference series for delta charts (default: spring-data-valkey-glide)
```

## Output

A single self-contained HTML file: `<output>/scalability_and_delta.html`

Open in any browser. All Plotly.js code is inlined — no external dependencies needed to view. Use the toolbar to zoom, pan, and download individual charts as PNG.
