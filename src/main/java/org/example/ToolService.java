package org.example;

import org.example.CustomScope.TaskJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
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
    private final ExecutorService tasks = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    /**
     * Baseline variant:
     * - Submits work directly to the shared executor (unstructured).
     * - Waits until quota reached OR deadline reached.
     * - Best-effort cancellation by Future.cancel(true).
     * - Does NOT guarantee clean join/cleanup (intentionally).
     */
    public RequestStats baselineToolProcess(int limit) throws InterruptedException {

        RepoAnalyser repoAnalyser = new RepoAnalyser(limit);
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository");

        AtomicInteger todoCount = new AtomicInteger(0);
        AtomicInteger activeTasks = new AtomicInteger(filePaths.size());

        //------------------------------------------------
        // Spawn tasks (unstructured)
        //------------------------------------------------

        for (Path path : filePaths) {
            tasks.submit(() -> {
                try {
                    if (todoCount.get() >= limit)
                        return;

                    repoAnalyser.analyzeFile(path);
                }
                catch (IOException | InterruptedException e) {
                    log.error("Error when analysing file = {}", e.getMessage());
                }
                finally {
                    activeTasks.decrementAndGet();
                }
            });
        }

        //------------------------------------------------
        // Wait until the deadline
        //------------------------------------------------

        while (!repoAnalyser.isLimitReached()) {
            if (todoCount.get() >= limit)
                break;

            try {
                Thread.sleep(1);
            }
            catch (InterruptedException e) {
                log.error("Sleep interrupted = {}",  e.getMessage());
            }
        }

        int unfinishedTasks = activeTasks.get();

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
     * - Creates a new scope per request
     * - All task created goes through the scope.
     * - On deadline: scope cancels remaining tasks.
     */
    public RequestStats structuredToolProcess(int limit) throws InterruptedException {

        RepoAnalyser repoAnalyser = new RepoAnalyser(limit);
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository");
        AtomicInteger activeTasks = new AtomicInteger(filePaths.size());
        TaskJoiner<Void> joiner = new TaskJoiner<>(repoAnalyser);

        try (var scope = StructuredTaskScope.open(joiner)) {
            for (Path path : filePaths) {
                scope.fork(() -> {
                    try {
                        // Joiner controls the cancellation so check for interrupts
                        if (Thread.currentThread().isInterrupted())
                            return null;

                        repoAnalyser.analyzeFile(path);
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

            // Blocks until limit reached or tasks completed
            scope.join();
        }
        catch (InterruptedException ie) {

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
}
