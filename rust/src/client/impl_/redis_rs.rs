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

use redis::{Commands, ConnectionAddr, ConnectionInfo, ConnectionLike, RedisConnectionInfo};

use crate::client::{BenchmarkClient, TimedResult};
use crate::config::DriverConfig;

type Pool = r2d2::Pool<TimeoutManager>;

/// A pooled `redis::Connection` that remembers whether a command on it failed.
///
/// A sync redis connection is stateful: if a command errors mid-flight (most
/// importantly a read timeout against a stalled server), the server's reply may
/// still be in transit. Returning such a socket to the pool unchanged would let
/// the next checkout read that stale reply as its own response — undercounting
/// errors and shifting latencies for the rest of the phase. We flag the
/// connection on any error and report it via `ManageConnection::has_broken`, so
/// r2d2 discards it and opens a fresh one.
///
/// `ConnectionLike` is implemented by delegating to the inner connection and
/// setting the flag whenever a request returns `Err`, so every command path
/// (`get`/`set`/`PING`) poisons automatically without per-call bookkeeping.
struct ManagedConn {
    inner: redis::Connection,
    broken: bool,
}

impl ConnectionLike for ManagedConn {
    fn req_packed_command(&mut self, cmd: &[u8]) -> redis::RedisResult<redis::Value> {
        let r = self.inner.req_packed_command(cmd);
        if r.is_err() {
            self.broken = true;
        }
        r
    }

    fn req_packed_commands(
        &mut self,
        cmd: &[u8],
        offset: usize,
        count: usize,
    ) -> redis::RedisResult<Vec<redis::Value>> {
        let r = self.inner.req_packed_commands(cmd, offset, count);
        if r.is_err() {
            self.broken = true;
        }
        r
    }

    fn get_db(&self) -> i64 {
        self.inner.get_db()
    }

    fn check_connection(&mut self) -> bool {
        self.inner.check_connection()
    }

    fn is_open(&self) -> bool {
        self.inner.is_open()
    }
}

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
    type Connection = ManagedConn;
    type Error = redis::RedisError;

    fn connect(&self) -> Result<ManagedConn, redis::RedisError> {
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
        Ok(ManagedConn {
            inner: conn,
            broken: false,
        })
    }

    fn is_valid(&self, conn: &mut ManagedConn) -> Result<(), redis::RedisError> {
        redis::cmd("PING").query::<()>(conn)
    }

    fn has_broken(&self, conn: &mut ManagedConn) -> bool {
        // Discard any connection that saw a command error (e.g. a read timeout):
        // its socket may still hold a pending reply that would be mis-read by the
        // next checkout.
        conn.broken
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

    fn live_server() -> Option<(String, u16)> {
        let host = std::env::var("VALKEY_HOST").ok()?;
        let port = std::env::var("VALKEY_PORT")
            .ok()
            .and_then(|p| p.parse().ok())
            .unwrap_or(6379);
        Some((host, port))
    }

    // A command that times out leaves its reply pending on the socket; reusing
    // that connection makes the NEXT command read the stale reply. This drives
    // the production TimeoutManager/ManagedConn through a size-1 r2d2 pool (so a
    // reused connection is the SAME socket) and asserts the actual returned
    // value: after a timed-out `GET k0`, a `GET k1` must return k1's value, not
    // k0's stale reply. Reverting has_broken to `false` makes this fail.
    #[test]
    #[ignore = "requires a live Valkey server (VALKEY_HOST)"]
    fn timed_out_connection_is_discarded_not_reused_with_stale_reply() {
        let (host, port) = match live_server() {
            Some(v) => v,
            None => return,
        };

        // Seed two distinct keys via a control connection.
        let client = redis::Client::open(format!("redis://{host}:{port}/")).unwrap();
        let mut ctrl = client.get_connection().unwrap();
        let _: () = redis::cmd("SET")
            .arg("stale:k0")
            .arg("VALUE_K0")
            .query(&mut ctrl)
            .unwrap();
        let _: () = redis::cmd("SET")
            .arg("stale:k1")
            .arg("VALUE_K1")
            .query(&mut ctrl)
            .unwrap();

        // Size-1 pool with a 300ms command timeout, built the same way connect()
        // builds it. Prime the single socket.
        let manager = TimeoutManager {
            info: ConnectionInfo {
                addr: ConnectionAddr::Tcp(host.clone(), port),
                redis: RedisConnectionInfo {
                    db: 0,
                    username: None,
                    password: None,
                },
            },
            op_timeout: Some(Duration::from_millis(300)),
        };
        let pool: Pool = r2d2::Pool::builder()
            .max_size(1)
            .min_idle(Some(0))
            .test_on_check_out(false)
            .connection_timeout(Duration::from_millis(300))
            .build_unchecked(manager);
        {
            let mut conn = pool.get().unwrap();
            let v: String = redis::cmd("GET").arg("stale:k0").query(&mut *conn).unwrap();
            assert_eq!(v, "VALUE_K0");
        }

        // Pause ALL for 2s so the next GET times out at 300ms, poisoning the one
        // pooled socket.
        let _: () = redis::cmd("CLIENT")
            .arg("PAUSE")
            .arg(2000)
            .arg("ALL")
            .query(&mut ctrl)
            .unwrap();
        {
            let mut conn = pool.get().unwrap();
            let r: redis::RedisResult<String> = redis::cmd("GET").arg("stale:k0").query(&mut *conn);
            assert!(r.is_err(), "GET during full pause should have timed out");
            // Dropping `conn` returns it to the pool; has_broken must evict it.
        }

        // After the pause lifts, a GET of the OTHER key must return k1's value.
        // If the poisoned socket were reused, this would instead read k0's late
        // reply ("VALUE_K0") or hit a protocol desync error.
        std::thread::sleep(Duration::from_millis(2200));
        let mut conn = pool.get().unwrap();
        let got: String = redis::cmd("GET").arg("stale:k1").query(&mut *conn).unwrap();
        assert_eq!(
            got, "VALUE_K1",
            "reused a timed-out connection and read a stale reply"
        );
    }
}
