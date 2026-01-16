package org.example;

import org.example.MockTool.RepoAnalyser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RequestScope {
    private static final Logger log = LoggerFactory.getLogger(RequestScope.class);

    public static String analyseRepoTool(int limit) {
        // Bcz of the way the Docker MCP gateway works (short bursts of images running) the singleton might be redundant.
        RepoAnalyser repoAnalyser = new RepoAnalyser();

        Queue<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java");

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
        CountDownLatch latch = new CountDownLatch(limit);

        while ((repoAnalyser.getTodoCount() < limit) && !filePaths.isEmpty()) {
            executorService.submit(() -> {
                try {
                    repoAnalyser.analyzeFile(filePaths.poll(), latch, limit);
                } catch (IOException e) {
                    log.error("Server error", e);
                    e.printStackTrace();
                }
            });
        }

        try {
            latch.await(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        executorService.shutdown();
        log.info("Baseline tool called with limit of = {} TODOs", limit);

        return "TODOs found = " + repoAnalyser.getTODOs() +
                ". Scanned " + repoAnalyser.getFileCount() + " files";
    }
}
