//! resp-bench Rust engine.
//!
//! A benchmark engine for RESP/Valkey at parity with the Java (reference),
//! Python, Go, Ruby, C#, PHP, and Node.js engines: the same driver/workload
//! JSON configs drive it, and it emits the same NDJSON metrics schema.

pub mod client;
pub mod command;
pub mod config;
pub mod engine;
pub mod metrics;
