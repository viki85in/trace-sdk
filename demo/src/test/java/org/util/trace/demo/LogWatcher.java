package org.util.trace.demo;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Collects what the SDK logs, so tests can check that a problem was reported rather than silently ignored. */
final class LogWatcher extends Handler implements AutoCloseable {

    private final Logger logger = Logger.getLogger("org.util.trace");
    private final List<String> messages = new CopyOnWriteArrayList<>();

    LogWatcher() {
        logger.addHandler(this);
    }

    List<String> messages() {
        return messages;
    }

    boolean contains(String text) {
        return messages.stream().anyMatch(m -> m.contains(text));
    }

    @Override
    public void publish(LogRecord record) {
        String message = record.getMessage();
        Object[] params = record.getParameters();
        if (params != null && params.length > 0) {
            message = java.text.MessageFormat.format(message, params);
        }
        messages.add(record.getLevel() + ": " + message);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
        logger.setLevel(Level.INFO);
    }
}
