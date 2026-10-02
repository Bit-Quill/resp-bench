//! redis-rs driver using the published `redis` crate with an `r2d2` connection
//! pool.
//!
//! Unlike GLIDE (which multiplexes over one socket), a sync `redis::Connection`
//! is single-threaded, so concurrent `pipeline_depth` workers each need their
//! own connection. This driver therefore holds an `r2d2::Pool` sized to
//! `pipeline_depth`: `sockets_per_client == pipeline_depth`, and `prime` opens
//! those sockets before the timed workload. This is the same model the Python
//! `redis-py` and Node `ioredis` drivers use.
//!
//! There is no way to give a sync redis-rs connection real in-flight depth on a
//! single socket, so a pool is the honest representation of `pipeline_depth`.
//!
//! Protocol note: the published `redis` 0.25 crate speaks RESP2 only — its
//! `RedisConnectionInfo` has no protocol field and it sends no `HELLO 3`. The
//! metadata therefore reports `resp_protocol: 2` (honest about what is on the
//! wire), in contrast to GLIDE's negotiated RESP3.

use std::time::{Duration, Instant};

use redis::{Commands, ConnectionAddr, ConnectionInfo, RedisConnectionInfo};

use crate::client::{BenchmarkClient, TimedResult};
use crate::config::DriverConfig;

type Pool = r2d2::Pool<TimeoutManager>;

/// An r2d2 connection manager that opens plain `redis::Connection`s and applies
/// a fixed read/write timeout to each one. r2d2's own `connection_timeout` only
/// bounds `pool.get()`; it does nothing for a command that stalls mid-flight.
/// Setting the socket read/write timeout is what actually bounds a command, so
/// a paused server surfaces as a timed-out error rather than an unbounded wait
/// recorded as a success.
#[derive(Clone)]
struct TimeoutManager {
    info: ConnectionInfo,
    op_timeout: Option<Duration>,
}

impl r2d2::ManageConnection for TimeoutManager {
    type Connection = redis::Connection;
    type Error = redis::RedisError;

    fn connect(&self) -> Result<redis::Connection, redis::RedisError> {
        let client = redis::Client::open(self.info.clone())?;
        let conn = match self.op_timeout {
            Some(t) => client.get_connection_with_timeout(t)?,
            None => client.get_connection()?,
        };
        // Bound every subsequent command (not just connect) on the socket.
        if let Some(t) = self.op_timeout {
            conn.set_read_timeout(Some(t))?;
            conn.set_write_timeout(Some(t))?;
        }
        Ok(conn)
    }

    fn is_valid(&self, conn: &mut redis::Connection) -> Result<(), redis::RedisError> {
        redis::cmd("PING").query::<()>(conn)
    }

    fn has_broken(&self, _conn: &mut redis::Connection) -> bool {
        false
    }
}

pub struct RedisRsClient {
    pool: Option<Pool>,
    max_in_flight: u32,
    tls_enabled: bool,
}

impl RedisRsClient {
    pub fn new() -> Self {
        RedisRsClient {
            pool: None,
            max_in_flight: 1,
            tls_enabled: false,
        }
    }

    fn measure<F>(f: F) -> TimedResult
    where
        F: FnOnce() -> Result<(), ()>,
    {
        let start = Instant::now();
        let outcome = f();
        let mut micros = start.elapsed().as_micros() as u64;
        if micros < 1 {
            micros = 1;
        }
        match outcome {
            Ok(()) => TimedResult::ok(micros),
            Err(()) => TimedResult::err(micros),
        }
    }
}

impl Default for RedisRsClient {
    fn default() -> Self {
        Self::new()
    }
}

impl BenchmarkClient for RedisRsClient {
    fn connect(&mut self, host: &str, port: u16, config: &DriverConfig) -> Result<(), String> {
        // This driver uses a standalone client + r2d2 pool. Cluster mode would
        // need redis-rs's separate ClusterClient (the `cluster` feature), which
        // r2d2 does not pool the same way, so reject it loudly rather than
        // silently connecting standalone against a cluster. Use the
        // `valkey-glide-rust` driver for cluster benchmarks.
        if config.is_cluster() {
            return Err(
                "redis-rs driver does not support cluster mode; use valkey-glide-rust".to_string(),
            );
        }

        // Build a ConnectionInfo struct directly (not a redis:// URL) so auth
        // credentials with URL-reserved characters (`#`, `%`, `@`, …) are passed
        // verbatim rather than percent-decoded or rejected by the URL parser.
        self.tls_enabled = config.tls_enabled();
        let addr = if self.tls_enabled {
            ConnectionAddr::TcpTls {
                host: host.to_string(),
                port,
                insecure: false,
                tls_params: None,
            }
        } else {
            ConnectionAddr::Tcp(host.to_string(), port)
        };
        let (username, password) = match config.auth_credentials() {
            Some((u, p)) => (u, p),
            None => (None, None),
        };
        let info = ConnectionInfo {
            addr,
            redis: RedisConnectionInfo {
                db: 0,
                username,
                password,
            },
        };

        let op_timeout = config
            .command_timeout_ms
            .filter(|ms| *ms > 0)
            .map(Duration::from_millis);
        let manager = TimeoutManager { info, op_timeout };

        let mut builder = r2d2::Pool::builder()
            .max_size(self.max_in_flight.max(1))
            // Create connections lazily: `prime()` opens them before the timed
            // workload, and a dead server surfaces there. Building eagerly would
            // also make connect() block for the full connection_timeout against a
            // down server.
            .min_idle(Some(0))
            // r2d2 defaults `test_on_check_out` to true; for the redis crate that
            // check issues a PING inside pool.get(), which is inside the timed
            // path — a second round trip on every command. Turn it off so each
            // measured op is exactly one request.
            .test_on_check_out(false);
        if let Some(t) = op_timeout {
            // Bound only the wait to obtain a connection from the pool. The
            // per-command bound is the socket read/write timeout set in the
            // manager above.
            builder = builder.connection_timeout(t);
        }
        let pool = builder
            // Defer connection creation to prime()/first use (see min_idle above).
            .build_unchecked(manager);
        self.pool = Some(pool);
        Ok(())
    }

    fn get(&self, key: &str) -> TimedResult {
        Self::measure(|| match &self.pool {
            Some(pool) => match pool.get() {
                Ok(mut conn) => conn
                    .get::<_, Option<String>>(key)
                    .map(|_| ())
                    .map_err(|_| ()),
                Err(_) => Err(()),
            },
            None => Err(()),
        })
    }

    fn set(&self, key: &str, value: &[u8]) -> TimedResult {
        Self::measure(|| match &self.pool {
            Some(pool) => match pool.get() {
                Ok(mut conn) => conn.set::<_, _, ()>(key, value).map_err(|_| ()),
                Err(_) => Err(()),
            },
            None => Err(()),
        })
    }

    fn ping(&self) -> TimedResult {
        Self::measure(|| match &self.pool {
            Some(pool) => match pool.get() {
                Ok(mut conn) => redis::cmd("PING")
                    .query::<String>(&mut *conn)
                    .map(|_| ())
                    .map_err(|_| ()),
                Err(_) => Err(()),
            },
            None => Err(()),
        })
    }

    fn close(&mut self) {
        // r2d2 closes idle connections when the pool is dropped.
        self.pool = None;
    }

    fn set_max_in_flight(&mut self, depth: u32) {
        self.max_in_flight = depth.max(1);
    }

    fn prime(&mut self) -> Result<(), String> {
        // Pre-open the pool's sockets so the timed workload does not pay for
        // connection establishment. Checking out `max_size` connections at once
        // forces the pool to create them, then they return on drop.
        if let Some(pool) = &self.pool {
            let mut held = Vec::new();
            for _ in 0..self.max_in_flight {
                match pool.get() {
                    Ok(conn) => held.push(conn),
                    Err(e) => return Err(format!("redis-rs prime: {e}")),
                }
            }
        }
        Ok(())
    }

    fn sockets_per_client(&self) -> u32 {
        // A pooling driver holds one socket per in-flight slot.
        self.max_in_flight
    }

    fn driver_version(&self) -> String {
        // The redis crate does not expose its version at runtime; report the
        // crate name so the metadata is not empty.
        "redis-rs".to_string()
    }

    fn driver_details(&self) -> serde_json::Map<String, serde_json::Value> {
        let mut m = serde_json::Map::new();
        // redis 0.25 speaks RESP2 only (no HELLO 3). Report that honestly so the
        // GLIDE (RESP3) vs redis-rs (RESP2) comparison is not mislabeled.
        m.insert("resp_protocol".to_string(), 2.into());
        m.insert("response_parser".to_string(), "redis-rs".into());
        m.insert("retries".to_string(), 0.into());
        m.insert("tls".to_string(), self.tls_enabled.into());
        m.insert(
            "pipelining".to_string(),
            "connection pool (1 socket per in-flight slot)".into(),
        );
        m
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::parse_driver_config_str;

    // Building the pool must not parse credentials through a URL. A password with
    // URL-reserved characters (`#`, `%`, `@`) that would break or be mangled by a
    // redis:// URL must be accepted verbatim. The pool creates connections
    // lazily and test_on_check_out is off, so connect() opens no socket and this
    // runs without a server.
    #[test]
    fn connect_accepts_url_reserved_password_chars() {
        let cfg = parse_driver_config_str(
            r#"{"schema_version":"1.0","driver_id":"redis-rs","mode":"standalone","auth":{"username":"u","password":"ab#cd%41@x"}}"#,
        )
        .unwrap();
        let mut client = RedisRsClient::new();
        client.set_max_in_flight(2);
        // Localhost:1 has no listener, but connect() only builds the (lazy) pool;
        // it must not fail parsing the password.
        client
            .connect("127.0.0.1", 1, &cfg)
            .expect("connect built the pool with a reserved-char password");
    }

    #[test]
    fn cluster_mode_is_rejected() {
        let cfg = parse_driver_config_str(
            r#"{"schema_version":"1.0","driver_id":"redis-rs","mode":"cluster"}"#,
        )
        .unwrap();
        let mut client = RedisRsClient::new();
        let err = client.connect("127.0.0.1", 6379, &cfg).unwrap_err();
        assert!(err.contains("cluster"), "unexpected error: {err}");
    }

    #[test]
    fn metadata_reports_resp2_and_tls_flag() {
        let cfg = parse_driver_config_str(
            r#"{"schema_version":"1.0","driver_id":"redis-rs","mode":"standalone"}"#,
        )
        .unwrap();
        let mut client = RedisRsClient::new();
        client.connect("127.0.0.1", 1, &cfg).unwrap();
        let d = client.driver_details();
        assert_eq!(d["resp_protocol"], 2);
        assert_eq!(d["tls"], false);
    }
}
