package org.example;

import org.example.MockTool.RepoAnalyser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class RequestScope {
    private static final Logger log = LoggerFactory.getLogger(RequestScope.class);

    public static String analyseRepoTool(int limit) {
        // Bcz of the way the Docker MCP gateway works (short bursts of images running) the singleton might be redundant.
        RepoAnalyser repoAnalyser = new RepoAnalyser();

        List<Path> filePaths = repoAnalyser.analyzeRepository("/app/MockRepository/Java", ".java", limit);

        for (Path path : filePaths) {
            try {
                repoAnalyser.analyzeFile(path);
            } catch (IOException e) {
                log.error("Server error", e);
                e.printStackTrace();
            }
        }

        log.info("Baseline tool called with limit of = {}", limit);

        return "Lines = " + repoAnalyser.getLineCount() +
                " and TODOs: " + repoAnalyser.getTODOs() +
                " Max Files: " + limit;
    }
}
