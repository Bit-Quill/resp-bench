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

use std::time::{Duration, Instant};

use redis::Commands;

use crate::client::{BenchmarkClient, TimedResult};
use crate::config::DriverConfig;

type Pool = r2d2::Pool<redis::Client>;

pub struct RedisRsClient {
    pool: Option<Pool>,
    max_in_flight: u32,
}

impl RedisRsClient {
    pub fn new() -> Self {
        RedisRsClient {
            pool: None,
            max_in_flight: 1,
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
        // Build a redis:// URL. RESP3 is requested so the driver matches GLIDE's
        // negotiated protocol; auth and TLS map into the URL scheme/userinfo.
        let scheme = if config.tls_enabled() {
            "rediss"
        } else {
            "redis"
        };
        let userinfo = match config.auth_credentials() {
            Some((user, pass)) => {
                format!("{}:{}@", user.unwrap_or_default(), pass.unwrap_or_default())
            }
            None => String::new(),
        };
        let url = format!("{scheme}://{userinfo}{host}:{port}/?protocol=resp3");

        let client = redis::Client::open(url).map_err(|e| format!("redis-rs open: {e}"))?;

        let mut builder = r2d2::Pool::builder().max_size(self.max_in_flight.max(1));
        if let Some(ms) = config.command_timeout_ms.filter(|ms| *ms > 0) {
            // Bound how long a checked-out connection waits on a command.
            builder = builder.connection_timeout(Duration::from_millis(ms));
        }
        let pool = builder
            .build(client)
            .map_err(|e| format!("redis-rs pool: {e}"))?;
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
        m.insert("resp_protocol".to_string(), 3.into());
        m.insert("response_parser".to_string(), "redis-rs".into());
        m.insert("retries".to_string(), 0.into());
        m.insert(
            "pipelining".to_string(),
            "connection pool (1 socket per in-flight slot)".into(),
        );
        m
    }
}
