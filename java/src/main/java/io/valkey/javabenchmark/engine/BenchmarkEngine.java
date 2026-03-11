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
 * <p>Command generation is parallelized across multiple "command issuer" threads
 * to avoid a single-threaded bottleneck at high connection counts. Each issuer
 * thread manages a partition of the client connections with its own semaphore,
 * key generator, and command selector to minimize cross-thread contention.</p>
 * 
 * <p>This design ensures:</p>
 * <ul>
 *   <li>Bounded memory usage - max in-flight requests = connections × pipeline_depth</li>
 *   <li>Accurate latency measurement - no queue backlog inflation</li>
 *   <li>Natural backpressure - new requests wait for available slots</li>
 *   <li>Correct behavior for both duration-based and request-based workloads</li>
 *   <li>Linear scaling of command generation throughput with issuer threads</li>
 * </ul>
 *
 * @author Ilia Kolominsky
 */
public class BenchmarkEngine {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkEngine.class);
    
    /** Default pipeline depth (max in-flight requests per client) */
    private static final int DEFAULT_PIPELINE_DEPTH = 1;
    
    /** Progress logging interval in seconds */
    private static final int PROGRESS_LOG_INTERVAL_SECONDS = 10;
    
    /** Default divisor for auto-computing command issuer threads from connection count */
    private static final int CONNECTIONS_PER_ISSUER_THREAD = 32;

    private final String host;
    private final int port;
    private final DriverConfig driverConfig;
    private final WorkloadConfig workloadConfig;
    private final NdjsonMetricsWriter metricsWriter;
    private final String commitId;
    private final Integer commandIssuerThreadsOverride;
    
    // Client reference for version info
    private BenchmarkClient sampleClient;

    public BenchmarkEngine(String host, int port, DriverConfig driverConfig,
                          WorkloadConfig workloadConfig, String metricsPath) {
        this(host, port, driverConfig, workloadConfig, metricsPath, null, null);
    }

    public BenchmarkEngine(String host, int port, DriverConfig driverConfig,
                          WorkloadConfig workloadConfig, String metricsPath, String commitId) {
        this(host, port, driverConfig, workloadConfig, metricsPath, commitId, null);
    }

    public BenchmarkEngine(String host, int port, DriverConfig driverConfig,
                          WorkloadConfig workloadConfig, String metricsPath, String commitId,
                          Integer commandIssuerThreads) {
        this.host = host;
        this.port = port;
        this.driverConfig = driverConfig;
        this.workloadConfig = workloadConfig;
        this.metricsWriter = new NdjsonMetricsWriter(metricsPath);
        this.commitId = commitId;
        this.commandIssuerThreadsOverride = commandIssuerThreads;
    }

    public void run() throws Exception {
        logger.info("Starting benchmark: {}", workloadConfig.getBenchmarkProfile().getName());
        logger.info("Driver: {}, Mode: {}", driverConfig.getDriverId(), driverConfig.getMode());
        logger.info("Server: {}:{}", host, port);

        // Set up metadata using a sample client to get version info
        setupMetadata();

        for (PhaseConfig phase : workloadConfig.getPhases()) {
            executePhase(phase);
        }

        logger.info("Benchmark completed");
    }

    /**
     * Set up metadata for metrics output, including driver versions.
     */
    private void setupMetadata() {
        try {
            // Create a temporary client to get version info
            sampleClient = BenchmarkClientFactory.createAndConnect(host, port, driverConfig);
            
            String driverId = driverConfig.getDriverId();
            String primaryVersion = sampleClient.getDriverVersion();
            String secondaryDriverId = driverConfig.getSecondaryDriverId();
            String secondaryVersion = sampleClient.getSecondaryDriverVersion();
            
            metricsWriter.setMetadata(commitId, driverId, primaryVersion, secondaryDriverId, secondaryVersion);
            
            logger.info("Metadata: commit={}, driver={}, version={}", 
                        commitId != null ? commitId : "N/A", driverId, primaryVersion);
            
            // Close the sample client
            sampleClient.close();
            sampleClient = null;
        } catch (Exception e) {
            logger.warn("Failed to get driver version for metadata: {}", e.getMessage());
            // Still set basic metadata
            metricsWriter.setMetadata(commitId, driverConfig.getDriverId(), "unknown", 
                                     driverConfig.getSecondaryDriverId(), null);
        }
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

    /**
     * Compute the effective number of command issuer threads.
     * 
     * <p>Priority: CLI override > auto-detect based on connection count.</p>
     * <p>Auto-detect: max(1, connections / 32), capped at available processors.</p>
     */
    private int computeIssuerThreadCount(int connections) {
        if (commandIssuerThreadsOverride != null && commandIssuerThreadsOverride > 0) {
            return commandIssuerThreadsOverride;
        }
        int auto = Math.max(1, connections / CONNECTIONS_PER_ISSUER_THREAD);
        return Math.min(auto, Runtime.getRuntime().availableProcessors());
    }

    private String executeWorkload(PhaseConfig phase, List<ClientSlot> clientSlots,
                                   List<Command> commands, KeyGenerator keyGenerator,
                                   RateLimiter rateLimiter, MetricsCollector metrics) {
        
        CompletionConfig completion = phase.getCompletion();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong requestCount = new AtomicLong(0);
        AtomicLong pendingCount = new AtomicLong(0);
        
        int issuerThreadCount = computeIssuerThreadCount(clientSlots.size());
        logger.info("Using {} command issuer thread(s) for {} connections", 
                    issuerThreadCount, clientSlots.size());
        
        // Submit warmup requests BEFORE starting metrics
        int warmupRequests = phase.getWarmupRequests();
        if (warmupRequests > 0) {
            // Use a single global semaphore for warmup (simple, small number of requests)
            int totalSlots = clientSlots.stream().mapToInt(s -> s.pipelineDepth).sum();
            Semaphore warmupGlobalSlots = new Semaphore(totalSlots);
            submitWarmup(clientSlots, warmupRequests, warmupGlobalSlots);
        }
        
        metrics.start();
        
        // Progress logging state
        long startTime = System.currentTimeMillis();
        
        try {
            if (issuerThreadCount == 1) {
                // Single-threaded path (optimized: no partitioning overhead)
                executeSingleThreaded(phase, clientSlots, commands, keyGenerator, rateLimiter,
                                      metrics, requestCount, pendingCount, running, startTime);
            } else {
                // Multi-threaded path with partitioned command issuers
                executeMultiThreaded(phase, clientSlots, commands, keyGenerator, rateLimiter,
                                      metrics, requestCount, pendingCount, running, startTime,
                                      issuerThreadCount);
            }
            
            // Wait for remaining in-flight requests to complete
            long remaining = pendingCount.get();
            if (remaining > 0) {
                logger.info("Waiting for {} pending operations to complete...", remaining);
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

    /**
     * Single-threaded command issuer (original behavior, no partitioning overhead).
     */
    private void executeSingleThreaded(PhaseConfig phase, List<ClientSlot> clientSlots,
                                        List<Command> commands, KeyGenerator keyGenerator,
                                        RateLimiter rateLimiter, MetricsCollector metrics,
                                        AtomicLong requestCount, AtomicLong pendingCount,
                                        AtomicBoolean running, long startTime) 
            throws InterruptedException {
        
        Thread.currentThread().setName("command-issuer");
        
        CompletionConfig completion = phase.getCompletion();
        CommandSelector selector = new CommandSelector(commands);
        int totalSlots = clientSlots.stream().mapToInt(s -> s.pipelineDepth).sum();
        Semaphore globalSlots = new Semaphore(totalSlots);
        long lastLogTime = System.currentTimeMillis();
        
        if (completion.isDurationBased()) {
            long endTime = System.currentTimeMillis() + (completion.getDurationSeconds() * 1000);
            
            while (System.currentTimeMillis() < endTime && running.get()) {
                submitRequestWithBackpressure(clientSlots, selector, keyGenerator, rateLimiter, 
                                               metrics, requestCount, pendingCount, globalSlots, -1);
                lastLogTime = logProgressIfNeeded(requestCount.get(), -1, lastLogTime, startTime);
            }
        } else {
            long targetRequests = completion.getTotalRequests();
            
            while (running.get()) {
                boolean submitted = submitRequestWithBackpressure(clientSlots, selector, keyGenerator, rateLimiter, 
                                               metrics, requestCount, pendingCount, globalSlots, targetRequests);
                if (!submitted) break;
                lastLogTime = logProgressIfNeeded(requestCount.get(), targetRequests, lastLogTime, startTime);
            }
        }
    }

    /**
     * Multi-threaded command issuer with partitioned client slots.
     * 
     * <p>Partitions the client slots among N issuer threads. Each thread gets:</p>
     * <ul>
     *   <li>Its own subset of ClientSlots</li>
     *   <li>Its own Semaphore (permits = sum of pipeline depths in partition)</li>
     *   <li>Its own KeyGenerator (forked with unique seed per thread)</li>
     *   <li>Its own CommandSelector (independent Random)</li>
     * </ul>
     * 
     * <p>Shared across all threads (already thread-safe):</p>
     * <ul>
     *   <li>AtomicLong requestCount — for total tracking and request-based completion</li>
     *   <li>AtomicLong pendingCount — for drain tracking</li>
     *   <li>MetricsCollector — uses SynchronizedHistogram and ConcurrentHashMap</li>
     *   <li>RateLimiter — uses Semaphore internally</li>
     * </ul>
     */
    private void executeMultiThreaded(PhaseConfig phase, List<ClientSlot> clientSlots,
                                       List<Command> commands, KeyGenerator keyGenerator,
                                       RateLimiter rateLimiter, MetricsCollector metrics,
                                       AtomicLong requestCount, AtomicLong pendingCount,
                                       AtomicBoolean running, long startTime,
                                       int issuerThreadCount) throws Exception {
        
        CompletionConfig completion = phase.getCompletion();
        
        // Partition client slots into roughly equal groups
        List<List<ClientSlot>> partitions = partitionList(clientSlots, issuerThreadCount);
        
        ExecutorService issuerPool = Executors.newFixedThreadPool(issuerThreadCount, new ThreadFactory() {
            private int counter = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "command-issuer-" + counter++);
                t.setDaemon(true);
                return t;
            }
        });
        
        List<Future<?>> futures = new ArrayList<>();
        
        for (int i = 0; i < partitions.size(); i++) {
            final int threadIndex = i;
            final List<ClientSlot> partition = partitions.get(i);
            
            if (partition.isEmpty()) {
                continue; // Skip empty partitions (more threads than clients)
            }
            
            // Each thread gets its own key generator, command selector, and semaphore
            final KeyGenerator threadKeyGen = keyGenerator.forkForThread(threadIndex);
            final CommandSelector threadSelector = new CommandSelector(commands);
            final int partitionSlots = partition.stream().mapToInt(s -> s.pipelineDepth).sum();
            final Semaphore partitionSemaphore = new Semaphore(partitionSlots);
            
            futures.add(issuerPool.submit(() -> {
                Thread.currentThread().setName("command-issuer-" + threadIndex);
                long lastLogTime = System.currentTimeMillis();
                
                try {
                    if (completion.isDurationBased()) {
                        long endTime = System.currentTimeMillis() + (completion.getDurationSeconds() * 1000);
                        
                        while (System.currentTimeMillis() < endTime && running.get()) {
                            submitRequestWithBackpressure(partition, threadSelector, threadKeyGen, 
                                                           rateLimiter, metrics, requestCount, 
                                                           pendingCount, partitionSemaphore, -1);
                        }
                    } else {
                        long targetRequests = completion.getTotalRequests();
                        
                        while (running.get()) {
                            boolean submitted = submitRequestWithBackpressure(partition, threadSelector, threadKeyGen, 
                                                           rateLimiter, metrics, requestCount, 
                                                           pendingCount, partitionSemaphore, targetRequests);
                            if (!submitted) break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.error("Error in command-issuer-{}: {}", threadIndex, e.getMessage());
                    running.set(false);
                }
            }));
        }
        
        // Progress logging from main thread while issuers run
        CompletionConfig comp = phase.getCompletion();
        long lastLogTime = System.currentTimeMillis();
        boolean allDone = false;
        
        while (!allDone) {
            allDone = true;
            for (Future<?> f : futures) {
                if (!f.isDone()) {
                    allDone = false;
                    break;
                }
            }
            if (!allDone) {
                Thread.sleep(100);
                long target = comp.isDurationBased() ? -1 : comp.getTotalRequests();
                lastLogTime = logProgressIfNeeded(requestCount.get(), target, lastLogTime, startTime);
            }
        }
        
        // Check for exceptions
        for (Future<?> f : futures) {
            f.get(); // Propagates exceptions
        }
        
        issuerPool.shutdown();
        issuerPool.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Partition a list into N roughly equal sublists.
     */
    private <T> List<List<T>> partitionList(List<T> list, int partitions) {
        List<List<T>> result = new ArrayList<>(partitions);
        int size = list.size();
        int baseSize = size / partitions;
        int remainder = size % partitions;
        
        int offset = 0;
        for (int i = 0; i < partitions; i++) {
            int partSize = baseSize + (i < remainder ? 1 : 0);
            result.add(list.subList(offset, offset + partSize));
            offset += partSize;
        }
        return result;
    }

    /**
     * Submit a single request with backpressure control.
     * 
     * <p>For request-based completion, atomically claims a request slot BEFORE any
     * blocking operations (semaphore acquire, rate limiting). This eliminates the
     * race condition where multiple threads pass the loop check simultaneously and
     * over-submit past the target count.</p>
     *
     * @param targetRequests the target request count for request-based completion,
     *                       or {@code <= 0} for duration-based (unlimited)
     * @return {@code true} if a request was submitted, {@code false} if the target
     *         was already reached (request-based only)
     */
    private boolean submitRequestWithBackpressure(List<ClientSlot> clientSlots, CommandSelector selector,
                                                KeyGenerator keyGenerator, RateLimiter rateLimiter,
                                                MetricsCollector metrics, AtomicLong requestCount,
                                                AtomicLong pendingCount, Semaphore globalSlots,
                                                long targetRequests) 
            throws InterruptedException {
        
        // 0. For request-based completion: atomically claim a slot BEFORE blocking.
        //    This prevents over-submission when multiple threads race past the loop check.
        if (targetRequests > 0) {
            long claimed = requestCount.getAndIncrement();
            if (claimed >= targetRequests) {
                requestCount.decrementAndGet(); // Unclaim
                return false; // Target reached
            }
        }
        
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
        
        // 6. Track pending (and increment for duration-based only)
        pendingCount.incrementAndGet();
        if (targetRequests <= 0) {
            requestCount.incrementAndGet(); // Duration-based: increment here
        }
        
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
        return true;
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
     * Log progress if enough time has elapsed since last log.
     * 
     * @param current current request count
     * @param target target request count (-1 for duration-based completion)
     * @param lastLogTime timestamp of last log
     * @param startTime timestamp when workload started
     * @return updated lastLogTime
     */
    private long logProgressIfNeeded(long current, long target, long lastLogTime, long startTime) {
        long now = System.currentTimeMillis();
        if (now - lastLogTime < PROGRESS_LOG_INTERVAL_SECONDS * 1000) {
            return lastLogTime;
        }
        
        long elapsedMs = now - startTime;
        long rate = elapsedMs > 0 ? (current * 1000 / elapsedMs) : 0;
        
        if (target > 0) {
            double percent = (current * 100.0 / target);
            logger.info("Progress: {}/{} requests ({} %) - {} req/s", 
                       current, target, String.format("%.1f", percent), rate);
        } else {
            logger.info("Progress: {} requests - {} req/s", current, rate);
        }
        
        return now;
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
