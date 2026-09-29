//! Metrics collection and NDJSON output.

mod collector;
mod hdr;
mod ndjson_writer;

pub use collector::{CommandMetrics, MetricsCollector};
pub use hdr::{encode_base64, new_histogram, HIGHEST_TRACKABLE_VALUE};
pub use ndjson_writer::{build_metadata, Metadata, NdjsonWriter};
