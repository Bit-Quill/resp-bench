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
package io.valkey.javabenchmark.client;

import io.valkey.javabenchmark.client.impl.*;
import io.valkey.javabenchmark.config.DriverConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

/**
 * Factory for creating BenchmarkClient instances.
 * 
 * <p>This factory supports registration of custom client implementations,
 * making it easy to add new client libraries.</p>
 * 
 * <p>Built-in supported drivers:</p>
 * <ul>
 *   <li>jedis - Jedis synchronous client</li>
 *   <li>lettuce - Lettuce async/reactive client</li>
 *   <li>valkey-glide - Valkey GLIDE client</li>
 *   <li>redisson - Redisson async client</li>
 *   <li>spring-data-valkey - Spring Data Valkey</li>
 *   <li>spring-data-redis - Spring Data Redis</li>
 * </ul>
 * @author Ilia Kolominsky 
 */
public class BenchmarkClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(BenchmarkClientFactory.class);

    private static final Map<String, DriverInfo> driverRegistry = new LinkedHashMap<>();

    static {
        // Register built-in client implementations
        registerDriver("jedis", 
                "Jedis Redis/Valkey client",
                JedisBenchmarkClient::new);
        registerDriver("lettuce", 
                "Lettuce async Redis/Valkey client",
                LettuceBenchmarkClient::new);
        registerDriver("valkey-glide", 
                "Valkey GLIDE high-performance client",
                ValkeyGlideBenchmarkClient::new);
        registerDriver("spring-data-valkey", 
                "Spring Data Valkey (requires secondary_driver_id)",
                SpringDataValkeyBenchmarkClient::new);
        registerDriver("spring-data-redis", 
                "Spring Data Redis (requires secondary_driver_id)",
                SpringDataRedisBenchmarkClient::new);
        registerDriver("redisson",
                "Redisson async Redis/Valkey client",
                RedissonBenchmarkClient::new);
        registerDriver("recording",
                "Recording client for testing with configurable latency and error simulation",
                RecordingBenchmarkClient::new);
    }

    /**
     * Register a custom client implementation.
     * 
     * @param driverId the driver identifier (e.g., "custom-client")
     * @param description driver description
     * @param supplier factory function to create client instances
     */
    public static void registerDriver(String driverId, String description, Supplier<BenchmarkClient> supplier) {
        driverRegistry.put(driverId.toLowerCase(), new DriverInfo(driverId, description, supplier));
        logger.debug("Registered driver: {}", driverId);
    }

    /**
     * Check if a driver is supported.
     * 
     * @param driverId the driver identifier
     * @return true if the driver is registered
     */
    public static boolean isSupported(String driverId) {
        return driverRegistry.containsKey(driverId.toLowerCase());
    }

    /**
     * Create a new BenchmarkClient instance.
     * 
     * @param driverConfig the driver configuration
     * @return a new unconnected client instance
     * @throws IllegalArgumentException if the driver is not supported
     */
    public static BenchmarkClient create(DriverConfig driverConfig) {
        String driverId = driverConfig.getDriverId().toLowerCase();
        
        DriverInfo driverInfo = driverRegistry.get(driverId);
        if (driverInfo == null) {
            throw new IllegalArgumentException("Unsupported driver: " + driverConfig.getDriverId() +
                    ". Supported: " + driverRegistry.keySet());
        }
        
        logger.info("Creating client for driver: {}", driverId);
        return driverInfo.supplier().get();
    }

    /**
     * Create and connect a BenchmarkClient.
     * 
     * @param host server host
     * @param port server port
     * @param driverConfig the driver configuration
     * @return a connected client instance
     * @throws BenchmarkClient.ClientException if connection fails
     */
    public static BenchmarkClient createAndConnect(
            String host,
            int port,
            DriverConfig driverConfig) throws BenchmarkClient.ClientException {
        
        BenchmarkClient client = create(driverConfig);
        try {
            client.connect(host, port, driverConfig);
            return client;
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    /**
     * Get all registered driver IDs.
     * 
     * @return collection of driver IDs
     */
    public static Collection<String> getRegisteredDriverIds() {
        return Collections.unmodifiableSet(driverRegistry.keySet());
    }

    /**
     * Get information about all registered drivers.
     * 
     * @return list of driver info
     */
    public static List<DriverInfo> getRegisteredDrivers() {
        return new ArrayList<>(driverRegistry.values());
    }

    /**
     * Get description for a driver.
     * 
     * @param driverId the driver ID
     * @return description or null if not found
     */
    public static String getDriverDescription(String driverId) {
        DriverInfo info = driverRegistry.get(driverId.toLowerCase());
        return info != null ? info.description() : null;
    }

    /**
     * Information about a registered driver.
     */
    public record DriverInfo(String driverId, String description, Supplier<BenchmarkClient> supplier) {
    }
}