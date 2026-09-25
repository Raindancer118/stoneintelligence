package de.raindancer118.stoneai.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Lange Dokumente: 150 Abschnitte nacheinander dauern Stunden - mehrere Aufrufe gleichzeitig,
// aber nie mehr als erlaubt, und das Ergebnis in Dokumentreihenfolge.
class BoundedParallelTest {

    @Test
    @DisplayName("should return the results in item order, whatever order they finish in")
    void should_keepItemOrder() {
        List<Integer> results = BoundedParallel.run(5, 3, index -> {
            sleep((5 - index) * 20L);
            return index * 10;
        }, index -> true, (index, result) -> {
        });

        assertThat(results).containsExactly(0, 10, 20, 30, 40);
    }

    @Test
    @DisplayName("should run several items at once, but never more than allowed")
    void should_runConcurrently_withinTheLimit() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        BoundedParallel.run(12, 3, index -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(30);
            running.decrementAndGet();
            return index;
        }, index -> true, (index, result) -> {
        });

        assertThat(peak.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("should run one after the other when only one is allowed")
    void should_runSequentially_whenTheLimitIsOne() {
        List<Integer> started = new ArrayList<>();

        BoundedParallel.run(4, 1, index -> {
            synchronized (started) {
                started.add(index);
            }
            return index;
        }, index -> true, (index, result) -> {
        });

        assertThat(started).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("should ask before each start and leave the rest unstarted once told to stop")
    void should_stopStarting_whenTheGateCloses() {
        AtomicInteger done = new AtomicInteger();

        List<Integer> results = BoundedParallel.run(6, 1, index -> index, index -> done.get() < 2,
                (index, result) -> done.incrementAndGet());

        assertThat(results).containsExactly(0, 1, null, null, null, null);
    }

    @Test
    @DisplayName("should report every finished item on the calling thread")
    void should_reportOnTheCallingThread() {
        Thread caller = Thread.currentThread();
        List<Boolean> onCaller = new ArrayList<>();

        BoundedParallel.run(4, 2, index -> index, index -> true,
                (index, result) -> onCaller.add(Thread.currentThread() == caller));

        assertThat(onCaller).hasSize(4).containsOnly(true);
    }

    @Test
    @DisplayName("should pass on the first failure and not start anything after it")
    void should_propagateAFailure_andStopTheRest() {
        AtomicInteger started = new AtomicInteger();

        assertThatThrownBy(() -> BoundedParallel.run(10, 1, index -> {
            started.incrementAndGet();
            if (index == 2) {
                throw new IllegalStateException("kein Kontingent");
            }
            return index;
        }, index -> true, (index, result) -> {
        })).isInstanceOf(IllegalStateException.class).hasMessage("kein Kontingent");

        assertThat(started.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("should pass on a failure of the report, e.g. a cancelled job, and interrupt running items")
    void should_propagateAReportFailure_andInterruptTheRest() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);

        assertThatThrownBy(() -> BoundedParallel.run(3, 3, index -> {
            if (index == 0) {
                return 0;
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return index;
        }, index -> true, (index, result) -> {
            throw new IllegalStateException("abgebrochen");
        })).hasMessage("abgebrochen");

        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("should return an empty list for no items")
    void should_handleNoItems() {
        assertThat(BoundedParallel.run(0, 4, index -> index, index -> true, (index, result) -> {
        })).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
