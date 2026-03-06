/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.valkey.javabenchmark.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Configuration model for driver settings.
 * Maps to the driver JSON configuration files.
 *
 * @author Ilia Kolominsky
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DriverConfig {

    @JsonProperty("schema_version")
    private String schemaVersion;

    @JsonProperty("description")
    private String description;

    @JsonProperty("driver_id")
    private String driverId;

    @JsonProperty("mode")
    private String mode;

    @JsonProperty("auth")
    private AuthConfig auth;

    @JsonProperty("tls")
    private TlsConfig tls;

    @JsonProperty("specific_driver_config")
    private Map<String, Object> specificDriverConfig;

    // Getters and Setters

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDriverId() {
        return driverId;
    }

    public void setDriverId(String driverId) {
        this.driverId = driverId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth;
    }

    public TlsConfig getTls() {
        return tls;
    }

    public void setTls(TlsConfig tls) {
        this.tls = tls;
    }

    public Map<String, Object> getSpecificDriverConfig() {
        return specificDriverConfig;
    }

    public void setSpecificDriverConfig(Map<String, Object> specificDriverConfig) {
        this.specificDriverConfig = specificDriverConfig;
    }

    // Convenience methods

    /**
     * Check if this is a cluster mode configuration.
     * 
     * @return true if mode is "cluster"
     */
    public boolean isClusterMode() {
        return "cluster".equalsIgnoreCase(mode);
    }

    /**
     * Check if TLS is enabled.
     * 
     * @return true if tls config is present
     */
    public boolean isTlsEnabled() {
        return tls != null;
    }

    /**
     * Check if authentication is configured.
     * 
     * @return true if auth config is present with password
     */
    public boolean hasAuth() {
        return auth != null && auth.getPassword() != null && !auth.getPassword().isEmpty();
    }

    /**
     * Check if connection pooling is enabled in specific_driver_config.
     * Used by Spring Data drivers with Jedis to enable JedisPool (matching Spring Boot defaults).
     * 
     * @return true if use_pooling is set to true in specific_driver_config
     */
    public boolean isUsePooling() {
        if (specificDriverConfig != null) {
            Object value = specificDriverConfig.get("use_pooling");
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof String) return Boolean.parseBoolean((String) value);
        }
        return false;
    }

    /**
     * Get the connection pool size from specific_driver_config.
     * Used by Spring Data drivers (Jedis pool maxTotal, ValkeyGlide adapter pool maxPoolSize).
     * Defaults to 8, matching Spring Boot / Spring Data defaults.
     * 
     * @return pool size (default 8)
     */
    public int getPoolSize() {
        if (specificDriverConfig != null) {
            Object value = specificDriverConfig.get("pool_size");
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) {
                try { return Integer.parseInt((String) value); } catch (NumberFormatException ignored) {}
            }
        }
        return 8; // Spring Boot default
    }

    /**
     * Get the secondary driver ID for spring-data-* drivers.
     * 
     * @return secondary driver ID or null
     */
    public String getSecondaryDriverId() {
        if (specificDriverConfig != null) {
            Object secondaryId = specificDriverConfig.get("secondary_driver_id");
            // Also check for the typo version in the config
            if (secondaryId == null) {
                secondaryId = specificDriverConfig.get("scondary_driver_id");
            }
            return secondaryId != null ? secondaryId.toString() : null;
        }
        return null;
    }

    /**
     * Get the secondary driver configuration.
     * 
     * @return secondary driver config map or null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSecondaryDriverConfig() {
        if (specificDriverConfig != null) {
            Object config = specificDriverConfig.get("secondary_driver_config");
            if (config instanceof Map) {
                return (Map<String, Object>) config;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "DriverConfig{" +
                "driverId='" + driverId + '\'' +
                ", mode='" + mode + '\'' +
                ", tlsEnabled=" + isTlsEnabled() +
                ", hasAuth=" + hasAuth() +
                '}';
    }

    /**
     * Authentication configuration.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthConfig {

        @JsonProperty("username")
        private String username;

        @JsonProperty("password")
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * TLS configuration.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TlsConfig {

        @JsonProperty("cert_path")
        private String certPath;

        @JsonProperty("key_path")
        private String keyPath;

        @JsonProperty("ca_cert_path")
        private String caCertPath;

        @JsonProperty("verify_peer")
        private Boolean verifyPeer;

        public String getCertPath() {
            return certPath;
        }

        public void setCertPath(String certPath) {
            this.certPath = certPath;
        }

        public String getKeyPath() {
            return keyPath;
        }

        public void setKeyPath(String keyPath) {
            this.keyPath = keyPath;
        }

        public String getCaCertPath() {
            return caCertPath;
        }

        public void setCaCertPath(String caCertPath) {
            this.caCertPath = caCertPath;
        }

        public Boolean getVerifyPeer() {
            return verifyPeer;
        }

        public void setVerifyPeer(Boolean verifyPeer) {
            this.verifyPeer = verifyPeer;
        }
    }
}