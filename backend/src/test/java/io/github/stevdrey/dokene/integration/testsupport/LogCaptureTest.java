// ABOUTME: Proves LogCapture can be read while other threads keep logging, and attributes each event to its thread.
// ABOUTME: Uses a non-additive logger with no other appender, so nothing but the capture touches the events.
package io.github.stevdrey.dokene.integration.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LogCaptureTest {
    private static final int BACKGROUND_EVENTS = 20_000;

    private final Logger logger = (Logger) LoggerFactory.getLogger(LogCaptureTest.class);
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void isolateLogger() {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
    }

    @AfterEach
    void restoreLogger() {
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test
    void readsWhileAnotherThreadKeepsLoggingAndScopesTextToTheReadingThread() throws InterruptedException {
        try (LogCapture capture = new LogCapture(logger)) {
            logger.warn("request-thread-marker");
            CountDownLatch producing = new CountDownLatch(1);
            AtomicReference<Throwable> producerFailure = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    for (int i = 0; i < BACKGROUND_EVENTS; i++) {
                        logger.warn("background-marker {}", i);
                        producing.countDown();
                    }
                } catch (Throwable failure) {
                    producerFailure.set(failure);
                }
            }, "log-capture-background");
            producer.start();
            assertThat(producing.await(10, TimeUnit.SECONDS)).isTrue();

            Thread reader = Thread.currentThread();
            do {
                // Any of these used to iterate the live list and could throw ConcurrentModificationException.
                capture.events();
                capture.text();
                capture.textFrom(reader);
                capture.warningsFrom(LogCaptureTest.class);
            } while (producer.isAlive());
            producer.join();

            assertThat(producerFailure.get()).isNull();
            assertThat(capture.events()).hasSize(BACKGROUND_EVENTS + 1);
            assertThat(capture.warningsFrom(LogCaptureTest.class)).hasSize(BACKGROUND_EVENTS + 1);
            assertThat(capture.textFrom(reader))
                    .contains("request-thread-marker")
                    .doesNotContain("background-marker");
            assertThat(capture.text()).contains("background-marker " + (BACKGROUND_EVENTS - 1));
        }
    }

    @Test
    void attributesEachEventToTheThreadThatLoggedItEvenWhenReadElsewhere() throws InterruptedException {
        try (LogCapture capture = new LogCapture(logger)) {
            Thread first = new Thread(() -> logger.info("first-producer-marker"), "log-capture-first");
            Thread second = new Thread(() -> logger.info("second-producer-marker"), "log-capture-second");
            first.start();
            second.start();
            first.join();
            second.join();

            // Read on this thread: a thread name resolved only now would name this thread, not the producer.
            assertThat(capture.textFrom(first))
                    .contains("first-producer-marker")
                    .doesNotContain("second-producer-marker");
            assertThat(capture.textFrom(second))
                    .contains("second-producer-marker")
                    .doesNotContain("first-producer-marker");
            assertThat(capture.textFrom(Thread.currentThread())).isEmpty();
        }
    }
}
