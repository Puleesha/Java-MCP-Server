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
 *  This class represent a repository analyser, where it can access files within a folder and return the number of lines and TODOs present.
 *
 * @author  Puleesha Vilhan
 * @since   05/12/2025
 */
public class RepoAnalyser {
    private static final Logger log = LoggerFactory.getLogger(RepoAnalyser.class);

    private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(5);
    private static final int REQUEST_LENGTH_LIMIT = 500;

    private final AtomicInteger todoCount = new AtomicInteger(0);
    private final AtomicInteger fileCount = new AtomicInteger(0);
    private final Semaphore connections = new Semaphore(100);

    private final ArrayList<String> TODOs;
    long deadlineNanos;
    int taskLimit;

    public RepoAnalyser(int limit) {
        deadlineNanos = System.nanoTime() + REQUEST_DEADLINE.toNanos();
        taskLimit = limit;
        TODOs = new ArrayList<>();
    }

    /**
     * List all the files that have to be analysed
     *
     * @param   folderPath The folder path of the repository
     *
     * @return  A List of the paths of all files
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
     * This method analyses the file and will be called sequentially or concurrently based on the server variant
     *
     * @param   file The path of the file
     *
     * @throws  IOException If the reader throws and error
     * @throws  InterruptedException If the Thread.sleep() is interrupted
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
     * Add a task to the array list
     *
     * @param   line The comment to be added
     */
    private synchronized void addTODO(String line)  {
        todoCount.incrementAndGet();
        TODOs.add(line.replace("//", " "));
    }

    /**
     * Get the total length of all tasks currently in the array list
     *
     * @return  length of all array list tasks
     */
    private synchronized int getResponseLength() {
        int totalLength = 0;

        for (String s : TODOs)
            totalLength += s.length();

        return totalLength;
    }

    public int getFileCount() {
        return fileCount.get();
    }

    public int getTodoCount() {
        return todoCount.get();
    }

    public synchronized String getTODOs() {
        return TODOs.toString();
    }

    /**
     * Checks if the response length or the task limit or time limit is reached
     *
     * @return  Boolean indicating if any of the limits were reached
     */
    public boolean isLimitReached() {
        return  (todoCount.get() >= taskLimit) ||
                (getResponseLength() >= REQUEST_LENGTH_LIMIT) ||
                (System.nanoTime() > deadlineNanos);
    }
}
