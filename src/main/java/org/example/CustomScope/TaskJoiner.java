package org.example.CustomScope;

import java.time.Instant;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskJoiner<T> implements StructuredTaskScope.Joiner<T, T> {

    private final int limit;
    private final Instant deadline;
    private final AtomicInteger filesFound = new AtomicInteger();

    public TaskJoiner(int limit, Instant deadline) {
        this.limit = limit;
        this.deadline = deadline;
    }

    // TODO: Update this method too when checking for response length
    /**
     * This method prevents creation of new tasks after deadlines of either limit or time are passed.
     *
     * @param   subtask The task to be executed
     *
     * @return  A boolean indicating whether to cancel the scope
     */
    @Override
    public boolean onFork(Subtask<? extends T> subtask) {
        return filesFound.get() >= limit || Instant.now().isAfter(deadline);
    }

    @Override
    public boolean onComplete(Subtask<? extends T> subtask) {
        if (subtask.state() == Subtask.State.SUCCESS)
            if (filesFound.incrementAndGet() >= limit)
                return false;

        return Instant.now().isBefore(deadline);
    }

    @Override
    public T result() {
        if (filesFound.get() == 0)
            throw new IllegalStateException("No files scanned. Errors thrown for all subtasks.");

        return null;
    }
}

