package com.springaimcpservercommon.jfranalyzer.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A small application with one known hot spot per kind of cost, so tests can check the analyzer points at exactly
 * these methods. Lives in its own package: the tests pass it as the package to attribute to.
 */
public final class SampleWorkload {

    private static volatile long sink;
    /** Published so the JIT cannot scalar-replace the allocations away (escape analysis). */
    private static volatile byte[] lastChunk;
    private static final Object MONITOR = new Object();
    private static final ReentrantLock LOCK = new ReentrantLock();
    /** Kept reachable so the old-object sampler and the heap see a growing live set. */
    private static final List<byte[]> RETAINED = new ArrayList<>();

    private SampleWorkload() {
    }

    /**
     * Runs every hot spot in parallel for about {@code millis}.
     *
     * @param millis how long
     * @throws InterruptedException if interrupted
     */
    public static void run(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        List<Thread> threads = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        threads.add(worker("cpu-burner", start, deadline, CpuBurner::burn));
        threads.add(worker("allocator", start, deadline, Allocator::allocate));
        for (int i = 0; i < 3; i++) {
            threads.add(worker("monitor-" + i, start, deadline, Contender::enterMonitor));
            threads.add(worker("lock-" + i, start, deadline, Contender::takeLock));
        }
        threads.add(worker("thrower", start, deadline, Thrower::throwAndCatch));
        threads.forEach(Thread::start);
        start.countDown();
        Thread.sleep(millis / 2);
        System.gc();
        for (Thread t : threads) {
            t.join();
        }
    }

    private static Thread worker(String name, CountDownLatch start, long deadline, Runnable step) {
        Thread t = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            while (System.nanoTime() < deadline) {
                step.run();
            }
        }, name);
        t.setDaemon(true);
        return t;
    }

    /** CPU hot spot. */
    static final class CpuBurner {
        static void burn() {
            long acc = 0;
            for (int n = 2; n < 20_000; n++) {
                acc += isPrime(n) ? n : 0;
            }
            sink += acc;
        }

        static boolean isPrime(int n) {
            for (int d = 2; d * d <= n; d++) {
                if (n % d == 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Allocation hot spot. */
    static final class Allocator {
        static void allocate() {
            for (int i = 0; i < 40; i++) {
                byte[] chunk = new byte[16 * 1024];
                lastChunk = chunk;
            }
            synchronized (RETAINED) {
                if (RETAINED.size() < 2_000) {
                    RETAINED.add(new byte[8 * 1024]);
                }
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Lock contention hot spots: a synchronized block and a ReentrantLock, each held for a while. */
    static final class Contender {
        static void enterMonitor() {
            synchronized (MONITOR) {
                pause(15);
            }
        }

        static void takeLock() {
            LOCK.lock();
            try {
                pause(15);
            } finally {
                LOCK.unlock();
            }
        }

        /** Sleeps while holding the lock: waiters block, but the holder burns no CPU. */
        private static void pause(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Exception hot spot. */
    static final class Thrower {
        static void throwAndCatch() {
            try {
                fail();
            } catch (SampleException e) {
                sink += e.getMessage().length();
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        static void fail() {
            throw new SampleException("expected");
        }
    }

    /** An exception type in the sample package: its constructor must not be reported as the throw site. */
    static final class SampleException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SampleException(String message) {
            super(message);
        }
    }
}
