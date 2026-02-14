# Configuration Specification

This document provides the complete specification for resp-bench configuration files.

## Overview

resp-bench uses two JSON configuration files:
1. **Driver Config** - Specifies which client library to use
2. **Workload Config** - Defines the benchmark phases and traffic patterns

## Driver Configuration

### Schema

```json
{
  "schema_version": "1.0",
  "description": "Human-readable description",
  "driver_id": "string",
  "mode": "standalone|cluster|sentinel",
  "tls": { ... },
  "auth": { ... },
  "specific_driver_config": { ... }
}
```

### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `schema_version` | string | Yes | Schema version, currently `"1.0"` |
| `description` | string | No | Human-readable description |
| `driver_id` | string | Yes | Client library identifier |
| `mode` | string | Yes | Server topology: `standalone`, `cluster`, or `sentinel` |
| `tls` | object | No | TLS/SSL configuration |
| `auth` | object | No | Authentication configuration |
| `specific_driver_config` | object | No | Driver-specific options |

### Driver IDs by Language

**Java:**
- `jedis` - Jedis client
- `lettuce` - Lettuce client
- `valkey-glide` - Valkey GLIDE client
- `redisson` - Redisson async client
- `spring-data-valkey` - Spring Data Valkey (requires `secondary_driver_id`)
- `spring-data-redis` - Spring Data Redis (requires `secondary_driver_id`)

**Python (planned):**
- `redis-py` - redis-py synchronous client
- `redis-py-async` - redis-py async client
- `valkey-glide` - Valkey GLIDE Python client

### TLS Configuration

```json
{
  "tls": {
    "enabled": true,
    "cert_path": "/path/to/client.crt",
    "key_path": "/path/to/client.key",
    "ca_path": "/path/to/ca.crt",
    "verify_hostname": true
  }
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | false | Enable TLS |
| `cert_path` | string | - | Path to client certificate |
| `key_path` | string | - | Path to client private key |
| `ca_path` | string | - | Path to CA certificate |
| `verify_hostname` | boolean | true | Verify server hostname |

### Authentication Configuration

```json
{
  "auth": {
    "username": "default",
    "password": "secret"
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `username` | string | Username for ACL authentication |
| `password` | string | Password for authentication |

### Driver-Specific Configuration

```json
{
  "specific_driver_config": {
    "secondary_driver_id": "valkey-glide",
    "pool_size": 10,
    "timeout_ms": 5000
  }
}
```

Used for framework drivers (e.g., Spring Data) that wrap other clients.

### Examples

**Jedis Standalone:**
```json
{
  "schema_version": "1.0",
  "description": "Jedis client - standalone mode",
  "driver_id": "jedis",
  "mode": "standalone"
}
```

**Spring Data Valkey with GLIDE:**
```json
{
  "schema_version": "1.0",
  "description": "Spring Data Valkey with Valkey-Glide driver",
  "driver_id": "spring-data-valkey",
  "mode": "standalone",
  "specific_driver_config": {
    "secondary_driver_id": "valkey-glide"
  }
}
```

**With TLS and Authentication:**
```json
{
  "schema_version": "1.0",
  "description": "Secure Lettuce client",
  "driver_id": "lettuce",
  "mode": "cluster",
  "tls": {
    "enabled": true,
    "ca_path": "/etc/ssl/certs/ca.crt"
  },
  "auth": {
    "password": "secret123"
  }
}
```

---

## Workload Configuration

### Schema

```json
{
  "schema_version": "1.0",
  "benchmark_profile": {
    "name": "string",
    "description": "string",
    "version": "string"
  },
  "phases": [
    { ... }
  ]
}
```

### Top-Level Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `schema_version` | string | Yes | Schema version, currently `"1.0"` |
| `benchmark_profile` | object | Yes | Metadata about this benchmark |
| `phases` | array | Yes | List of benchmark phases |

### Benchmark Profile

```json
{
  "benchmark_profile": {
    "name": "GET/SET Workload",
    "description": "Standard read-heavy workload",
    "version": "1.0.0"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Name of the benchmark |
| `description` | string | No | Detailed description |
| `version` | string | No | Version identifier |

### Phase Configuration

```json
{
  "id": "WARMUP",
  "description": "Warmup phase",
  "connections": 10,
  "cps_limit": -1,
  "rps_limit": -1,
  "pipeline_depth": 1,
  "warmup_requests": 1,
  "completion": { ... },
  "keyspace": { ... },
  "commands": [ ... ]
}
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | string | Yes | - | Phase identifier (appears in output) |
| `description` | string | No | - | Human-readable description |
| `connections` | integer | Yes | - | Number of client connections |
| `cps_limit` | integer | No | -1 | Connections per second limit (-1 = unlimited) |
| `rps_limit` | integer | No | -1 | Requests per second limit (-1 = unlimited) |
| `pipeline_depth` | integer | No | 1 | Max in-flight requests per connection |
| `warmup_requests` | integer | No | 1 | Warmup PINGs per connection (0 = disabled) |
| `completion` | object | Yes | - | Phase completion criteria |
| `keyspace` | object | Yes | - | Key generation configuration |
| `commands` | array | Yes | - | Commands to execute |

### Completion Criteria

**By Request Count:**
```json
{
  "completion": {
    "type": "requests",
    "requests": 100000
  }
}
```

**By Duration:**
```json
{
  "completion": {
    "type": "duration",
    "seconds": 60
  }
}
```

| Type | Field | Description |
|------|-------|-------------|
| `requests` | `requests` | Stop after this many requests |
| `duration` | `seconds` | Run for this many seconds |

### Keyspace Configuration

```json
{
  "keyspace": {
    "keys_count": 10000,
    "key_size_bytes": 16,
    "key_prefix": "bench:",
    "generation_alg": "uniform_rand",
    "seed": 12345
  }
}
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `keys_count` | integer | Yes | - | Total unique keys |
| `key_size_bytes` | integer | No | 16 | Target key size (excluding prefix) |
| `key_prefix` | string | Yes | - | Prefix for all keys |
| `generation_alg` | string | Yes | - | Key generation algorithm |
| `seed` | integer | Conditional | - | Random seed (required for `uniform_rand`) |

### Key Generation Algorithms

| Algorithm | Description | Seed Required |
|-----------|-------------|---------------|
| `sequential_int` | Keys 0, 1, 2, ... N-1 (wraps) | No |
| `uniform_rand` | Uniform random distribution | Yes |

### Command Configuration

```json
{
  "commands": [
    {"command": "get", "weight": 0.8},
    {"command": "set", "weight": 0.2, "data_size_bytes": 256}
  ]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `command` | string | Yes | Command name |
| `weight` | number | Yes | Selection weight (0.0-1.0) |
| `data_size_bytes` | integer | Conditional | Value size for write commands |

### Supported Commands

| Command | Description | Requires `data_size_bytes` |
|---------|-------------|----------------------------|
| `ping` | PING command | No |
| `get` | GET key | No |
| `set` | SET key value | Yes |
| `hget` | HGET key field | No |
| `hset` | HSET key field value | Yes |
| `lpush` | LPUSH key value | Yes |
| `lpop` | LPOP key | No |
| `sadd` | SADD key member | Yes |
| `smembers` | SMEMBERS key | No |

*Note: Command availability may vary by language engine.*

### Complete Workload Example

```json
{
  "schema_version": "1.0",
  "benchmark_profile": {
    "name": "Standard GET/SET Benchmark",
    "description": "Two-phase benchmark: warmup then steady-state",
    "version": "1.0.0"
  },
  "phases": [
    {
      "id": "WARMUP",
      "description": "Populate keys with sequential SET",
      "connections": 10,
      "cps_limit": -1,
      "rps_limit": -1,
      "completion": {
        "type": "requests",
        "requests": 10000
      },
      "keyspace": {
        "keys_count": 10000,
        "key_size_bytes": 16,
        "key_prefix": "bench:",
        "generation_alg": "sequential_int"
      },
      "commands": [
        {"command": "set", "weight": 1.0, "data_size_bytes": 256}
      ]
    },
    {
      "id": "STEADY",
      "description": "80/20 read/write workload",
      "connections": 50,
      "cps_limit": -1,
      "rps_limit": 10000,
      "pipeline_depth": 1,
      "completion": {
        "type": "duration",
        "seconds": 60
      },
      "keyspace": {
        "keys_count": 10000,
        "key_size_bytes": 16,
        "key_prefix": "bench:",
        "generation_alg": "uniform_rand",
        "seed": 42
      },
      "commands": [
        {"command": "get", "weight": 0.8},
        {"command": "set", "weight": 0.2, "data_size_bytes": 256}
      ]
    }
  ]
}
```

---

## Metrics Output Format

All language engines produce NDJSON (one JSON object per line):

```json
{
  "phase": {
    "id": "STEADY",
    "status": "COMPLETED",
    "start_timestamp": "2024-01-15T10:30:00.000Z",
    "finish_timestamp": "2024-01-15T10:31:00.000Z",
    "duration_ms": 60000,
    "connections": 50
  },
  "totals": {
    "requests": 600000,
    "errors": 5
  },
  "metrics": {
    "GET": {
      "requests": 480000,
      "errors": 3,
      "latency": {
        "unit": "us",
        "count": 479997,
        "summary": {
          "min": 45,
          "p50": 120,
          "p95": 250,
          "p99": 400,
          "p999": 800,
          "max": 15000
        },
        "hdr": {
          "format": "hdr",
          "sigfig": 3,
          "payload_b64": "HISTFAAAAEx..."
        }
      }
    },
    "SET": {
      "requests": 120000,
      "errors": 2,
      "latency": {
        "unit": "us",
        "count": 119998,
        "summary": {
          "min": 55,
          "p50": 140,
          "p95": 280,
          "p99": 450,
          "p999": 1000,
          "max": 18000
        },
        "hdr": {
          "format": "hdr",
          "sigfig": 3,
          "payload_b64": "HISTFAAAAEx..."
        }
      }
    }
  }
}
```

### Metrics Fields

| Field | Description |
|-------|-------------|
| `phase.id` | Phase identifier from config |
| `phase.status` | `COMPLETED` or `ERROR` |
| `phase.start_timestamp` | ISO-8601 UTC timestamp |
| `phase.finish_timestamp` | ISO-8601 UTC timestamp |
| `phase.duration_ms` | Phase duration in milliseconds |
| `phase.connections` | Number of connections used |
| `totals.requests` | Total requests across all commands |
| `totals.errors` | Total errors across all commands |
| `metrics.<CMD>.requests` | Requests for this command |
| `metrics.<CMD>.errors` | Errors for this command |
| `metrics.<CMD>.latency.unit` | Always `us` (microseconds) |
| `metrics.<CMD>.latency.count` | Successful latency samples |
| `metrics.<CMD>.latency.summary` | Percentile statistics |
| `metrics.<CMD>.latency.hdr` | HdrHistogram for full analysis |

### HdrHistogram Payload

The `payload_b64` is a base64-encoded compressed HdrHistogram that can be decoded for detailed analysis:

**Java:**
```java
byte[] compressed = Base64.getDecoder().decode(payload_b64);
ByteBuffer buffer = ByteBuffer.wrap(compressed);
Histogram histogram = Histogram.decodeFromCompressedByteBuffer(buffer, 0);
```

**Python:**
```python
import base64
from hdrhistogram import HdrHistogram

data = base64.b64decode(payload_b64)
histogram = HdrHistogram.decode(data)
```
