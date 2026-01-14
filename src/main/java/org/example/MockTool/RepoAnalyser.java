package org.example.MockTool;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *  This class represent a repository analyser, where it can access files within a folder and return the number of lines and TODOs present.
 *
 * @author  Puleesha Vilhan
 * @since   05/12/2025
 */
public class RepoAnalyser {

    // TODO: Change this if singleton is used
    private final AtomicInteger lineCount = new AtomicInteger(0);
    private final AtomicInteger todoCount = new AtomicInteger(0);
    private final AtomicInteger fileCount = new AtomicInteger(0);

    private ArrayList<String> TODOs = new ArrayList<>();
    /**
     * List all the files that have to be analysed
     *
     * @param   folderPath The folder path of the repository
     * @param   typeExtension The language of the files to be analysed by the server
     * @param   maxFiles The max number of files to be scanned
     *
     * @return  A list of the paths of all files
     */
    public List<Path> analyzeRepository(String folderPath, String typeExtension, int maxFiles) {

        Path rootDir = Paths.get(folderPath);
        List<String> fileType = List.of(typeExtension);
        //  Send one of these as a parameter
        //   ".java", ".kt", ".rs", ".ts", ".js"

        List<Path> filesToAnalyze = new ArrayList<>();
        try {
            filesToAnalyze = discoverFiles(rootDir, maxFiles, fileType);
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        return filesToAnalyze;
    }

    private List<Path> discoverFiles(Path rootDir,
                                     int maxFiles,
                                     List<String> extensions) throws IOException {
        List<Path> result = new ArrayList<>();

        try (var stream = Files.walk(rootDir)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> hasAllowedExtension(path, extensions))
                    .sorted()   // Return the same set of files for every function call
                    .limit(maxFiles)
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
     *
     * @return  The number of lines and TODOs in a Java record format
     *
     * @throws  IOException If the reader throws and error
     */
    public void analyzeFile(Path file) throws IOException {

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;

            // TODO: Add a Thread sleep

            while ((line = reader.readLine()) != null) {
                lineCount.incrementAndGet();

                if (line.contains("TODO")) {
                    addTODO(line);
                    todoCount.incrementAndGet();
                }
                fileCount.incrementAndGet();
            }
        }
//        return new FileStats(file, lineCount, todoCount);
    }

    private synchronized void addTODO(String line) {
        TODOs.add(line);
    }

    public AtomicInteger getLineCount() {
        return lineCount;
    }

    public AtomicInteger getTodoCount() {
        return todoCount;
    }

    public String getTODOs() {
        return TODOs.toString();
    }
}
