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

/**
 * Configuration model for phase completion criteria.
 *
 * @author Ilia Kolominsky
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompletionConfig {

    public static final String TYPE_DURATION = "duration";
    public static final String TYPE_REQUESTS = "requests";

    @JsonProperty("type")
    private String type;

    @JsonProperty("seconds")
    private Long seconds;

    @JsonProperty("requests")
    private Long requests;

    // Getters and Setters

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getSeconds() {
        return seconds;
    }

    public void setSeconds(Long seconds) {
        this.seconds = seconds;
    }

    public Long getRequests() {
        return requests;
    }

    public void setRequests(Long requests) {
        this.requests = requests;
    }

    // Convenience methods

    /**
     * Check if completion is duration-based.
     * 
     * @return true if type is "duration"
     */
    public boolean isDurationBased() {
        return TYPE_DURATION.equalsIgnoreCase(type);
    }

    /**
     * Check if completion is request-based.
     * 
     * @return true if type is "requests"
     */
    public boolean isRequestBased() {
        return TYPE_REQUESTS.equalsIgnoreCase(type);
    }

    /**
     * Get the duration in seconds.
     * 
     * @return duration in seconds, or 0 if not duration-based
     */
    public long getDurationSeconds() {
        return seconds != null ? seconds : 0L;
    }

    /**
     * Get the target request count.
     * 
     * @return request count, or 0 if not request-based
     */
    public long getTotalRequests() {
        return requests != null ? requests : 0L;
    }

    /**
     * Validate the completion configuration.
     * 
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Completion type is required");
        }
        if (isDurationBased()) {
            if (seconds == null || seconds <= 0) {
                throw new IllegalArgumentException(
                        "Duration-based completion requires positive seconds value");
            }
        } else if (isRequestBased()) {
            if (requests == null || requests <= 0) {
                throw new IllegalArgumentException(
                        "Request-based completion requires positive requests value");
            }
        } else {
            throw new IllegalArgumentException(
                    "Unknown completion type: " + type + 
                    ". Supported types: " + TYPE_DURATION + ", " + TYPE_REQUESTS);
        }
    }

    @Override
    public String toString() {
        if (isDurationBased()) {
            return "CompletionConfig{type='duration', seconds=" + seconds + "}";
        } else if (isRequestBased()) {
            return "CompletionConfig{type='requests', requests=" + requests + "}";
        }
        return "CompletionConfig{type='" + type + "'}";
    }
}