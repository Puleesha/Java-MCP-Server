package org.example.MockTool.ModelRecords;

import java.util.List;

public record RepoSummary(int filesAnalyzed, long totalLines, long totalTodos, List<FileStats> perFile) {}
