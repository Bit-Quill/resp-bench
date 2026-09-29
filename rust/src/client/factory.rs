//! Driver registry: maps `driver_id` to a client implementation.

use std::fmt;

use crate::client::impl_::glide::GlideClient;
use crate::client::impl_::recording::RecordingClient;
use crate::client::impl_::redis_rs::RedisRsClient;
use crate::client::BenchmarkClient;
use crate::config::DriverConfig;

/// A client creation/connection error.
#[derive(Debug)]
pub struct ClientError(pub String);

impl fmt::Display for ClientError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for ClientError {}

/// Driver ids this engine supports, in a stable order for `--info`.
pub fn supported_drivers() -> Vec<&'static str> {
    vec!["valkey-glide-rust", "redis-rs", "recording"]
}

fn create(driver_id: &str) -> Result<Box<dyn BenchmarkClient>, ClientError> {
    match driver_id.to_lowercase().as_str() {
        "valkey-glide-rust" | "valkey-glide" => Ok(Box::new(GlideClient::new())),
        "redis-rs" => Ok(Box::new(RedisRsClient::new())),
        "recording" => Ok(Box::new(RecordingClient::new())),
        other => Err(ClientError(format!(
            "Unknown driver: {other}. Supported: {}",
            supported_drivers().join(", ")
        ))),
    }
}

/// Create a client, size it for `pipeline_depth`, connect, and prime it.
pub fn create_and_connect(
    host: &str,
    port: u16,
    config: &DriverConfig,
    pipeline_depth: u32,
) -> Result<Box<dyn BenchmarkClient>, ClientError> {
    let mut client = create(&config.driver_id)?;
    // Declared before connect so a pooling driver can size its pool.
    client.set_max_in_flight(pipeline_depth);
    client.connect(host, port, config).map_err(ClientError)?;
    // Open any extra sockets before the timed workload.
    client.prime().map_err(ClientError)?;
    Ok(client)
}
