//! valkey-glide driver using the blocking `sync` layer of the native Rust
//! client (`glide` crate; APIs mirror redis-rs).
//!
//! `SyncGlideClient` is a cheaply-cloneable handle that multiplexes all requests
//! over a single socket on a shared Tokio runtime. So one client per connection
//! honors the `client == connection` invariant, and a phase's `pipeline_depth`
//! is satisfied natively: the depth worker threads share the one client and put
//! that many requests on the wire without opening extra sockets. Therefore
//! `sockets_per_client` is always 1 and `prime` is a no-op.

use std::time::{Duration, Instant};

use glide::sync::{SyncGlideClient, SyncGlideClusterClient};
use glide::{
    Commands, GlideClientConfiguration, GlideClusterClientConfiguration, ServerCredentials,
};

use crate::client::{BenchmarkClient, TimedResult};
use crate::config::DriverConfig;

enum Conn {
    Standalone(SyncGlideClient),
    Cluster(SyncGlideClusterClient),
}

pub struct GlideClient {
    conn: Option<Conn>,
}

impl GlideClient {
    pub fn new() -> Self {
        GlideClient { conn: None }
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

impl Default for GlideClient {
    fn default() -> Self {
        Self::new()
    }
}

impl BenchmarkClient for GlideClient {
    fn connect(&mut self, host: &str, port: u16, config: &DriverConfig) -> Result<(), String> {
        let credentials = config.auth_credentials().map(|(user, pass)| match user {
            Some(u) => ServerCredentials::username_password(u, pass.unwrap_or_default()),
            None => ServerCredentials::password(pass.unwrap_or_default()),
        });
        let use_tls = config.tls_enabled();
        let timeout = config
            .command_timeout_ms
            .filter(|ms| *ms > 0)
            .map(Duration::from_millis);

        if config.is_cluster() {
            let mut conf = GlideClusterClientConfiguration::with_address(host, port);
            if use_tls {
                conf = conf.tls(glide::TlsConfig::SecureTls);
            }
            if let Some(creds) = credentials {
                conf = conf.credentials(creds);
            }
            if let Some(t) = timeout {
                conf = conf.request_timeout(t);
            }
            let client =
                SyncGlideClusterClient::connect(conf).map_err(|e| format!("glide connect: {e}"))?;
            self.conn = Some(Conn::Cluster(client));
        } else {
            let mut conf = GlideClientConfiguration::with_address(host, port);
            if use_tls {
                conf = conf.tls(glide::TlsConfig::SecureTls);
            }
            if let Some(creds) = credentials {
                conf = conf.credentials(creds);
            }
            if let Some(t) = timeout {
                conf = conf.request_timeout(t);
            }
            let client =
                SyncGlideClient::connect(conf).map_err(|e| format!("glide connect: {e}"))?;
            self.conn = Some(Conn::Standalone(client));
        }
        Ok(())
    }

    fn get(&self, key: &str) -> TimedResult {
        Self::measure(|| match self.conn.as_ref() {
            Some(Conn::Standalone(c)) => {
                c.get::<_, Option<String>>(key).map(|_| ()).map_err(|_| ())
            }
            Some(Conn::Cluster(c)) => c.get::<_, Option<String>>(key).map(|_| ()).map_err(|_| ()),
            None => Err(()),
        })
    }

    fn set(&self, key: &str, value: &[u8]) -> TimedResult {
        Self::measure(|| match self.conn.as_ref() {
            Some(Conn::Standalone(c)) => c.set::<_, _, ()>(key, value).map_err(|_| ()),
            Some(Conn::Cluster(c)) => c.set::<_, _, ()>(key, value).map_err(|_| ()),
            None => Err(()),
        })
    }

    fn ping(&self) -> TimedResult {
        Self::measure(|| match self.conn.as_ref() {
            Some(Conn::Standalone(c)) => c.ping().map(|_| ()).map_err(|_| ()),
            Some(Conn::Cluster(c)) => c.ping().map(|_| ()).map_err(|_| ()),
            None => Err(()),
        })
    }

    fn close(&mut self) {
        // The sync client closes when dropped.
        self.conn = None;
    }

    fn sockets_per_client(&self) -> u32 {
        1
    }

    fn driver_version(&self) -> String {
        // The glide crate does not expose its version at runtime; report the
        // package name so the metadata is not empty.
        "glide-rust".to_string()
    }

    fn driver_details(&self) -> serde_json::Map<String, serde_json::Value> {
        // GLIDE negotiates RESP3 and parses in Rust; neither is
        // environment-dependent. Recorded for symmetry with the peer drivers.
        let mut m = serde_json::Map::new();
        m.insert("resp_protocol".to_string(), 3.into());
        m.insert("response_parser".to_string(), "glide-rust".into());
        m.insert("retries".to_string(), 0.into());
        m.insert(
            "pipelining".to_string(),
            "multiplexed (1 socket per client)".into(),
        );
        m
    }
}
