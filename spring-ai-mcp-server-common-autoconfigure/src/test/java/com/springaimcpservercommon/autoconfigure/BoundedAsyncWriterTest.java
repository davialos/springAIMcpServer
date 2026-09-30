package com.springaimcpservercommon.autoconfigure;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedAsyncWriterTest {

    @Test
    void writesRunOffTheCallingThread() throws Exception {
        try (BoundedAsyncWriter writer = new BoundedAsyncWriter(2, "test.writer", LoggerFactory.getLogger("test"))) {
            CountDownLatch done = new CountDownLatch(1);
            Thread caller = Thread.currentThread();
            AtomicInteger sameThread = new AtomicInteger();

            writer.submit("write", () -> {
                if (Thread.currentThread() == caller) {
                    sameThread.incrementAndGet();
                }
                done.countDown();
            });

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(sameThread).hasValue(0);
        }
    }

    @Test
    void aFullBulkheadDropsTheWriteInsteadOfBlockingTheCaller() throws Exception {
        try (BoundedAsyncWriter writer = new BoundedAsyncWriter(1, "test.writer", LoggerFactory.getLogger("test"))) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();

            writer.submit("first", () -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ran.incrementAndGet();
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            writer.submit("second", ran::incrementAndGet);
            release.countDown();
            Thread.sleep(200);

            assertThat(ran).hasValue(1);
        }
    }

    @Test
    void aFailingWriteNeverReachesTheCallerAndFreesItsSlot() throws Exception {
        try (BoundedAsyncWriter writer = new BoundedAsyncWriter(1, "test.writer", LoggerFactory.getLogger("test"))) {
            CountDownLatch failed = new CountDownLatch(1);
            writer.submit("boom", () -> {
                failed.countDown();
                throw new IllegalStateException("store down");
            });
            assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);

            CountDownLatch next = new CountDownLatch(1);
            writer.submit("after", next::countDown);

            assertThat(next.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
