/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.client.impl;

import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.DriverConfig;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recording client for testing. Records all operations for verification.
 * Supports configurable latency simulation and error injection for comprehensive testing.
 * 
 * <p>Configuration via specific_driver_config in JSON:</p>
 * <pre>
 * {
 *     "driver_id": "recording",
 *     "mode": "standalone",
 *     "specific_driver_config": {
 *         "operation_delay_micros": 5000,
 *         "delay_variation_micros": 500,
 *         "error_rate": 0.1,
 *         "error_message": "Simulated error"
 *     }
 * }
 * </pre>
 * 
 * <p>For latency histogram testing with long-tail distribution:</p>
 * <pre>
 * {
 *     "driver_id": "recording",
 *     "mode": "standalone",
 *     "specific_driver_config": {
 *         "latency_distribution": "log_normal",
 *         "latency_min_ms": 100,
 *         "latency_median_ms": 150,
 *         "latency_p9999_target_ms": 900
 *     }
 * }
 * </pre>
 *
 * @author Ilia Kolominsky
 */
public class RecordingBenchmarkClient implements BenchmarkClient {

    // Static registry for test access to instances
    private static final CopyOnWriteArrayList<RecordingBenchmarkClient> instances = new CopyOnWriteArrayList<>();
    
    // Static aggregate data that survives instance close (for black-box testing)
    private static final ConcurrentLinkedQueue<RecordedOperation> aggregateOperations = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<RecordedOperation> operations = new ConcurrentLinkedQueue<>();
    private final AtomicLong setCount = new AtomicLong(0);
    private final AtomicLong getCount = new AtomicLong(0);
    private final AtomicLong pingCount = new AtomicLong(0);
    private final AtomicLong delCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, byte[]> data = new ConcurrentHashMap<>();
    private volatile boolean connected = false;
    private long connectTime;

    // Delay simulation settings (fixed mode)
    private volatile long operationDelayMicros = 0;
    private volatile long delayVariationMicros = 0;
    
    // Log-normal latency distribution settings
    private volatile String latencyDistribution = "fixed"; // "fixed" or "log_normal"
    private volatile long latencyMinMs = 0;
    private volatile double logNormalMu = 0.0;
    private volatile double logNormalSigma = 0.0;
    private final Random random = new Random();
    
    // Error simulation settings
    private volatile double errorRate = 0.0;
    private volatile String errorMessage = "Simulated error";
    
    // Warmup mode — suppresses error simulation during connectivity checks
    private volatile boolean warmupMode = false;
    
    // Memory ballast for testing — holds allocated bytes to prevent GC
    private volatile byte[] memoryBallast = null;
    
    // Executor for async delay simulation (CPU count threads for scheduling)
    private final ScheduledExecutorService delayExecutor = 
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

    @Override
    public String getDriverId() { return "recording"; }

    @Override
    public String getDescription() { return "Recording client for testing"; }

    @Override
    public String getDriverVersion() { return "1.0.0"; }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) {
        this.connectTime = System.currentTimeMillis();
        this.connected = true;
        
        // Read configuration from specific_driver_config
        if (driverConfig != null && driverConfig.getSpecificDriverConfig() != null) {
            Map<String, Object> config = driverConfig.getSpecificDriverConfig();
            
            // Read operation delay (fixed mode)
            if (config.containsKey("operation_delay_micros")) {
                Object value = config.get("operation_delay_micros");
                this.operationDelayMicros = value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
            }
            
            // Read delay variation (fixed mode)
            if (config.containsKey("delay_variation_micros")) {
                Object value = config.get("delay_variation_micros");
                this.delayVariationMicros = value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
            }
            
            // Read error rate
            if (config.containsKey("error_rate")) {
                Object value = config.get("error_rate");
                this.errorRate = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
            }
            
            // Read error message
            if (config.containsKey("error_message")) {
                this.errorMessage = config.get("error_message").toString();
            }
            
            // Read latency distribution settings
            if (config.containsKey("latency_distribution")) {
                this.latencyDistribution = config.get("latency_distribution").toString();
            }
            
            if ("log_normal".equals(this.latencyDistribution)) {
                // Read log-normal parameters
                if (config.containsKey("latency_min_ms")) {
                    Object value = config.get("latency_min_ms");
                    this.latencyMinMs = value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
                }
                
                long medianMs = 150; // default
                if (config.containsKey("latency_median_ms")) {
                    Object value = config.get("latency_median_ms");
                    medianMs = value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
                }
                
                long p9999TargetMs = 900; // default
                if (config.containsKey("latency_p9999_target_ms")) {
                    Object value = config.get("latency_p9999_target_ms");
                    p9999TargetMs = value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
                }
                
                // Calculate log-normal parameters from median and p9999 target
                // For log-normal: median = exp(mu), so mu = ln(median)
                // p9999 corresponds to z-score ≈ 3.72, so: ln(p9999) = mu + 3.72 * sigma
                this.logNormalMu = Math.log(medianMs);
                this.logNormalSigma = (Math.log(p9999TargetMs) - this.logNormalMu) / 3.72;
                
                // Ensure sigma is positive
                if (this.logNormalSigma <= 0) {
                    this.logNormalSigma = 0.5; // fallback
                }
            }
        }
        
        operations.add(new RecordedOperation("CONNECT", System.currentTimeMillis(), null, null, true, null));
        
        // Register this instance for test access
        instances.add(this);
    }

    @Override
    public void setWarmupMode(boolean warmup) {
        this.warmupMode = warmup;
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public CompletableFuture<TimedResult<Void>> set(byte[] key, byte[] value) {
        setCount.incrementAndGet();
        long startTime = System.nanoTime();
        
        return simulateDelay().thenApply(v -> {
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            boolean success = !shouldSimulateError();
            String error = success ? null : errorMessage;
            
            String keyStr = new String(key);
            if (success) {
                data.put(keyStr, value);
            }
            RecordedOperation op = new RecordedOperation("SET", System.currentTimeMillis(), key, value, success, error);
            operations.add(op);
            aggregateOperations.add(op);
            
            if (!success) {
                throw new RuntimeException(errorMessage);
            }
            return TimedResult.ofVoid(latencyMicros);
        });
    }

    @Override
    public CompletableFuture<TimedResult<byte[]>> get(byte[] key) {
        getCount.incrementAndGet();
        long startTime = System.nanoTime();
        
        return simulateDelay().thenApply(v -> {
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            boolean success = !shouldSimulateError();
            String error = success ? null : errorMessage;
            
            String keyStr = new String(key);
            RecordedOperation op = new RecordedOperation("GET", System.currentTimeMillis(), key, null, success, error);
            operations.add(op);
            aggregateOperations.add(op);
            
            if (!success) {
                throw new RuntimeException(errorMessage);
            }
            return TimedResult.of(data.get(keyStr), latencyMicros);
        });
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping() {
        pingCount.incrementAndGet();
        long startTime = System.nanoTime();
        
        return simulateDelay().thenApply(v -> {
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            boolean success = !shouldSimulateError();
            String error = success ? null : errorMessage;
            
            RecordedOperation op = new RecordedOperation("PING", System.currentTimeMillis(), null, null, success, error);
            operations.add(op);
            aggregateOperations.add(op);
            
            if (!success) {
                throw new RuntimeException(errorMessage);
            }
            return TimedResult.of("PONG", latencyMicros);
        });
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        return ping();
    }

    @Override
    public CompletableFuture<TimedResult<Long>> del(byte[]... keys) {
        delCount.incrementAndGet();
        long startTime = System.nanoTime();
        
        return simulateDelay().thenApply(v -> {
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            boolean success = !shouldSimulateError();
            String error = success ? null : errorMessage;
            
            long count = 0;
            if (success) {
                for (byte[] key : keys) {
                    if (data.remove(new String(key)) != null) count++;
                }
            }
            
            for (byte[] key : keys) {
                RecordedOperation op = new RecordedOperation("DEL", System.currentTimeMillis(), key, null, success, error);
                operations.add(op);
                aggregateOperations.add(op);
            }
            
            if (!success) {
                throw new RuntimeException(errorMessage);
            }
            return TimedResult.of(count, latencyMicros);
        });
    }

    @Override
    public CompletableFuture<TimedResult<Void>> flushDb() {
        long startTime = System.nanoTime();
        return simulateDelay().thenApply(v -> {
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            data.clear();
            RecordedOperation op = new RecordedOperation("FLUSHDB", System.currentTimeMillis(), null, null, true, null);
            operations.add(op);
            aggregateOperations.add(op);
            return TimedResult.ofVoid(latencyMicros);
        });
    }

    @Override
    public void close() {
        connected = false;
        operations.add(new RecordedOperation("CLOSE", System.currentTimeMillis(), null, null, true, null));
        delayExecutor.shutdown();
        
        // Unregister this instance
        instances.remove(this);
    }

    // === Static registry methods for test access ===

    /**
     * Get all active RecordingBenchmarkClient instances.
     * Useful for tests to inspect recorded data after engine completes.
     * 
     * @return list of active instances
     */
    public static List<RecordingBenchmarkClient> getInstances() {
        return List.copyOf(instances);
    }

    /**
     * Get the most recently created instance.
     * 
     * @return the last instance or null if none
     */
    public static RecordingBenchmarkClient getLastInstance() {
        if (instances.isEmpty()) return null;
        return instances.get(instances.size() - 1);
    }

    /**
     * Clear all registered instances and aggregate operations.
     * Should be called in test setup/teardown.
     */
    public static void clearInstances() {
        instances.clear();
        aggregateOperations.clear();
    }

    // === Static aggregate methods for black-box testing ===

    /**
     * Get all aggregate operations from all instances (survives instance close).
     * This is useful for black-box testing where instances are closed before validation.
     * 
     * @return list of all recorded operations
     */
    public static List<RecordedOperation> getAggregateOperations() {
        return List.copyOf(aggregateOperations);
    }

    /**
     * Get aggregate operations filtered by command type.
     * 
     * @param command command type (SET, GET, PING, DEL, FLUSHDB)
     * @return list of matching operations
     */
    public static List<RecordedOperation> getAggregateOperations(String command) {
        return aggregateOperations.stream()
                .filter(op -> op.command().equals(command))
                .toList();
    }

    /**
     * Get all aggregate SET operations with their values.
     * 
     * @return list of SET operations with values
     */
    public static List<RecordedOperation> getAggregateSetOperationsWithValues() {
        return aggregateOperations.stream()
                .filter(op -> "SET".equals(op.command()) && op.value() != null)
                .toList();
    }

    /**
     * Get all unique keys from aggregate operations.
     * 
     * @return list of unique key strings
     */
    public static List<String> getAggregateUniqueKeys() {
        return aggregateOperations.stream()
                .filter(op -> op.key() != null)
                .map(op -> new String(op.key()))
                .distinct()
                .toList();
    }

    // === Delay simulation methods ===

    /**
     * Set the base operation delay in microseconds.
     * @param delayMicros delay in microseconds (0 to disable)
     */
    public void setOperationDelayMicros(long delayMicros) {
        this.operationDelayMicros = delayMicros;
    }

    /**
     * Get the configured operation delay.
     * @return delay in microseconds
     */
    public long getOperationDelayMicros() {
        return operationDelayMicros;
    }

    /**
     * Set the delay variation range (+/- this value).
     * Actual delay will be: operationDelayMicros +/- delayVariationMicros
     * @param variationMicros variation in microseconds
     */
    public void setDelayVariationMicros(long variationMicros) {
        this.delayVariationMicros = variationMicros;
    }

    /**
     * Get the configured delay variation.
     * @return variation in microseconds
     */
    public long getDelayVariationMicros() {
        return delayVariationMicros;
    }

    /**
     * Simulate delay using CompletableFuture.
     */
    private CompletableFuture<Void> simulateDelay() {
        long delayMs = calculateDelayMs();
        
        if (delayMs <= 0) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        delayExecutor.schedule(() -> future.complete(null), delayMs, TimeUnit.MILLISECONDS);
        return future;
    }
    
    /**
     * Calculate delay based on configured distribution.
     * @return delay in milliseconds
     */
    private long calculateDelayMs() {
        if ("log_normal".equals(latencyDistribution)) {
            // Generate log-normal sample: exp(mu + sigma * Z) where Z is standard normal
            double z = random.nextGaussian();
            double sampleMs = Math.exp(logNormalMu + logNormalSigma * z);
            // Ensure minimum latency for OS sleep safety
            return Math.max(latencyMinMs, (long) sampleMs);
        } else {
            // Fixed delay mode (original behavior)
            if (operationDelayMicros <= 0) {
                return 0;
            }
            
            long actualDelayMicros = operationDelayMicros;
            if (delayVariationMicros > 0) {
                long variation = ThreadLocalRandom.current().nextLong(-delayVariationMicros, delayVariationMicros + 1);
                actualDelayMicros = Math.max(0, operationDelayMicros + variation);
            }
            return actualDelayMicros / 1000; // Convert micros to millis
        }
    }

    // === Error simulation methods ===

    /**
     * Set the error rate (0.0 to 1.0).
     * @param rate probability of error (0.0 = no errors, 1.0 = always error)
     */
    public void setErrorRate(double rate) {
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("Error rate must be between 0.0 and 1.0");
        }
        this.errorRate = rate;
    }

    /**
     * Get the configured error rate.
     * @return error rate (0.0 to 1.0)
     */
    public double getErrorRate() {
        return errorRate;
    }

    /**
     * Set the error message for simulated errors.
     * @param message error message
     */
    public void setErrorMessage(String message) {
        this.errorMessage = message;
    }

    /**
     * Get the configured error message.
     * @return error message
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Check if this operation should simulate an error.
     * Returns false during warmup mode to allow connectivity checks to pass.
     */
    private boolean shouldSimulateError() {
        if (warmupMode) return false;
        if (errorRate <= 0.0) return false;
        if (errorRate >= 1.0) return true;
        return ThreadLocalRandom.current().nextDouble() < errorRate;
    }

    // === Test verification methods ===

    public long getSetCount() { return setCount.get(); }
    public long getGetCount() { return getCount.get(); }
    public long getPingCount() { return pingCount.get(); }
    public long getDelCount() { return delCount.get(); }
    public long getTotalOperations() { return setCount.get() + getCount.get() + pingCount.get() + delCount.get(); }
    
    public List<RecordedOperation> getOperations() { return List.copyOf(operations); }
    
    public List<RecordedOperation> getOperations(String command) {
        return operations.stream().filter(op -> op.command().equals(command)).toList();
    }

    /**
     * Get all operations that were successful.
     */
    public List<RecordedOperation> getSuccessfulOperations() {
        return operations.stream().filter(RecordedOperation::success).toList();
    }

    /**
     * Get all operations that failed.
     */
    public List<RecordedOperation> getFailedOperations() {
        return operations.stream().filter(op -> !op.success()).toList();
    }

    /**
     * Get all unique keys that were accessed.
     */
    public List<String> getUniqueKeys() {
        return operations.stream()
                .filter(op -> op.key() != null)
                .map(op -> new String(op.key()))
                .distinct()
                .toList();
    }

    /**
     * Get all SET operations with their values.
     */
    public List<RecordedOperation> getSetOperationsWithValues() {
        return operations.stream()
                .filter(op -> "SET".equals(op.command()) && op.value() != null)
                .toList();
    }

    /**
     * Get the stored data map (for inspection).
     */
    public ConcurrentHashMap<String, byte[]> getStoredData() {
        return data;
    }

    /**
     * Get connect time.
     */
    public long getConnectTime() {
        return connectTime;
    }

    public void reset() {
        operations.clear();
        setCount.set(0);
        getCount.set(0);
        pingCount.set(0);
        delCount.set(0);
        data.clear();
        operationDelayMicros = 0;
        delayVariationMicros = 0;
        latencyDistribution = "fixed";
        latencyMinMs = 0;
        logNormalMu = 0.0;
        logNormalSigma = 0.0;
        errorRate = 0.0;
        errorMessage = "Simulated error";
    }
    
    // === Getters for latency distribution settings (for testing) ===
    
    public String getLatencyDistribution() {
        return latencyDistribution;
    }
    
    public long getLatencyMinMs() {
        return latencyMinMs;
    }
    
    public double getLogNormalMu() {
        return logNormalMu;
    }
    
    public double getLogNormalSigma() {
        return logNormalSigma;
    }

    /**
     * Recorded operation including success/failure status.
     */
    public record RecordedOperation(
            String command, 
            long timestamp, 
            byte[] key, 
            byte[] value,
            boolean success,
            String errorMessage
    ) {
        /**
         * Legacy constructor for backward compatibility.
         */
        public RecordedOperation(String command, long timestamp, byte[] key, byte[] value) {
            this(command, timestamp, key, value, true, null);
        }
    }
}