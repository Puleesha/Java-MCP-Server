package org.example.MockTool.ModelRecords;

import java.nio.file.Path;

public record FileStats(Path path, long lineCount, long todoCount) {}
