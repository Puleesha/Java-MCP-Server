package org.example.MockTool;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;

/**
 *  This class represent a repository analyser, where it can access files within a folder and return the number of lines and TODOs present.
 *
 * @author  Puleesha Vilhan
 * @since   04/12/2025
 * @version 1.0.0
 */
public class RepoAnalyser {

    // Simple per-file stats
    public static final class FileStats {
        public final Path path;
        public final long lineCount;
        public final long todoCount;

        public FileStats(Path path, long lineCount, long todoCount) {
            this.path = path;
            this.lineCount = lineCount;
            this.todoCount = todoCount;
        }
    }

    // Aggregated summary for the whole repo
    public static final class RepoSummary {
        public final int filesAnalyzed;
        public final long totalLines;
        public final long totalTodos;
        public final List<FileStats> perFile;

        public RepoSummary(int filesAnalyzed, long totalLines, long totalTodos, List<FileStats> perFile) {
            this.filesAnalyzed = filesAnalyzed;
            this.totalLines = totalLines;
            this.totalTodos = totalTodos;
            this.perFile = perFile;
        }
    }

    /**
     * Analyze a repository-like directory:
     *  1) Sequentially discover up to maxFiles matching the extensions.
     *  2) In parallel, compute line count and TODO count per file.
     *  3) Aggregate into a RepoSummary.
     */
    public static RepoSummary analyzeRepo(Path rootDir, int maxFiles, List<String> extensions)
            throws IOException, InterruptedException, ExecutionException {

        // 1. Sequential discovery stage
        List<Path> filesToAnalyze = discoverFiles(rootDir, maxFiles, extensions);

        // 2. Parallel per-file analysis using structured concurrency
        List<FileStats> perFileStats = new ArrayList<>(filesToAnalyze.size());

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<FileStats>> subtasks = new ArrayList<>();

            for (Path file : filesToAnalyze) {
                subtasks.add(scope.fork(() -> analyzeFile(file)));
            }

            // Wait for all to finish or fail
            scope.join();
            scope.throwIfFailed();

            for (var subtask : subtasks) {
                perFileStats.add(subtask.get());
            }
        }

        // 3. Sequential aggregation
        long totalLines = 0;
        long totalTodos = 0;

        for (FileStats fs : perFileStats) {
            totalLines += fs.lineCount;
            totalTodos += fs.todoCount;
        }

        return new RepoSummary(perFileStats.size(), totalLines, totalTodos, perFileStats);
    }

    private static List<Path> discoverFiles(Path rootDir,
                                            int maxFiles,
                                            List<String> extensions) throws IOException {
        List<Path> result = new ArrayList<>();

        try (var stream = Files.walk(rootDir)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> hasAllowedExtension(path, extensions))
                    .limit(maxFiles)
                    .forEach(result::add);
        }

        return result;
    }

    private static boolean hasAllowedExtension(Path path, List<String> extensions) {
        String name = path.getFileName().toString().toLowerCase();
        for (String ext : extensions) {
            if (name.endsWith(ext.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static FileStats analyzeFile(Path file) throws IOException {
        long lineCount = 0;
        long todoCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;

                if (line.contains("TODO"))
                    todoCount++;
            }
        }

        return new FileStats(file, lineCount, todoCount);
    }

    // Optional quick manual test
    public void analyseRepoTool(int maxFiles) throws Exception {
        Path root = Paths.get("./sample-repo");
//        int maxFiles = 100;
        List<String> exts = List.of(".java", ".kt", ".rs", ".ts", ".js");

        RepoSummary summary = analyzeRepo(root, maxFiles, exts);

        System.out.println("Files analyzed : " + summary.filesAnalyzed);
        System.out.println("Total lines    : " + summary.totalLines);
        System.out.println("Total TODOs    : " + summary.totalTodos);
    }
}
