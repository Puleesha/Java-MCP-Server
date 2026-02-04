package org.example;

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

public class ToolServiceNew {
    private static final Logger log = LoggerFactory.getLogger(ToolServiceNew.class);
    int leaked;

    // Shared, global executor like a real server
    private final ThreadPoolExecutor tasks =
            (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 10);

    // Tune these and report them in your thesis
    private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(5);

    /**
     * Baseline variant:
     * - Submits work directly to the shared executor (unstructured).
     * - Waits until quota reached OR deadline reached.
     * - Best-effort cancellation by Future.cancel(true).
     * - Does NOT guarantee clean join/cleanup (intentionally).
     */
    public RequestStats baselineToolProcess(int limit) throws InterruptedException {
        AtomicInteger activeTasks = new AtomicInteger(0);

        RepoAnalyser repoAnalyser = new RepoAnalyser();
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java");

        CountDownLatch quotaLatch = new CountDownLatch(limit);
        Semaphore permits = new Semaphore(Runtime.getRuntime().availableProcessors() * 4);

        List<Future<?>> futures = new ArrayList<>(filePaths.size());

        long deadlineNanos = System.nanoTime() + REQUEST_DEADLINE.toNanos();

        for (Path path : filePaths) {
            Future<?> f = tasks.submit(() -> {
                // Acquire per-request permit (interruptible so cancel(true) works)
                try {
                    permits.acquire();
                }
                catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                activeTasks.incrementAndGet();
                try {
                    if (repoAnalyser.getTodoCount() >= limit)
                        return;

                    // Cancel check for interruption
                    if (Thread.currentThread().isInterrupted())
                        return;

                    repoAnalyser.analyzeFile(path, limit, quotaLatch);
                }
                catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
                finally {
                    activeTasks.decrementAndGet();
                    permits.release();
                }
            });
            futures.add(f);
        }

        // Wait until limit reached or deadline
        waitUntilQuotaOrDeadline(quotaLatch, deadlineNanos);
        int leaked = (int) futures.stream().filter(f -> !f.isDone()).count();

        // Best-effort cancel: interrupt running tasks + prevent queued tasks from starting
        for (Future<?> f : futures) {
            f.cancel(true);
        }

        log.info("Baseline tool called with limit={} TODOs", limit);

        return new RequestStats(
                repoAnalyser.getTodoCount(),
                repoAnalyser.getFileCount(),
                leaked
        );
    }

    /**
     * Structured variant:
     * - Creates a per-request scope on top of the SAME shared executor.
     * - All task spawning goes through the scope.
     * - On quota or deadline: scope.shutdown() cancels remaining tasks.
     * - scope.joinUntil(deadline) enforces bounded waiting and prevents blocking forever.
     */
    public RequestStats structuredToolProcess(int limit) throws InterruptedException {
        AtomicInteger activeTasks = new AtomicInteger(0);

        RepoAnalyser repoAnalyser = new RepoAnalyser();
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java");

        CountDownLatch quotaLatch = new CountDownLatch(limit);

        long deadlineNanos = System.nanoTime() + REQUEST_DEADLINE.toNanos();

        try (RequestScope scope = new RequestScope(tasks, deadlineNanos, Runtime.getRuntime().availableProcessors() * 4)) {

            for (Path path : filePaths) {
                scope.spawn(() -> {
                    activeTasks.incrementAndGet();
                    try {
                        if (repoAnalyser.getTodoCount() >= limit) return;
                        if (scope.isCancelled() || Thread.currentThread().isInterrupted()) return;

                        repoAnalyser.analyzeFile(path, limit, quotaLatch);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        activeTasks.decrementAndGet();
                    }
                });
            }

            // Wait until quota or deadline
            boolean quotaReached = waitUntilQuotaOrDeadline(quotaLatch, deadlineNanos);

            // Enforce structured semantics:
            // if quota reached or deadline hit -> stop all remaining child tasks for this request
            if (quotaReached || System.nanoTime() >= deadlineNanos) {
                scope.shutdown();
            }

            // Join, but DO NOT block past deadline (and don't pretend you can)
//            scope.joinUntil(deadlineNanos);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); // preserve interrupt
        }


        log.info("Structured tool called with limit={} TODOs", limit);

        return new RequestStats(
                repoAnalyser.getTodoCount(),
                repoAnalyser.getFileCount(),
                leaked
        );
    }

    /**
     * Returns true if quota reached before deadline, false otherwise.
     */
    private boolean waitUntilQuotaOrDeadline(CountDownLatch latch, long deadlineNanos) {
        long remaining;
        while (true) {
            remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) return false;

            try {
                // Wait in small chunks so we can respect deadline precisely
                long chunk = Math.min(TimeUnit.MILLISECONDS.toNanos(50), remaining);
                if (latch.await(chunk, TimeUnit.NANOSECONDS)) return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * A per-request structured scope implemented ON TOP of an ExecutorService.
     * This is the key to your thesis: "scope owns children, cancellation, and join semantics".
     */
    class RequestScope implements AutoCloseable {
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

            // Bound per-request parallelism *before* scheduling
            permits.acquire();

            Future<?> f = executor.submit(() -> {
                try {
                    // Deadline/cancel gate
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
            leaked = (int) children.stream().filter(f -> !f.isDone()).count();

            for (Future<?> f : children) {
                f.cancel(true);
            }
        }

        void joinUntil(long joinDeadlineNanos) throws InterruptedException {
            // Best effort: wait for children until deadline; then cancel and stop waiting
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
                    // cancelled -> ok
                } catch (ExecutionException ee) {
                    // You choose semantics: fail-fast vs best-effort.
                    // For your "partial results" thesis, best-effort is usually right:
                    // just keep going.
                }
            }
        }

        @Override
        public void close() {
            // Enforce "no child work past scope end"
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
