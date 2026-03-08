package org.example;

import org.example.CustomScope.TaskJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class contains both the baseline and structured thread handling approaches
 *
 * @author Puleesha Vilhan
 */
public class ToolService {
    private static final Logger log = LoggerFactory.getLogger(ToolService.class);

    // Shared, global executor like a real server
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();

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

        RepoAnalyser repoAnalyser = new RepoAnalyser();
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository");

        CountDownLatch quotaLatch = new CountDownLatch(limit);

        List<Future<?>> futures = new ArrayList<>(filePaths.size());
        AtomicInteger activeTasks = new AtomicInteger(filePaths.size());

        long deadlineNanos = System.nanoTime() + REQUEST_DEADLINE.toNanos();

        for (Path path : filePaths) {
            Future<?> f = tasks.submit(() -> {
                try {
                    // Cancel check for interruption
                    if (Thread.currentThread().isInterrupted())
                        return;

                    repoAnalyser.analyzeFile(path, limit, quotaLatch);
                }
                catch (IOException | InterruptedException e) {
                    log.warn("Error when analysing file = {}", e.getMessage());
                    Thread.currentThread().interrupt();
                }
                finally {
                    activeTasks.decrementAndGet();
                }
            });
            futures.add(f);
        }

        // Wait until limit reached or deadline
        // TODO: Change / remove this method of waiting for timeout
        waitUntilQuotaOrDeadline(quotaLatch, deadlineNanos);
        int unfinishedTasks = activeTasks.get();

        // Best-effort cancel: interrupt running tasks + prevent queued tasks from starting
        for (Future<?> f : futures) {
            f.cancel(true);
        }

        log.info("Baseline tool called with limit of {} TODOs", limit);

        return new RequestStats(
                repoAnalyser.getTodoCount(),
                repoAnalyser.getFileCount(),
                unfinishedTasks,
                repoAnalyser.getTODOs()
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

        RepoAnalyser repoAnalyser = new RepoAnalyser();
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository");
        AtomicInteger activeTasks = new AtomicInteger(filePaths.size());

        Instant deadline = Instant.now().plus(REQUEST_DEADLINE);
        TaskJoiner<Void> joiner = new TaskJoiner<>(limit, deadline);

        try (var scope = StructuredTaskScope.open(joiner)) {
            for (Path path : filePaths) {
                scope.fork(() -> {
                    try {
                        // Joiner controls the cancellation so check for interrupts
                        if (Thread.currentThread().isInterrupted())
                            return null;

                        repoAnalyser.analyzeFile(path, limit, null);
                    }
                    catch (IOException e) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        activeTasks.decrementAndGet();
                    }

                    return null;
                });
            }

            // This blocks until joiner decides:
            // - quota reached OR
            // - deadline reached OR
            // - all tasks finished
            scope.join();

        } catch (InterruptedException ie) {

            Thread.currentThread().interrupt();
            throw ie;
        }

        int unfinishedTasks = activeTasks.get();

        log.info("Structured tool called with limit of {} TODOs", limit);

        return new RequestStats(
                repoAnalyser.getTodoCount(),
                repoAnalyser.getFileCount(),
                unfinishedTasks,
                repoAnalyser.getTODOs()
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
}
