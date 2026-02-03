package org.example;

import org.example.Utils.TimeForCancellation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ToolServiceNew {
    private static final Logger log = LoggerFactory.getLogger(ToolServiceNew.class);

    // Shared, global executor like a real server
    private final ThreadPoolExecutor tasks =
            (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 10);

    private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(5);

    /**
     * Baseline variant:
     * - Submits N workers directly to shared executor (no scope).
     * - Waits until quota reached OR deadline reached.
     * - Best-effort cancellation via Future.cancel(true).
     * - No guarantee of cleanup/join before return (intentionally).
     *
     * Metric:
     * - timeToStopMs: time from cancellation signal -> workers quiescent (activeWorkers==0)
     */
    public RequestStats baselineToolProcess(int limit) {
        AtomicInteger activeWorkers = new AtomicInteger(0);
        AtomicLong cancelNs = new AtomicLong(0);
        AtomicLong quiescentNs = new AtomicLong(0);

        RepoAnalyser repoAnalyser = new RepoAnalyser();
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java");

        CountDownLatch latch = new CountDownLatch(limit);

        long deadlineNanos = System.nanoTime() + REQUEST_DEADLINE.toNanos();
        int maxThreadCount = Runtime.getRuntime().availableProcessors() * 4;

        ConcurrentLinkedQueue<Path> queue = new ConcurrentLinkedQueue<>(filePaths);
        List<Future<?>> workers = new ArrayList<>(maxThreadCount);

        for (int i = 0; i < maxThreadCount; i++) {
            Future<?> f = tasks.submit(() -> {
                activeWorkers.incrementAndGet();
                try {
                    while (true) {
                        if (repoAnalyser.getTodoCount() >= limit || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos)
                            return;

                        Path path = queue.poll();
                        if (path == null) return;

                        try {
                            repoAnalyser.analyzeFile(path, limit, latch);
                        } catch (IOException e) {
                            // best-effort skip
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } finally {
                    int now = activeWorkers.decrementAndGet();
                    if (now == 0 && cancelNs.get() != 0 && quiescentNs.get() == 0) {
                        quiescentNs.compareAndSet(0, System.nanoTime());
                    }
                }
            });
            workers.add(f);
        }

        boolean quotaReached = waitUntilQuotaOrDeadline(latch, deadlineNanos);

        // signal cancel on quota/deadline
        if (quotaReached || System.nanoTime() >= deadlineNanos) {
            cancelNs.compareAndSet(0, System.nanoTime());

            for (Future<?> f : workers) {
                f.cancel(true);
            }

            // if already quiescent, record immediately
            if (activeWorkers.get() == 0) {
                quiescentNs.compareAndSet(0, System.nanoTime());
            }
        }

        long returnNs = System.nanoTime();
        TimeForCancellation cancelTime = new TimeForCancellation(cancelNs.get(), quiescentNs.get(), returnNs);

        log.info(
                "Baseline: limit={}, todos={}, files={}, activeAtReturn={}, timeToStopMs={}",
                limit,
                repoAnalyser.getTodoCount(),
                repoAnalyser.getFileCount(),
                activeWorkers.get(),
                cancelTime.getTimeToStopMs()
        );

        return new RequestStats(repoAnalyser.getTodoCount(), repoAnalyser.getFileCount(), (int) cancelTime.getTimeToStopMs());
    }

    /**
     * Structured variant:
     * - Uses RequestScope over the SAME shared executor.
     * - Spawns N workers THROUGH the scope.
     * - On quota/deadline: scope.shutdown() cancels children.
     * - scope.joinUntil(deadline) prevents waiting forever.
     *
     * Metric:
     * - timeToStopMs: time from scope shutdown -> workers quiescent (activeWorkers==0)
     */
    public RequestStats structuredToolProcess(int limit) {
        AtomicInteger activeWorkers = new AtomicInteger(0);
        AtomicLong cancelNs = new AtomicLong(0);
        AtomicLong quiescentNs = new AtomicLong(0);

        RepoAnalyser repoAnalyser = new RepoAnalyser();
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java");

        CountDownLatch latch = new CountDownLatch(limit);

        long deadlineNanos = System.nanoTime() + REQUEST_DEADLINE.toNanos();
        int maxThreadCount = Runtime.getRuntime().availableProcessors() * 4;

        ConcurrentLinkedQueue<Path> queue = new ConcurrentLinkedQueue<>(filePaths);

        try (RequestScope scope = new RequestScope(tasks, deadlineNanos, maxThreadCount)) {

            for (int i = 0; i < maxThreadCount; i++) {
                scope.spawn(() -> {
                    activeWorkers.incrementAndGet();
                    try {
                        while (true) {
                            if (repoAnalyser.getTodoCount() >= limit || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos)
                                return;

                            Path path = queue.poll();
                            if (path == null) return;

                            try {
                                repoAnalyser.analyzeFile(path, limit, latch);
                            } catch (IOException e) {
                                // best-effort skip
                            }
                        }
                    } finally {
                        int now = activeWorkers.decrementAndGet();
                        if (now == 0 && cancelNs.get() != 0 && quiescentNs.get() == 0) {
                            quiescentNs.compareAndSet(0, System.nanoTime());
                        }
                    }
                });
            }

            boolean quotaReached = waitUntilQuotaOrDeadline(latch, deadlineNanos);

            if (quotaReached || System.nanoTime() >= deadlineNanos) {
                cancelNs.compareAndSet(0, System.nanoTime());
                scope.shutdown();

                if (activeWorkers.get() == 0) {
                    quiescentNs.compareAndSet(0, System.nanoTime());
                }
            }

            scope.joinUntil(deadlineNanos);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        long returnNs = System.nanoTime();
        TimeForCancellation cancelTime = new TimeForCancellation(cancelNs.get(), quiescentNs.get(), returnNs);

        log.info(
                "Structured: limit={}, todos={}, files={}, activeAtReturn={}, timeToStopMs={}",
                limit,
                repoAnalyser.getTodoCount(),
                repoAnalyser.getFileCount(),
                activeWorkers.get(),
                cancelTime.getTimeToStopMs()
        );

        return new RequestStats(repoAnalyser.getTodoCount(), repoAnalyser.getFileCount(), (int) cancelTime.getTimeToStopMs());
    }

    /**
     * Returns true if quota reached before deadline, false otherwise.
     */
    private boolean waitUntilQuotaOrDeadline(CountDownLatch latch, long deadlineNanos) {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) return false;

            try {
                long chunk = Math.min(TimeUnit.MILLISECONDS.toNanos(50), remaining);
                if (latch.await(chunk, TimeUnit.NANOSECONDS)) return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Request-scoped structured execution built on top of a shared executor.
     */
    static final class RequestScope implements AutoCloseable {
        private final ExecutorService executor;
        private final long deadlineNanos;
        private final Semaphore permits;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final List<Future<?>> children = new CopyOnWriteArrayList<>();

        RequestScope(ExecutorService executor, long deadlineNanos, int maxParallel) {
            this.executor = Objects.requireNonNull(executor);
            this.deadlineNanos = deadlineNanos;
            this.permits = new Semaphore(Math.max(1, maxParallel));
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void spawn(InterruptibleRunnable r) throws InterruptedException {
            if (cancelled.get()) return;

            // Backpressure: bound per-request parallelism before scheduling
            permits.acquire();

            Future<?> f = executor.submit(() -> {
                try {
                    if (cancelled.get()) return;
                    if (System.nanoTime() >= deadlineNanos) return;
                    if (Thread.currentThread().isInterrupted()) return;

                    r.run();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    permits.release();
                }
            });

            children.add(f);
        }

        void shutdown() {
            if (!cancelled.compareAndSet(false, true)) return;
            for (Future<?> f : children) {
                f.cancel(true);
            }
        }

        void joinUntil(long joinDeadlineNanos) throws InterruptedException {
            for (Future<?> f : children) {
                long remaining = joinDeadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    shutdown();
                    return;
                }
                try {
                    f.get(remaining, TimeUnit.NANOSECONDS);
                } catch (TimeoutException te) {
                    shutdown();
                    return;
                } catch (CancellationException ignored) {
                    // ok
                } catch (ExecutionException ignored) {
                    // best-effort: keep joining others
                }
            }
        }

        @Override
        public void close() {
            shutdown();
            try {
                joinUntil(deadlineNanos);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    interface InterruptibleRunnable {
        void run() throws InterruptedException;
    }
}
