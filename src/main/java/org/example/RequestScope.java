package org.example;

import org.example.MockTool.RepoAnalyser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

public class RequestScope {
    private static final Logger log = LoggerFactory.getLogger(RequestScope.class);
    RepoAnalyser repoAnalyser = new RepoAnalyser();
    // Bcz of the way the Docker MCP gateway works (short bursts of images running) the singleton might be redundant.

    public String analyseRepoTool(int limit) {
        Queue<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java");
        long startTime = System.currentTimeMillis();

        // TODO: Try to change executor to try-with-resources
        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
        List<Future<?>> futures = new ArrayList<>();

        for (Path path: filePaths) {
            futures.add(executorService.submit(() -> {
                try {
                    repoAnalyser.analyzeFile(path, limit, startTime);
                } catch (IOException | InterruptedException e) {
                    log.error("Server error", e);
                    e.printStackTrace();
                }
            }));
        }

        for (Future<?> future: futures) {
            try {
                // Prevent any leaked threads from lingering after completion
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        // Reasons for removing countdown latch:
        // 1. latch was placed here so it'll get called only if all futures are done
        // 2. Therefore, blocking here doesn't really do anything
        // 3. Also, the latch can decrease performance if the available TODOs < limit, where it just blocks for 5s.

        executorService.shutdown();
        log.info("Baseline tool called with limit of = {} TODOs", limit);

        return "TODOs found = " + repoAnalyser.getTODOs() +
                ". Scanned " + repoAnalyser.getFileCount() + " files";
    }

    public int getTodoCount() {
        return repoAnalyser.getTodoCount();
    }
}
