package org.example.MockTool;

import org.example.MockTool.ModelRecords.FileStats;
import org.example.MockTool.ModelRecords.RepoSummary;

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

    /**
     * Analyze a repository-like directory:
     *  1) Sequentially discover up to maxFiles matching the extensions.
     *  2) In parallel, compute line count and TODO count per file.
     *  3) Aggregate into a RepoSummary.
     */
    public RepoSummary analyzeRepo(Path rootDir, int maxFiles, List<String> extensions) {

        List<Path> filesToAnalyze = new ArrayList<>();
        try {
            // 1. Sequential discovery stage
            filesToAnalyze = discoverFiles(rootDir, maxFiles, extensions);
        }
        catch (IOException e) {
            e.printStackTrace();
        }

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
        catch ( InterruptedException  | ExecutionException e) {
            e.printStackTrace();
        }

        // 3. Sequential aggregation
        long totalLines = 0;
        long totalTodos = 0;

        for (FileStats fs : perFileStats) {
            totalLines += fs.lineCount();
            totalTodos += fs.todoCount();
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
    public void analyseRepoTool(int maxFiles, String folderPath) {
        Path root = Paths.get(folderPath);
//        int maxFiles = 100;
        List<String> fileTypes = List.of(".java", ".kt", ".rs", ".ts", ".js");

        RepoSummary summary = analyzeRepo(root, maxFiles, fileTypes);

//        System.out.println("Files analyzed : " + summary.filesAnalyzed);
//        System.out.println("Total lines    : " + summary.totalLines);
//        System.out.println("Total TODOs    : " + summary.totalTodos);
    }
}
