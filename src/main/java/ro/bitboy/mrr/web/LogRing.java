package ro.bitboy.mrr.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Last N log lines, for the admin Logs page. Console logging is unchanged — this is an
 * extra appender on ROOT, in-memory, gone on restart.
 */
@Component
public class LogRing extends UnsynchronizedAppenderBase<ILoggingEvent> {

    public static final int CAPACITY = 2_000;
    public static final int MAX_MESSAGE_CHARS = 16_000;

    private final ArrayDeque<Line> ring = new ArrayDeque<>(CAPACITY);
    private long seq;

    public LogRing() {
        setName("mrr-memory");
    }

    @PostConstruct
    void attach() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        setContext(context);
        start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(getName()) == null) {
            root.addAppender(this);
        }
    }

    @PreDestroy
    void detach() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(getName());
        }
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        record(event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage(),
                thrown(event.getThrowableProxy()),
                Instant.ofEpochMilli(event.getTimeStamp()));
    }

    synchronized void record(String level, String logger, String message, String thrown, Instant at) {
        seq++;
        if (ring.size() >= CAPACITY) {
            ring.removeFirst();
        }
        ring.addLast(new Line(seq, at, level, logger, cap(message), cap(thrown)));
    }

    /**
     * {@code after <= 0} returns the tail (last {@code limit} lines). Otherwise lines
     * newer than {@code after}, in order, capped at {@code limit}.
     */
    public synchronized List<Line> after(long after, int limit) {
        int cap = Math.clamp(limit, 1, CAPACITY);
        if (after <= 0) {
            int skip = Math.max(0, ring.size() - cap);
            List<Line> tail = new ArrayList<>(cap);
            int i = 0;
            for (Line line : ring) {
                if (i++ >= skip) {
                    tail.add(line);
                }
            }
            return tail;
        }
        List<Line> newer = new ArrayList<>();
        for (Line line : ring) {
            if (line.seq() > after) {
                newer.add(line);
                if (newer.size() >= cap) {
                    break;
                }
            }
        }
        return newer;
    }

    public synchronized void clear() {
        ring.clear();
    }

    public synchronized int size() {
        return ring.size();
    }

    private static String cap(String text) {
        if (text == null || text.length() <= MAX_MESSAGE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE_CHARS)
                + "\n\n[truncated by mrr, " + text.length() + " chars total]";
    }

    private static String thrown(IThrowableProxy proxy) {
        if (proxy == null) {
            return null;
        }
        String message = proxy.getMessage();
        return proxy.getClassName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public record Line(long seq, Instant at, String level, String logger, String message, String thrown) {
    }
}
