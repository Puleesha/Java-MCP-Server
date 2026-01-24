package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ToolService {
    private static final Logger log = LoggerFactory.getLogger(ToolService.class);

    AtomicInteger activeTasks = new AtomicInteger(0);
    RepoAnalyser repoAnalyser = new RepoAnalyser();

    public String baselineToolProcess(int limit) throws InterruptedException {
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java");

        CountDownLatch latch = new CountDownLatch(limit);
        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

        for (Path path : filePaths) {
            executorService.submit(() -> {
                activeTasks.incrementAndGet();
                try {
                    repoAnalyser.analyzeFile(path, limit, latch);
                }
                catch (IOException | InterruptedException e) {
                    log.error("Server error", e);
                    e.printStackTrace();
                }
                finally {
                    activeTasks.decrementAndGet();
                }
            });
        }

        latch.await(5000, TimeUnit.SECONDS);
        executorService.shutdown();

        log.info("Baseline tool called with limit of = {} TODOs", limit);

        return "TODOs found = " + repoAnalyser.getTODOs() +
                ". Scanned " + repoAnalyser.getFileCount() + " files";
    }

    public String structuredToolProcess(int limit) throws InterruptedException {

        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java");
        CountDownLatch latch = new CountDownLatch(limit);

        try (StructuredTaskScope<Void, Void> scope = StructuredTaskScope.open()) {

            for (Path path : filePaths) {
                scope.fork(() -> {
                    activeTasks.incrementAndGet();
                    try {
                        // If we've already reached the limit, stop quickly
                        if (repoAnalyser.getTodoCount() >= limit)
                            return null;

                        repoAnalyser.analyzeFile(path, limit, latch);

                        return null;
                    }
                    finally {
                        activeTasks.decrementAndGet();
                    }
                });
            }

            latch.await(5000, TimeUnit.SECONDS);

            // Ensure children are done/cancelled before we proceed
            scope.join();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Baseline tool called with limit of = {} TODOs", limit);

        // Return whatever we managed to collect so far
        return "TODOs found = " + repoAnalyser.getTodoCount() + " files";
    }

    public int getTodoCount() {
        return repoAnalyser.getTodoCount();
    }

    public int getActiveTasks() {
        return activeTasks.get();
    }
}
