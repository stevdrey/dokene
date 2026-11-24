// ABOUTME: Captures every Logback event under the root logger for one test, including attached throwables.
// ABOUTME: Lets the secret and webhook tests prove a WARN line was written and that no canary reached any log text.
package io.github.stevdrey.dokene.integration.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;

/**
 * Attach only after any Spring Boot context has started: Boot's logging setup resets Logback and drops appenders,
 * so startup output is captured with {@code OutputCaptureExtension} instead. An {@code ApplicationContextRunner}
 * does not reset Logback, so a capture opened before {@code run} sees that context's startup lines.
 */
public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            // Thread name and message resolve lazily; fix them on the logging thread, not on the reading one.
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    public LogCapture() {
        this((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME));
    }

    LogCapture(Logger logger) {
        this.logger = logger;
        appender.start();
        logger.addAppender(appender);
    }

    /** A snapshot taken under the appender's lock, so background threads may keep logging while a test reads. */
    public List<ILoggingEvent> events() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    public List<ILoggingEvent> warningsFrom(Class<?> logger) {
        List<ILoggingEvent> matched = new ArrayList<>();
        for (ILoggingEvent event : events()) {
            if (event.getLevel() == Level.WARN && event.getLoggerName().equals(logger.getName())) {
                matched.add(event);
            }
        }
        return matched;
    }

    /** Every formatted message plus every message in every attached cause chain, joined for a boolean search. */
    public String text() {
        return text(event -> true);
    }

    /**
     * Like {@link #text()}, but only for events logged on {@code thread}: background threads of other cached
     * contexts (pool housekeeping, scheduled sweeps) log into the same root logger.
     */
    public String textFrom(Thread thread) {
        String name = thread.getName();
        return text(event -> name.equals(event.getThreadName()));
    }

    private String text(Predicate<ILoggingEvent> included) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : events()) {
            if (!included.test(event)) {
                continue;
            }
            text.append(event.getFormattedMessage()).append('\n');
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
