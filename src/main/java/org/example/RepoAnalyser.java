package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class represents a repository analyser, where it can access files within a folder and return the TODOs present.
 *
 * @author  Puleesha Vilhan
 * @since   05/12/2025
 */
public class RepoAnalyser {
    private static final Logger log = LoggerFactory.getLogger(RepoAnalyser.class);

    // Maximum time a request can take
    private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(5);
    // Maximum length of the response
    private static final int REQUEST_LENGTH_LIMIT = 2000;

    // Counter for the total number of TODOs found
    private final AtomicInteger todoCount = new AtomicInteger(0);
    // Counter for the total number of files analyzed
    private final AtomicInteger fileCount = new AtomicInteger(0);
    // Semaphore to limit the number of concurrent file analysis tasks
    private final Semaphore connections = new Semaphore(100);

    // List to store the TODOs found
    private final ArrayList<String> TODOs;
    // Deadline for the request
    long deadline;
    // Task limit for the request
    int taskLimit;

    /**
     * Constructor for the RepoAnalyser class.
     *
     * @param limit The maximum number of tasks that can be processed
     */
    public RepoAnalyser(int limit) {
        deadline = System.nanoTime() + REQUEST_DEADLINE.toNanos();
        taskLimit = limit;
        TODOs = new ArrayList<>();
    }

    /**
     * Analyzes the repository located at the specified folder path.
     *
     * @param folderPath The path to the folder containing the repository
     * @return A list of file paths to be analyzed
     */
    public List<Path> analyzeRepository(String folderPath) {
        Path rootDir = Paths.get(folderPath);

        List<Path> filesToAnalyze = new LinkedList<>();
        try {
            connections.acquire();
            filesToAnalyze = discoverFiles(rootDir);
        }
        catch (IOException | InterruptedException e) {
            log.error("Error while analyzing files", e);
        }
        finally {
            connections.release();
        }

        return filesToAnalyze;
    }

    /**
     * Discovers all regular files within the specified directory.
     *
     * @param rootDir The root directory to start the search
     * @return A list of file paths
     * @throws IOException If an I/O error occurs
     */
    private List<Path> discoverFiles(Path rootDir) throws IOException {
        List<Path> result = new LinkedList<>();

        try (var stream = Files.walk(rootDir)) {
            stream.filter(Files::isRegularFile)
                    .sorted()   // Return the same set of files for every function call
                    .forEach(result::add);
        }

        return result;
    }

    /**
     * Analyzes the specified file for TODO comments.
     *
     * @param file The path of the file to analyze
     * @throws IOException If the reader throws an error
     * @throws InterruptedException If the Thread.sleep() is interrupted
     */
    public void analyzeFile(Path file) throws IOException, InterruptedException {
        try {
            connections.acquire();

            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                fileCount.incrementAndGet();

                if (Thread.currentThread().isInterrupted())
                    return;

                while (((line = reader.readLine()) != null) && !Thread.currentThread().isInterrupted())
                    if (line.contains("TODO"))
                        addTODO(line);
            }
        }
        finally {
            connections.release();
        }
    }

    /**
     * Adds a TODO comment to the list if the limits are not reached.
     *
     * @param line The TODO comment to add
     */
    private synchronized void addTODO(String line) {
        String newTask = line.replace("//", " ");

        if (!(todoCount.get() >= taskLimit ||
                getResponseLength() + newTask.length() > REQUEST_LENGTH_LIMIT ||
                System.nanoTime() > deadline)) {
            TODOs.add(newTask);
            todoCount.incrementAndGet();
        }
    }

    /**
     * Calculates the total length of all TODO comments in the list.
     *
     * @return The total length of all TODO comments
     */
    private synchronized int getResponseLength() {
        int totalLength = 0;

        for (String s : TODOs)
            totalLength += s.length();

        return totalLength;
    }

    /**
     * Retrieves the total number of files analyzed.
     *
     * @return The number of files analyzed
     */
    public int getFileCount() {
        return fileCount.get();
    }

    /**
     * Retrieves the total number of TODOs found.
     *
     * @return The number of TODOs found
     */
    public int getTodoCount() {
        return todoCount.get();
    }

    /**
     * Retrieves the list of TODOs found.
     *
     * @return A string representation of the list of TODOs
     */
    public synchronized String getTODOs() {
        return TODOs.toString();
    }

    /**
     * Checks if any of the limits (task limit, response length, time limit) have been reached.
     *
     * @return Boolean indicating if any limits have been reached
     */
    public boolean isLimitReached() {
        return (todoCount.get() >= taskLimit) || (System.nanoTime() > deadline);
    }
}