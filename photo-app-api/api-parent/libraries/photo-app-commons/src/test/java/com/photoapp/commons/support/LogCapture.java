package com.photoapp.commons.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Attaches a Logback {@link ListAppender} to one class's logger so a test can assert what was
 * logged, at what level, and whether a stack trace came with it.
 *
 * <p>This exists because half of what {@code GlobalExceptionHandler} does is not visible in the
 * HTTP response. Two handlers can return an identical body while one logs at ERROR with a full
 * stack trace and the other at WARN without one - and the difference is the whole point of the
 * client-error handlers. A test that only asserted the status would pass either way.
 *
 * <p>Local to {@code photo-app-commons}: this module cannot depend on {@code
 * photo-app-test-support}, which depends on it. See {@code docs/TESTING.md} §1.
 *
 * <p>Use with try-with-resources - {@link #close()} detaches the appender and restores the
 * logger's previous level, so a test cannot leak either into the next one.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousLevel;

    public static LogCapture on(Class<?> type) {
        return new LogCapture(type);
    }

    private LogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        this.previousLevel = logger.getLevel();
        // The handler logs at WARN and ERROR; forcing TRACE means a stray root-logger
        // configuration in some other module cannot make this test silently capture nothing.
        logger.setLevel(Level.TRACE);
        this.appender.start();
        this.logger.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** The one event expected by handlers that log exactly once. */
    public ILoggingEvent onlyEvent() {
        List<ILoggingEvent> events = events();
        if (events.size() != 1) {
            throw new AssertionError("Expected exactly 1 log event but captured " + events.size()
                    + ": " + events.stream().map(ILoggingEvent::getFormattedMessage).toList());
        }
        return events.getFirst();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
