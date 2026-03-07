package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *  This class represent a repository analyser, where it can access files within a folder and return the number of lines and TODOs present.
 *
 * @author  Puleesha Vilhan
 * @since   05/12/2025
 */
public class RepoAnalyser {

    private final AtomicInteger todoCount = new AtomicInteger(0);
    private final AtomicInteger fileCount = new AtomicInteger(0);
    private final Semaphore connections = new Semaphore(100);
    private final Semaphore mutex = new Semaphore(1);
    private ArrayList<String> TODOs = new ArrayList<>();
    private static final int REQUEST_LENGTH_LIMIT = 500;

    /**
     * List all the files that have to be analysed
     *
     * @param   folderPath The folder path of the repository
     * @param   fileTypes The language of the files to be analysed by the server
     *
     * @return  A List of the paths of all files
     */
    public List<Path> analyzeRepository(String folderPath, List<String> fileTypes) {
        Path rootDir = Paths.get(folderPath);

        List<Path> filesToAnalyze = new LinkedList<>();
        try {
            connections.acquire();
            filesToAnalyze = discoverFiles(rootDir, fileTypes);
        }
        catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        finally {
            connections.release();
        }

        return filesToAnalyze;
    }

    private List<Path> discoverFiles(Path rootDir, List<String> extensions) throws IOException {
        List<Path> result = new LinkedList<>();

        try (var stream = Files.walk(rootDir)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> hasAllowedExtension(path, extensions))
                    .sorted()   // Return the same set of files for every function call
                    .forEach(result::add);
        }

        return result;
    }

    private boolean hasAllowedExtension(Path path, List<String> extensions) {
        String name = path.getFileName().toString().toLowerCase();
        for (String ext : extensions)
            if (name.endsWith(ext.toLowerCase()))
                return true;

        return false;
    }

    /**
     * This method analyses the file and will be called sequentially or concurrently based on the server variant
     *
     * @param   file The path of the file
     * @param   limit The number of TODOs to be retrieved
     * @param   latch Countdown latch tracking the number of TODOs found
     *
     * @throws  IOException If the reader throws and error
     * @throws  InterruptedException If the Thread.sleep() is interrupted
     */
    public void analyzeFile(Path file, int limit, CountDownLatch latch) throws IOException, InterruptedException {
        try {
            connections.acquire();

            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                fileCount.incrementAndGet();

                if (Thread.currentThread().isInterrupted())
                    return;

                while (
                    ((line = reader.readLine()) != null) &&
                    todoCount.get() < limit && !Thread.currentThread().isInterrupted() &&
                    (getResponseLength() + line.length()) < REQUEST_LENGTH_LIMIT
                )
                    if (line.contains("TODO"))
                        addTODO(line, latch);
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
     * @param   latch The countdown latch
     */
    private synchronized void addTODO(String line, CountDownLatch latch)  {
        try {
            mutex.acquire();
            if (latch != null)
                latch.countDown();

            todoCount.incrementAndGet();
            TODOs.add(line.replace("//", " "));
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            mutex.release();
        }
    }

    /**
     * Get the total length of all tasks currently in the array list
     *
     * @return  length of all array list tasks
     */
    private int getResponseLength() {
        int totalLength = 0;

        try {
            mutex.acquire();
            for (String s : TODOs)
                totalLength += s.length();
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            mutex.release();
        }

        return totalLength;
    }

    public int getFileCount() {
        return fileCount.get();
    }

    public int getTodoCount() {
        return todoCount.get();
    }

    public String getTODOs() {
        return TODOs.toString();
    }
}
