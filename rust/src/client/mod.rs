//! Client abstraction over the benchmark drivers.

mod factory;
pub mod impl_;
mod timed_result;

pub use factory::{create_and_connect, supported_drivers, ClientError};
pub use timed_result::TimedResult;

use crate::config::DriverConfig;

/// A benchmark client: one logical connection to the server.
///
/// A driver may multiplex all requests over a single socket (GLIDE) or use a
/// pool sized to `pipeline_depth` (a pooling driver). `sockets_per_client`
/// reports which, so the metrics can distinguish them.
///
/// Implementations must be `Send + Sync`: one client is shared across its
/// `pipeline_depth` worker threads.
pub trait BenchmarkClient: Send + Sync {
    /// Establish the connection.
    fn connect(&mut self, host: &str, port: u16, config: &DriverConfig) -> Result<(), String>;

    /// Execute `GET key`.
    fn get(&self, key: &str) -> TimedResult;

    /// Execute `SET key value`.
    fn set(&self, key: &str, value: &[u8]) -> TimedResult;

    /// Execute `PING`.
    fn ping(&self) -> TimedResult;

    /// Close the connection.
    fn close(&mut self);

    /// Declare the max in-flight requests this client will be driven at, so a
    /// pooling driver can size its pool. Default: no-op (multiplexing driver).
    fn set_max_in_flight(&mut self, _depth: u32) {}

    /// Open any extra sockets before the timed workload. Default: no-op.
    fn prime(&mut self) -> Result<(), String> {
        Ok(())
    }

    /// Server-side sockets per client: 1 for a multiplexing driver at any depth,
    /// `pipeline_depth` for a pooling one.
    fn sockets_per_client(&self) -> u32 {
        1
    }

    /// Best-effort primary driver version for metrics metadata.
    fn driver_version(&self) -> String {
        "unknown".to_string()
    }

    /// Additive metadata describing what is actually being measured (negotiated
    /// protocol, response parser, retry count, pipelining mechanism).
    fn driver_details(&self) -> serde_json::Map<String, serde_json::Value> {
        serde_json::Map::new()
    }
}
