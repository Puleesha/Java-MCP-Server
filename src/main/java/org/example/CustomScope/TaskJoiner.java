package org.example.CustomScope;

import org.example.RepoAnalyser;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

public class TaskJoiner<T> implements StructuredTaskScope.Joiner<T, T> {

    private final RepoAnalyser repoAnalyser;

    public TaskJoiner(RepoAnalyser repoAnalyser) {
        this.repoAnalyser = repoAnalyser;
    }

    /**
     * This method prevents creation of new tasks after deadlines when creating new tasks.
     *
     * @param   subtask The task to be executed
     *
     * @return  A boolean indicating whether to cancel the scope
     */
    @Override
    public boolean onFork(Subtask<? extends T> subtask) {
        return repoAnalyser.isLimitReached();
    }

    /**
     * This method prevents creation of new tasks after deadlines after a subtask was completed.
     *
     * @param   subtask The task that was completed
     *
     * @return  A boolean indicating whether to cancel the scope
     */
    @Override
    public boolean onComplete(Subtask<? extends T> subtask) {
        return repoAnalyser.isLimitReached();
    }

    @Override
    public T result() {
        return null;
    }
}

