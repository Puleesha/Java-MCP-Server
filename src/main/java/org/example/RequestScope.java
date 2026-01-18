package org.example;

import org.example.MockTool.RepoAnalyser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestScope {
    private static final Logger log = LoggerFactory.getLogger(RequestScope.class);

    AtomicInteger activeTasks = new AtomicInteger(0);
    RepoAnalyser repoAnalyser = new RepoAnalyser();

    public String analyseRepoTool(int limit) {
        Queue<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java");

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

        try {
            for (Path path: filePaths) {
                executorService.submit(() -> {
                    activeTasks.incrementAndGet();
                    try {
                        repoAnalyser.analyzeFile(path, limit);
                        return null;
                    }
                    catch (IOException | InterruptedException e) {
                        log.error("Server error", e);
                        e.printStackTrace();
                        return null;
                    }
                    finally {
                        activeTasks.decrementAndGet();
                    }
                });
            }

            Thread.sleep(5000); // Set a timeout for 5 seconds
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        executorService.shutdown();

        log.info("Baseline tool called with limit of = {} TODOs", limit);

        return "TODOs found = " + repoAnalyser.getTODOs() +
                ". Scanned " + repoAnalyser.getFileCount() + " files";
    }

    public int getTodoCount() {
        return repoAnalyser.getTodoCount();
    }

    public int getActiveTasks() {
        return activeTasks.get();
    }
}
