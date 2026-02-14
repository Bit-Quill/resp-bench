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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for loading configuration files.
 *
 * @author Ilia Kolominsky
 */
public class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Load driver configuration from a JSON file.
     * 
     * @param path the path to the JSON file
     * @return the parsed driver configuration
     * @throws ConfigurationException if loading or parsing fails
     */
    public static DriverConfig loadDriverConfig(String path) throws ConfigurationException {
        return loadDriverConfig(Path.of(path));
    }

    /**
     * Load driver configuration from a JSON file.
     * 
     * @param path the path to the JSON file
     * @return the parsed driver configuration
     * @throws ConfigurationException if loading or parsing fails
     */
    public static DriverConfig loadDriverConfig(Path path) throws ConfigurationException {
        logger.info("Loading driver configuration from: {}", path);
        
        try {
            String content = Files.readString(path);
            DriverConfig config = objectMapper.readValue(content, DriverConfig.class);
            
            // Validate required fields
            if (config.getDriverId() == null || config.getDriverId().trim().isEmpty()) {
                throw new ConfigurationException("Driver configuration must have a driver_id");
            }
            if (config.getMode() == null || config.getMode().trim().isEmpty()) {
                throw new ConfigurationException("Driver configuration must have a mode");
            }
            
            logger.info("Loaded driver configuration: {}", config);
            return config;
            
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load driver configuration from " + path, e);
        }
    }

    /**
     * Load workload configuration from a JSON file.
     * 
     * @param path the path to the JSON file
     * @return the parsed workload configuration
     * @throws ConfigurationException if loading or parsing fails
     */
    public static WorkloadConfig loadWorkloadConfig(String path) throws ConfigurationException {
        return loadWorkloadConfig(Path.of(path));
    }

    /**
     * Load workload configuration from a JSON file.
     * 
     * @param path the path to the JSON file
     * @return the parsed workload configuration
     * @throws ConfigurationException if loading or parsing fails
     */
    public static WorkloadConfig loadWorkloadConfig(Path path) throws ConfigurationException {
        logger.info("Loading workload configuration from: {}", path);
        
        try {
            String content = Files.readString(path);
            WorkloadConfig config = objectMapper.readValue(content, WorkloadConfig.class);
            
            // Validate the configuration
            validateWorkloadConfig(config);
            
            logger.info("Loaded workload configuration: {}", config);
            return config;
            
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load workload configuration from " + path, e);
        }
    }

    /**
     * Validate the workload configuration.
     * 
     * @param config the workload configuration to validate
     * @throws ConfigurationException if validation fails
     */
    private static void validateWorkloadConfig(WorkloadConfig config) throws ConfigurationException {
        if (config.getPhases() == null || config.getPhases().isEmpty()) {
            throw new ConfigurationException("Workload must have at least one phase");
        }
        
        for (int i = 0; i < config.getPhases().size(); i++) {
            PhaseConfig phase = config.getPhases().get(i);
            try {
                phase.validate();
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException(
                        "Invalid configuration in phase " + (i + 1) + 
                        " (" + phase.getId() + "): " + e.getMessage(), e);
            }
        }
    }

    /**
     * Load any configuration from a JSON file.
     * 
     * @param path the path to the JSON file
     * @param clazz the class to deserialize to
     * @param <T> the configuration type
     * @return the parsed configuration
     * @throws ConfigurationException if loading or parsing fails
     */
    public static <T> T loadConfig(Path path, Class<T> clazz) throws ConfigurationException {
        logger.debug("Loading configuration from: {} as {}", path, clazz.getSimpleName());
        
        try {
            String content = Files.readString(path);
            return objectMapper.readValue(content, clazz);
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load configuration from " + path, e);
        }
    }

    /**
     * Parse a driver configuration from JSON string.
     * 
     * @param json the JSON string
     * @return the parsed driver configuration
     * @throws ConfigurationException if parsing or validation fails
     */
    public static DriverConfig parseDriverConfig(String json) throws ConfigurationException {
        try {
            DriverConfig config = objectMapper.readValue(json, DriverConfig.class);
            
            // Validate required fields
            if (config.getDriverId() == null || config.getDriverId().trim().isEmpty()) {
                throw new ConfigurationException("Driver configuration must have a driver_id");
            }
            
            return config;
        } catch (IOException e) {
            throw new ConfigurationException("Failed to parse driver configuration", e);
        }
    }

    /**
     * Parse a workload configuration from JSON string.
     * 
     * @param json the JSON string
     * @return the parsed workload configuration
     * @throws ConfigurationException if parsing fails
     */
    public static WorkloadConfig parseWorkloadConfig(String json) throws ConfigurationException {
        try {
            WorkloadConfig config = objectMapper.readValue(json, WorkloadConfig.class);
            validateWorkloadConfig(config);
            return config;
        } catch (IOException e) {
            throw new ConfigurationException("Failed to parse workload configuration", e);
        }
    }

    /**
     * Get the shared ObjectMapper instance.
     * 
     * @return the ObjectMapper
     */
    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Exception thrown when configuration loading or validation fails.
     */
    public static class ConfigurationException extends Exception {
        
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}