package org.example;

/**
 * This class acts as a data object to return the tool call's results
 *
 * @param   todoCount The number of TODOs found
 * @param   filesScanned The number of files scanned
 * @param   activeTasks The number of active or queued tasks for that request during completion
 * @param   todoTasks The tasks that were fetched from the mock repository
 */
public record RequestStats(int todoCount, int filesScanned, int activeTasks, String todoTasks) {}
