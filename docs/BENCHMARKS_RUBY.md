# Ruby Client Benchmarks — Full Details

Detailed performance comparison of Ruby client libraries running on GitHub Actions runners. Results are automatically updated via CI.

> **Note**: These benchmarks run on shared GitHub Actions runners. Results may have variance between runs due to noisy neighbor effects. The graphs show averages across multiple runs (n=count shown in labels).

## Single Client (1 connection)

### Throughput

![SET RPS](../graphs/ruby/1-client/rps-SET.png)
![GET RPS](../graphs/ruby/1-client/rps-GET.png)

### Latency P50

![SET Latency P50](../graphs/ruby/1-client/latency-p50-SET.png)
![GET Latency P50](../graphs/ruby/1-client/latency-p50-GET.png)

### Latency P95

![SET Latency P95](../graphs/ruby/1-client/latency-p95-SET.png)
![GET Latency P95](../graphs/ruby/1-client/latency-p95-GET.png)

### Latency P99

![SET Latency P99](../graphs/ruby/1-client/latency-p99-SET.png)
![GET Latency P99](../graphs/ruby/1-client/latency-p99-GET.png)

### Latency P999

![SET Latency P999](../graphs/ruby/1-client/latency-p999-SET.png)
![GET Latency P999](../graphs/ruby/1-client/latency-p999-GET.png)

---

## 10 Concurrent Clients

### Throughput

![SET RPS](../graphs/ruby/10-clients/rps-SET.png)
![GET RPS](../graphs/ruby/10-clients/rps-GET.png)

### Latency P50

![SET Latency P50](../graphs/ruby/10-clients/latency-p50-SET.png)
![GET Latency P50](../graphs/ruby/10-clients/latency-p50-GET.png)

### Latency P95

![SET Latency P95](../graphs/ruby/10-clients/latency-p95-SET.png)
![GET Latency P95](../graphs/ruby/10-clients/latency-p95-GET.png)

### Latency P99

![SET Latency P99](../graphs/ruby/10-clients/latency-p99-SET.png)
![GET Latency P99](../graphs/ruby/10-clients/latency-p99-GET.png)

### Latency P999

![SET Latency P999](../graphs/ruby/10-clients/latency-p999-SET.png)
![GET Latency P999](../graphs/ruby/10-clients/latency-p999-GET.png)

---

## 100 Concurrent Clients

### Throughput

![SET RPS](../graphs/ruby/100-clients/rps-SET.png)
![GET RPS](../graphs/ruby/100-clients/rps-GET.png)

### Latency P50

![SET Latency P50](../graphs/ruby/100-clients/latency-p50-SET.png)
![GET Latency P50](../graphs/ruby/100-clients/latency-p50-GET.png)

### Latency P95

![SET Latency P95](../graphs/ruby/100-clients/latency-p95-SET.png)
![GET Latency P95](../graphs/ruby/100-clients/latency-p95-GET.png)

### Latency P99

![SET Latency P99](../graphs/ruby/100-clients/latency-p99-SET.png)
![GET Latency P99](../graphs/ruby/100-clients/latency-p99-GET.png)

### Latency P999

![SET Latency P999](../graphs/ruby/100-clients/latency-p999-SET.png)
![GET Latency P999](../graphs/ruby/100-clients/latency-p999-GET.png)

---

## Drivers Tested

| Driver | Description |
|--------|-------------|
| redis-rb | The most popular Ruby Redis client |
| valkey-glide-ruby | Valkey's official Ruby client |

## Workload Configuration

All benchmarks use the same reference workload:
- **1M requests** per phase (STEADY)
- **1M keys** with 16-byte keys, `bench:` prefix
- **512 bytes** data size for SET operations
- **50/50 GET/SET** command mix (uniform random)
- **No rate limiting** (max throughput)
