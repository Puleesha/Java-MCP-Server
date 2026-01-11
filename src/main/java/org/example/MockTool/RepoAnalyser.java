package org.example.MockTool;

import org.example.MockTool.ModelRecords.FileStats;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 *  This class represent a repository analyser, where it can access files within a folder and return the number of lines and TODOs present.
 *
 * @author  Puleesha Vilhan
 * @since   05/12/2025
 * @version 1.1.0
 */
public class RepoAnalyser {

    private int fileCount = 0;

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
    private FileStats analyzeFile(Path file) throws IOException {
        long lineCount = 0;
        long todoCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;

            // Simulate file reading errors every 20 files to test fault containment
            if (fileCount % 20 == 0)
                throw new IOException("Could not read the file");

            while ((line = reader.readLine()) != null) {
                lineCount++;

                if (line.contains("TODO"))
                    todoCount++;

                fileCount++;
            }
        }

        return new FileStats(file, lineCount, todoCount);
    }
}
