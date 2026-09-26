package de.tstieh.stoneintelligence.stoneai.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * Runs the model calls of a long document side by side - a 500-page book is 150 sections, and
 * one after the other that is hours of waiting on the network.
 *
 * <p>Three properties the callers rely on. Results come back in item order, so the same document
 * yields the same notes however the calls happen to finish. Everything the caller learns - each
 * finished item, the question whether to start the next one - happens on the calling thread, so
 * a progress sink that throws to cancel the job cancels it here, and no caller state needs a
 * lock. And the first failure ends the run: nothing new is started, what is still running is
 * interrupted, and the failure travels up unchanged (a quota that ran out stays a quota that ran
 * out, and the job waits for it instead of failing).
 */
public final class BoundedParallel {

    private BoundedParallel() {
    }

    /**
     * @param count    how many items there are, numbered {@code 0..count-1}
     * @param parallel how many may run at the same time (at least one)
     * @param task     the work for one item; runs on a worker thread
     * @param mayStart asked on the calling thread before each item starts; once it says no, no
     *                 further item starts and the ones not started stay {@code null} in the result
     * @param onDone   told on the calling thread about each finished item, in the order they finish
     * @return the results by item, {@code null} for items that were never started
     */
    public static <T> List<T> run(int count, int parallel, IntFunction<T> task, IntPredicate mayStart,
                                  BiConsumer<Integer, T> onDone) {
        Object[] results = new Object[count];
        if (count == 0) {
            return List.of();
        }
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CompletionService<Finished<T>> completions = new ExecutorCompletionService<>(pool);
        List<Future<Finished<T>>> running = new ArrayList<>();
        try {
            int next = 0;
            int inFlight = 0;
            boolean open = true;
            while (true) {
                while (open && inFlight < Math.max(1, parallel) && next < count) {
                    if (!mayStart.test(next)) {
                        open = false;
                        break;
                    }
                    int index = next++;
                    running.add(completions.submit(() -> new Finished<>(index, task.apply(index))));
                    inFlight++;
                }
                if (inFlight == 0) {
                    break;
                }
                Finished<T> finished = take(completions);
                inFlight--;
                results[finished.index()] = finished.result();
                onDone.accept(finished.index(), finished.result());
            }
        } finally {
            running.forEach(future -> future.cancel(true));
            pool.shutdownNow();
        }
        @SuppressWarnings("unchecked")
        List<T> ordered = (List<T>) Arrays.asList(results);
        return ordered;
    }

    private record Finished<T>(int index, T result) {
    }

    private static <T> Finished<T> take(CompletionService<Finished<T>> completions) {
        try {
            return completions.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("beim Warten auf die KI-Aufrufe unterbrochen", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
