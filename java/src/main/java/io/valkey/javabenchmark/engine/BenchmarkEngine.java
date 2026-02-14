/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.engine;

import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.BenchmarkClientFactory;
import io.valkey.javabenchmark.command.Command;
import io.valkey.javabenchmark.command.CommandFactory;
import io.valkey.javabenchmark.config.*;
import io.valkey.javabenchmark.metrics.NdjsonMetricsWriter;
import io.valkey.javabenchmark.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main benchmark engine that orchestrates the benchmark execution.
 * 
 * <p>Uses a backpressure-aware design where each client connection tracks
 * its in-flight requests. New requests are only submitted when a client
 * has available capacity (controlled by pipeline depth).</p>
 * 
 * <p>This design ensures:</p>
 * <ul>
 *   <li>Bounded memory usage - max in-flight requests = connections × pipeline_depth</li>
 *   <li>Accurate latency measurement - no queue backlog inflation</li>
 *   <li>Natural backpressure - new requests wait for available slots</li>
 *   <li>Correct behavior for both duration-based and request-based workloads</li>
 * </ul>
 *
 * @author Ilia Kolominsky
 */
public class BenchmarkEngine {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkEngine.class);
    
    /** Default pipeline depth (max in-flight requests per client) */
    private static final int DEFAULT_PIPELINE_DEPTH = 1;

    private final String host;
    private final int port;
    private final DriverConfig driverConfig;
    private final WorkloadConfig workloadConfig;
    private final NdjsonMetricsWriter metricsWriter;

    public BenchmarkEngine(String host, int port, DriverConfig driverConfig,
                          WorkloadConfig workloadConfig, String metricsPath) {
        this.host = host;
        this.port = port;
        this.driverConfig = driverConfig;
        this.workloadConfig = workloadConfig;
        this.metricsWriter = new NdjsonMetricsWriter(metricsPath);
    }

    public void run() throws Exception {
        logger.info("Starting benchmark: {}", workloadConfig.getBenchmarkProfile().getName());
        logger.info("Driver: {}, Mode: {}", driverConfig.getDriverId(), driverConfig.getMode());
        logger.info("Server: {}:{}", host, port);

        for (PhaseConfig phase : workloadConfig.getPhases()) {
            executePhase(phase);
        }

        logger.info("Benchmark completed");
    }

    private void executePhase(PhaseConfig phase) throws Exception {
        logger.info("=== Starting phase: {} ({}) ===", phase.getId(), phase.getDescription());

        // Get pipeline depth from phase config (default 1 for accurate latency)
        int pipelineDepth = phase.getPipelineDepth() > 0 ? phase.getPipelineDepth() : DEFAULT_PIPELINE_DEPTH;
        
        // Create client slots with backpressure control
        List<ClientSlot> clientSlots = createClientSlots(phase, pipelineDepth);
        
        // Create commands
        List<Command> commands = CommandFactory.createAll(phase.getCommands());
        
        // Create key generator
        KeyGenerator keyGenerator = KeyGenerator.create(phase.getKeyspace());
        
        // Create rate limiter
        RateLimiter rateLimiter = phase.hasRpsLimit() ? 
                RateLimiter.create(phase.getRpsLimit()) : null;
        
        // Create metrics collector
        MetricsCollector metrics = new MetricsCollector();

        // Execute workload with backpressure (including warmup)
        String status = executeWorkload(phase, clientSlots, commands, keyGenerator, rateLimiter, metrics);

        // Write metrics
        metricsWriter.writePhaseResults(phase.getId(), status, phase.getConnections(), metrics);

        // Close clients
        closeClientSlots(clientSlots);

        logger.info("=== Phase {} completed: {} ===", phase.getId(), status);
    }

    private List<ClientSlot> createClientSlots(PhaseConfig phase, int pipelineDepth) throws Exception {
        logger.info("Creating {} connections (pipeline depth: {})...", phase.getConnections(), pipelineDepth);
        
        List<ClientSlot> slots = new ArrayList<>();
        RateLimiter cpsLimiter = phase.hasCpsLimit() ? 
                RateLimiter.create(phase.getCpsLimit()) : null;

        for (int i = 0; i < phase.getConnections(); i++) {
            if (cpsLimiter != null) {
                cpsLimiter.acquire();
            }
            
            BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, driverConfig);
            slots.add(new ClientSlot(client, pipelineDepth));
            
            if ((i + 1) % 50 == 0) {
                logger.info("Created {}/{} connections", i + 1, phase.getConnections());
            }
        }
        
        logger.info("All {} connections established", slots.size());
        return slots;
    }

    private String executeWorkload(PhaseConfig phase, List<ClientSlot> clientSlots,
                                   List<Command> commands, KeyGenerator keyGenerator,
                                   RateLimiter rateLimiter, MetricsCollector metrics) {
        
        CompletionConfig completion = phase.getCompletion();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong requestCount = new AtomicLong(0);
        AtomicLong pendingCount = new AtomicLong(0);
        
        // Command selector based on weights
        CommandSelector selector = new CommandSelector(commands);
        
        // Create a semaphore representing total available slots across all clients
        int totalSlots = clientSlots.stream().mapToInt(s -> s.pipelineDepth).sum();
        Semaphore globalSlots = new Semaphore(totalSlots);
        
        // Submit warmup requests BEFORE starting metrics - they occupy slots
        // and will naturally pace the measured workload
        int warmupRequests = phase.getWarmupRequests();
        if (warmupRequests > 0) {
            submitWarmup(clientSlots, warmupRequests, globalSlots);
        }
        
        metrics.start();
        
        try {
            if (completion.isDurationBased()) {
                // Duration-based completion
                long endTime = System.currentTimeMillis() + (completion.getDurationSeconds() * 1000);
                
                while (System.currentTimeMillis() < endTime && running.get()) {
                    submitRequestWithBackpressure(clientSlots, selector, keyGenerator, rateLimiter, 
                                                   metrics, requestCount, pendingCount, globalSlots);
                }
                
            } else {
                // Request-based completion
                long targetRequests = completion.getTotalRequests();
                
                while (requestCount.get() < targetRequests && running.get()) {
                    submitRequestWithBackpressure(clientSlots, selector, keyGenerator, rateLimiter, 
                                                   metrics, requestCount, pendingCount, globalSlots);
                }
            }
            
            // Wait for remaining in-flight requests to complete
            long remaining = pendingCount.get();
            if (remaining > 0) {
                logger.info("Waiting for {} pending operations to complete...", remaining);
                // Wait by acquiring all slots (they'll be released as requests complete)
                long waitStart = System.currentTimeMillis();
                long maxWaitMs = 60_000; // 60 second timeout
                
                while (pendingCount.get() > 0 && (System.currentTimeMillis() - waitStart) < maxWaitMs) {
                    Thread.sleep(10);
                }
                
                if (pendingCount.get() > 0) {
                    logger.warn("Timeout waiting for {} operations", pendingCount.get());
                    return "TIMEOUT";
                }
            }
            logger.info("All operations completed");
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Workload interrupted");
            return "INTERRUPTED";
        } catch (Exception e) {
            logger.error("Error during workload execution: {}", e.getMessage());
            return "ERROR";
        } finally {
            metrics.stop();
        }
        
        return "COMPLETED";
    }

    private void submitRequestWithBackpressure(List<ClientSlot> clientSlots, CommandSelector selector,
                                                KeyGenerator keyGenerator, RateLimiter rateLimiter,
                                                MetricsCollector metrics, AtomicLong requestCount,
                                                AtomicLong pendingCount, Semaphore globalSlots) 
            throws InterruptedException {
        
        // 1. Apply rate limiting (if configured)
        if (rateLimiter != null) {
            rateLimiter.acquire();
        }
        
        // 2. Acquire a slot (blocks if all clients are at max pipeline depth)
        //    This provides backpressure - we won't submit if all clients are busy
        globalSlots.acquire();
        
        // 3. Find a client with an available slot
        ClientSlot slot = findAvailableSlot(clientSlots, requestCount.get());
        
        // 4. Acquire the client's local slot
        slot.availableSlots.acquire();
        
        // Note: Do NOT release globalSlots here - it will be released in the completion callback.
        // Releasing here would cause permit inflation and break backpressure.
        
        // 5. Select command
        Command command = selector.select();
        
        // 6. Track pending
        pendingCount.incrementAndGet();
        requestCount.incrementAndGet();
        
        // 7. Execute async
        command.execute(slot.client, keyGenerator)
                .thenAccept(result -> {
                    metrics.record(result);
                    slot.availableSlots.release(); // Release slot for next request
                    pendingCount.decrementAndGet();
                    globalSlots.release(); // Notify a slot is available
                })
                .exceptionally(ex -> {
                    // Still release slot on error
                    slot.availableSlots.release();
                    pendingCount.decrementAndGet();
                    globalSlots.release();
                    return null;
                });
    }
    
    /**
     * Find an available client slot using round-robin with fallback.
     */
    private ClientSlot findAvailableSlot(List<ClientSlot> clientSlots, long requestIndex) {
        int startIndex = (int) (requestIndex % clientSlots.size());
        
        // First try round-robin index
        ClientSlot preferred = clientSlots.get(startIndex);
        if (preferred.availableSlots.availablePermits() > 0) {
            return preferred;
        }
        
        // Otherwise find first available
        for (ClientSlot slot : clientSlots) {
            if (slot.availableSlots.availablePermits() > 0) {
                return slot;
            }
        }
        
        // All busy - return round-robin (will block on acquire)
        return preferred;
    }

    private void closeClientSlots(List<ClientSlot> clientSlots) {
        logger.info("Closing {} connections...", clientSlots.size());
        for (ClientSlot slot : clientSlots) {
            try {
                slot.client.close();
            } catch (Exception e) {
                logger.warn("Error closing client: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Submits warmup requests to occupy slots and establish steady-state backpressure.
     * 
     * <p>Sends PING commands on all clients using the SAME semaphores that the
     * measured workload will use. Does NOT wait for completion - the warmup
     * requests remain in-flight and will pace the subsequent measured requests.</p>
     * 
     * <p>This prevents initial burst because:</p>
     * <ol>
     *   <li>Warmup acquires slots (reducing available permits)</li>
     *   <li>Measured workload starts immediately (must wait for available slots)</li>
     *   <li>As warmup responses arrive, slots become available one at a time</li>
     *   <li>Measured requests fill slots gradually, no burst</li>
     * </ol>
     * 
     * @param clientSlots the client slots to warm up
     * @param warmupRequestsPerClient number of warmup requests per client
     * @param globalSlots the shared semaphore for backpressure (same as main workload)
     */
    private void submitWarmup(List<ClientSlot> clientSlots, int warmupRequestsPerClient, 
                              Semaphore globalSlots) {
        
        int totalWarmupRequests = clientSlots.size() * warmupRequestsPerClient;
        logger.info("Submitting warmup: {} requests per client ({} total)...", 
                    warmupRequestsPerClient, totalWarmupRequests);
        
        // Submit warmup requests (they acquire slots from the shared semaphore)
        for (int r = 0; r < warmupRequestsPerClient; r++) {
            for (ClientSlot slot : clientSlots) {
                // Try to acquire slots (non-blocking if slots available)
                boolean acquired = globalSlots.tryAcquire();
                if (!acquired) {
                    // All slots occupied - skip remaining warmup
                    // This shouldn't happen with warmup_requests=1, but handles edge cases
                    logger.debug("Warmup slots exhausted, {} requests submitted", 
                                r * clientSlots.size());
                    return;
                }
                
                boolean localAcquired = slot.availableSlots.tryAcquire();
                if (!localAcquired) {
                    // Client slot busy - release global and skip
                    globalSlots.release();
                    continue;
                }
                
                // Send PING (no metrics recording)
                // Slots are released when response arrives, naturally pacing subsequent requests
                slot.client.ping()
                        .thenAccept(result -> {
                            slot.availableSlots.release();
                            globalSlots.release();
                        })
                        .exceptionally(ex -> {
                            slot.availableSlots.release();
                            globalSlots.release();
                            return null;
                        });
            }
        }
        
        logger.info("Warmup submitted, proceeding to measured workload (slots occupied by warmup will pace requests)");
    }

    /**
     * Wraps a BenchmarkClient with semaphore-based backpressure control.
     */
    private static class ClientSlot {
        final BenchmarkClient client;
        final Semaphore availableSlots;
        final int pipelineDepth;

        ClientSlot(BenchmarkClient client, int pipelineDepth) {
            this.client = client;
            this.pipelineDepth = pipelineDepth;
            this.availableSlots = new Semaphore(pipelineDepth);
        }
    }

    /**
     * Selects commands based on their weights.
     */
    private static class CommandSelector {
        private final List<Command> commands;
        private final double[] cumulativeWeights;
        private final Random random = new Random();

        CommandSelector(List<Command> commands) {
            this.commands = commands;
            this.cumulativeWeights = new double[commands.size()];
            
            double sum = 0;
            for (int i = 0; i < commands.size(); i++) {
                sum += commands.get(i).getWeight();
                cumulativeWeights[i] = sum;
            }
        }

        Command select() {
            double r = random.nextDouble();
            for (int i = 0; i < cumulativeWeights.length; i++) {
                if (r <= cumulativeWeights[i]) {
                    return commands.get(i);
                }
            }
            return commands.get(commands.size() - 1);
        }
    }
}