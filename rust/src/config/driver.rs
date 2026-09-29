//! Driver (client library) configuration. Maps to
//! `configs/schemas/driver-config.schema.json`.

use serde::Deserialize;
use serde_json::Value;
use std::collections::HashMap;

/// Which client library to use and how to connect.
#[derive(Debug, Clone, Deserialize)]
pub struct DriverConfig {
    #[serde(default = "default_schema_version")]
    pub schema_version: String,
    #[serde(default)]
    pub description: Option<String>,
    pub driver_id: String,
    #[serde(default = "default_mode")]
    pub mode: String,
    /// Command timeout in milliseconds. Applied to all drivers that support it.
    /// A top-level driver field per the shared schema (matches Python/Go).
    #[serde(default)]
    pub command_timeout_ms: Option<u64>,
    #[serde(default)]
    pub tls: Option<HashMap<String, Value>>,
    #[serde(default)]
    pub auth: Option<HashMap<String, Value>>,
    #[serde(default)]
    pub specific_driver_config: HashMap<String, Value>,
}

fn default_schema_version() -> String {
    "1.0".to_string()
}

fn default_mode() -> String {
    "standalone".to_string()
}

impl DriverConfig {
    pub fn is_cluster(&self) -> bool {
        self.mode == "cluster"
    }

    pub fn tls_enabled(&self) -> bool {
        self.tls
            .as_ref()
            .and_then(|t| t.get("enabled"))
            .and_then(Value::as_bool)
            .unwrap_or(false)
    }

    /// Username/password from the `auth` block, if any is set.
    pub fn auth_credentials(&self) -> Option<(Option<String>, Option<String>)> {
        let auth = self.auth.as_ref()?;
        let username = auth
            .get("username")
            .and_then(Value::as_str)
            .map(String::from);
        let password = auth
            .get("password")
            .and_then(Value::as_str)
            .map(String::from);
        if username.is_none() && password.is_none() {
            None
        } else {
            Some((username, password))
        }
    }

    /// Secondary driver id for composite drivers (e.g. spring-data-*).
    pub fn secondary_driver_id(&self) -> Option<String> {
        self.specific_driver_config
            .get("secondary_driver_id")
            .and_then(Value::as_str)
            .map(String::from)
    }
}
