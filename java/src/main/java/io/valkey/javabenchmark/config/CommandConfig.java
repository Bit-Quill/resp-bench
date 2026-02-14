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
 * Configuration model for a benchmark command.
 *
 * @author Ilia Kolominsky
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommandConfig {

    @JsonProperty("command")
    private String command;

    @JsonProperty("weight")
    private double weight = 1.0;

    @JsonProperty("data_size_bytes")
    private Integer dataSizeBytes;

    @JsonProperty("settings")
    private Map<String, Object> settings;

    // Getters and Setters

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public Integer getDataSizeBytes() {
        return dataSizeBytes;
    }

    public void setDataSizeBytes(Integer dataSizeBytes) {
        this.dataSizeBytes = dataSizeBytes;
    }

    public Map<String, Object> getSettings() {
        return settings;
    }

    public void setSettings(Map<String, Object> settings) {
        this.settings = settings;
    }

    // Convenience methods

    /**
     * Get the command name in lowercase.
     * 
     * @return lowercase command name
     */
    public String getCommandLower() {
        return command != null ? command.toLowerCase() : null;
    }

    /**
     * Get the data size in bytes, with a default value.
     * 
     * @param defaultSize default size if not configured
     * @return data size in bytes
     */
    public int getDataSizeBytesOrDefault(int defaultSize) {
        return dataSizeBytes != null ? dataSizeBytes : defaultSize;
    }

    /**
     * Check if this is a SET command.
     * 
     * @return true if command is "set"
     */
    public boolean isSetCommand() {
        return "set".equalsIgnoreCase(command);
    }

    /**
     * Check if this is a GET command.
     * 
     * @return true if command is "get"
     */
    public boolean isGetCommand() {
        return "get".equalsIgnoreCase(command);
    }

    /**
     * Check if this is a PING command.
     * 
     * @return true if command is "ping"
     */
    public boolean isPingCommand() {
        return "ping".equalsIgnoreCase(command);
    }

    /**
     * Validate the command configuration.
     * 
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("Command name is required");
        }
        if (weight < 0 || weight > 1) {
            throw new IllegalArgumentException(
                    "Command weight must be between 0 and 1, got " + weight);
        }
        if (isSetCommand() && (dataSizeBytes == null || dataSizeBytes <= 0)) {
            throw new IllegalArgumentException(
                    "SET command requires positive data_size_bytes");
        }
    }

    @Override
    public String toString() {
        return "CommandConfig{" +
                "command='" + command + '\'' +
                ", weight=" + weight +
                (dataSizeBytes != null ? ", dataSizeBytes=" + dataSizeBytes : "") +
                '}';
    }
}