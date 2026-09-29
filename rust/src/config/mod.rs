//! Configuration types and loader.
//!
//! These mirror the shared JSON schemas (`configs/schemas/`) and the field
//! names/defaults used by the Java (reference), Python, Go, and other engines,
//! so the same config files drive every engine identically.

mod command;
mod completion;
mod driver;
mod keyspace;
mod loader;
mod phase;
mod workload;

pub use command::CommandConfig;
pub use completion::CompletionConfig;
pub use driver::DriverConfig;
pub use keyspace::{KeyspaceConfig, DEFAULT_KEY_PREFIX, DEFAULT_KEY_SIZE_BYTES};
pub use loader::{
    load_driver_config, load_workload_config, parse_driver_config_str, parse_workload_config_str,
    ConfigError,
};
pub use phase::PhaseConfig;
pub use workload::WorkloadConfig;
