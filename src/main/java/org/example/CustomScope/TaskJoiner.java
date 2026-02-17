package org.example.CustomScope;

import java.time.Instant;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskJoiner<T> implements StructuredTaskScope.Joiner<T, T> {

    private final int limit;
    private final Instant deadline;
    private final AtomicInteger count = new AtomicInteger();

    public TaskJoiner(int limit, Instant deadline) {
        this.limit = limit;
        this.deadline = deadline;
    }

    /**
     * This method prevents creation of new tasks after deadlines of either limit or time are passed.
     *
     * @param   subtask The task to be executed
     *
     * @return  A boolean indicating whether to cancel the scope
     */
    @Override
    public boolean onFork(Subtask<? extends T> subtask) {
        return count.get() >= limit || Instant.now().isAfter(deadline);
    }

    @Override
    public boolean onComplete(Subtask<? extends T> subtask) {
        if (subtask.state() == Subtask.State.SUCCESS)
            if (count.incrementAndGet() >= limit)
                return false;

        return Instant.now().isBefore(deadline);
    }

    @Override
    public T result() {
        return null; // or aggregate if needed
    }
}

