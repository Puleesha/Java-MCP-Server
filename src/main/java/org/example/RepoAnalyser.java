package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
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
    private ArrayList<String> TODOs = new ArrayList<>();

    /**
     * List all the files that have to be analysed
     *
     * @param   folderPath The folder path of the repository
     * @param   typeExtension The language of the files to be analysed by the server
     *
     * @return  A List of the paths of all files
     */
    public List<Path> analyzeRepository(String folderPath, String typeExtension) {

        Path rootDir = Paths.get(folderPath);List<String> fileType = List.of(typeExtension);

        List<Path> filesToAnalyze = new LinkedList<>();
        try {
            filesToAnalyze = discoverFiles(rootDir, fileType);
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        return filesToAnalyze;
    }

    private List<Path> discoverFiles(Path rootDir,
                                     List<String> extensions) throws IOException {
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
        for (String ext : extensions) {
            if (name.endsWith(ext.toLowerCase())) {
                return true;
            }
        }
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

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            fileCount.incrementAndGet();

            // Simulate network latency
            Thread.sleep(new Random().nextInt(800));

            while (((line = reader.readLine()) != null) && todoCount.get() < limit)
                if (line.contains("TODO"))
                    addTODO(line, latch);
        }
    }

    private synchronized void addTODO(String line, CountDownLatch latch)  {
        latch.countDown();
        todoCount.incrementAndGet();
        TODOs.add(line.replace("//", " "));
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
