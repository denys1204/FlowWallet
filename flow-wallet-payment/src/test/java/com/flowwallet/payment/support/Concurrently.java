package com.flowwallet.payment.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/**
 * Runs one task on several threads released together once all of them are waiting, so they reach the database at the
 * same moment rather than one after another. A race test asserts what holds under any interleaving, never which thread
 * won.
 */
public final class Concurrently {
    private static final long TIMEOUT_SECONDS = 60;

    private Concurrently() {
    }

    /**
     * @param task called with the thread's index, from 0
     * @return each thread's result, in index order
     * @throws AssertionError if a task threw, or the threads did not finish within a minute (a deadlock)
     */
    public static <T> List<T> run(int threads, IntFunction<T> task) {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }));
            }
            if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("The threads did not all start within " + TIMEOUT_SECONDS + " s");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } catch (ExecutionException e) {
            throw new AssertionError("A concurrent task failed", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("The concurrent tasks did not finish within " + TIMEOUT_SECONDS + " s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the concurrent tasks", e);
        } finally {
            executor.shutdownNow();
        }
    }
}
