package io.github.kurukurumi33.mcp.server;

import io.github.kurukurumi33.mcp.protocol.McpException;
import io.github.kurukurumi33.mcp.protocol.McpProtocol;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Per-backend concurrency gates and per-call deadlines.
 *
 * <p>Every backend is a separate resource: one slow S3 bucket must not be able to occupy the whole
 * gateway and starve the DuckDB queries. Each namespace therefore gets its own bulkhead — a bounded
 * number of calls in flight and a bounded number of callers allowed to wait — and a call that cannot
 * get in is rejected with 429 before it touches the backend, rather than being queued behind it.
 *
 * <p>Deadlines are enforced by running the adapter on a virtual thread and giving up on the future
 * after {@code callTimeoutMs}. Cancelling with {@code interrupt} only stops clients that honour
 * interrupts; a blocking driver that ignores them keeps running and, correctly, keeps its permit
 * until it really finishes.
 *
 * <p>Constructed with zero limits, {@link #unlimited()} runs everything inline and is what a
 * directly-constructed engine uses; the Spring starter always supplies a configured instance.
 */
public final class AdmissionControl {

    private static final AdmissionControl UNLIMITED =
            new AdmissionControl(0, 0, 0, 0, null, Executors.newVirtualThreadPerTaskExecutor());

    private final int maxConcurrent;
    private final int maxQueued;
    private final long queueWaitMs;
    private final long callTimeoutMs;
    private final CallLimiter limiter;
    private final ConcurrentMap<String, Gate> gates = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    private AdmissionControl(int maxConcurrent, int maxQueued, long queueWaitMs, long callTimeoutMs,
                             CallLimiter limiter, ExecutorService executor) {
        this.maxConcurrent = Math.max(maxConcurrent, 0);
        this.maxQueued = Math.max(maxQueued, 0);
        this.queueWaitMs = Math.max(queueWaitMs, 0);
        this.callTimeoutMs = Math.max(callTimeoutMs, 0);
        this.limiter = limiter;
        this.executor = executor;
    }

    /**
     * @param maxConcurrent calls allowed to run per backend at once; 0 means no limit
     * @param maxQueued     callers allowed to wait for a permit per backend; 0 means none wait
     * @param queueWaitMs   how long a caller may wait for a permit; 0 rejects immediately
     * @param callTimeoutMs deadline for one adapter call; 0 means no deadline
     */
    public static AdmissionControl of(int maxConcurrent, int maxQueued, long queueWaitMs,
                                      long callTimeoutMs) {
        return of(maxConcurrent, maxQueued, queueWaitMs, callTimeoutMs, null);
    }

    /**
     * @param limiter shared rate limit consulted before the local bulkhead; null to disable. It is
     *                checked first because a shared budget is cheaper to enforce than a slot and
     *                should apply even when this instance happens to have capacity.
     */
    public static AdmissionControl of(int maxConcurrent, int maxQueued, long queueWaitMs,
                                      long callTimeoutMs, CallLimiter limiter) {
        if (maxConcurrent <= 0 && callTimeoutMs <= 0 && limiter == null) {
            return UNLIMITED;
        }
        return new AdmissionControl(maxConcurrent, maxQueued, queueWaitMs, callTimeoutMs, limiter,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    /** No gates and no deadline: adapter calls run on the calling thread. */
    public static AdmissionControl unlimited() {
        return UNLIMITED;
    }

    public boolean boundsConcurrency() {
        return maxConcurrent > 0;
    }

    public long callTimeoutMs() {
        return callTimeoutMs;
    }

    /**
     * Runs {@code task} inside {@code backend}'s bulkhead and under the call deadline.
     *
     * @throws McpException 429 when the bulkhead is full, 504 when the deadline expires
     */
    public <T> T call(String backend, String operation, Callable<T> task) {
        if (limiter != null) {
            CallLimiter.Decision decision = limiter.tryAcquire(backend);
            if (!decision.allowed()) {
                throw McpException.rateLimited(backend, operation, decision.retryAfterMs());
            }
        }

        Gate gate = maxConcurrent > 0 ? gates.computeIfAbsent(backend, name -> new Gate()) : null;
        boolean holdsPermit = acquire(gate, backend, operation);

        if (callTimeoutMs <= 0) {
            try {
                return invoke(task);
            } finally {
                if (holdsPermit) {
                    gate.running.release();
                }
            }
        }

        Future<T> future;
        try {
            future = executor.submit(() -> {
                try {
                    return task.call();
                } finally {
                    // Released by the worker, not the caller: a task that ignores the interrupt
                    // still occupies a slot until it actually returns.
                    if (holdsPermit) {
                        gate.running.release();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (holdsPermit) {
                gate.running.release();
            }
            throw new McpException(McpProtocol.INTERNAL_ERROR, 500,
                    "Gateway worker pool rejected the call to backend '" + backend + "'");
        }

        try {
            return future.get(callTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw McpException.backendTimeout(backend, operation, callTimeoutMs);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpException(McpProtocol.INTERNAL_ERROR, 500,
                    "Interrupted while calling backend '" + backend + "'");
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        }
    }

    private boolean acquire(Gate gate, String backend, String operation) {
        if (gate == null) {
            return false;
        }
        if (gate.running.tryAcquire()) {
            return true;
        }
        if (queueWaitMs <= 0 || !gate.waiting.tryAcquire()) {
            throw McpException.serverBusy(backend, operation, maxConcurrent);
        }
        try {
            if (!gate.running.tryAcquire(queueWaitMs, TimeUnit.MILLISECONDS)) {
                throw McpException.serverBusy(backend, operation, maxConcurrent);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(McpProtocol.INTERNAL_ERROR, 500,
                    "Interrupted while waiting for backend '" + backend + "'");
        } finally {
            // The waiting permit only covers the period spent queued, so it is released as soon as
            // this caller either gets a running slot or gives up.
            gate.waiting.release();
        }
    }

    private static <T> T invoke(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new RuntimeException(cause);
    }

    private final class Gate {
        private final Semaphore running = new Semaphore(maxConcurrent);
        private final Semaphore waiting = new Semaphore(Math.max(maxQueued, 0));
    }
}