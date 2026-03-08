package org.example.old;

import org.example.RepoAnalyser;
import org.example.RequestStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ToolServiceOld {
    private static final Logger log = LoggerFactory.getLogger(ToolServiceOld.class);
    private final ExecutorService tasks = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);

    public RequestStats baselineToolProcess(int limit) throws InterruptedException {
        AtomicInteger activeTasks = new AtomicInteger(0);
        RepoAnalyser repoAnalyser = new RepoAnalyser();
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository");

        CountDownLatch latch = new CountDownLatch(limit);

        for (Path path : filePaths) {
            tasks.submit(() -> {
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

        latch.await(5, TimeUnit.SECONDS);

        log.info("Baseline tool called with limit of = {} TODOs", limit);

        return new RequestStats(repoAnalyser.getTodoCount(), repoAnalyser.getFileCount(), activeTasks.get(), repoAnalyser.getTODOs());
    }

    public RequestStats structuredToolProcess(int limit) throws InterruptedException {
        AtomicInteger activeTasks = new AtomicInteger(0);
        RepoAnalyser repoAnalyser = new RepoAnalyser();
        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository");

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

            latch.await(5, TimeUnit.SECONDS);
            scope.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Structured tool called with limit of = {} TODOs", limit);

        return new RequestStats(repoAnalyser.getTodoCount(), repoAnalyser.getFileCount(), activeTasks.get(), repoAnalyser.getTODOs());
    }
}
