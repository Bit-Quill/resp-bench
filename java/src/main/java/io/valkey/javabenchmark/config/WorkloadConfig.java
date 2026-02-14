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

import java.util.List;

/**
 * Configuration model for workload settings.
 * Maps to the workload JSON configuration files.
 *
 * @author Ilia Kolominsky
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkloadConfig {

    @JsonProperty("schema_version")
    private String schemaVersion;

    @JsonProperty("benchmark_profile")
    private BenchmarkProfile benchmarkProfile;

    @JsonProperty("phases")
    private List<PhaseConfig> phases;

    // Getters and Setters

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public BenchmarkProfile getBenchmarkProfile() {
        return benchmarkProfile;
    }

    public void setBenchmarkProfile(BenchmarkProfile benchmarkProfile) {
        this.benchmarkProfile = benchmarkProfile;
    }

    public List<PhaseConfig> getPhases() {
        return phases;
    }

    public void setPhases(List<PhaseConfig> phases) {
        this.phases = phases;
    }

    @Override
    public String toString() {
        return "WorkloadConfig{" +
                "schemaVersion='" + schemaVersion + '\'' +
                ", benchmarkProfile=" + benchmarkProfile +
                ", phasesCount=" + (phases != null ? phases.size() : 0) +
                '}';
    }

    /**
     * Benchmark profile metadata.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BenchmarkProfile {

        @JsonProperty("name")
        private String name;

        @JsonProperty("description")
        private String description;

        @JsonProperty("version")
        private String version;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        @Override
        public String toString() {
            return "BenchmarkProfile{" +
                    "name='" + name + '\'' +
                    ", version='" + version + '\'' +
                    '}';
        }
    }
}