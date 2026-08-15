package ro.bitboy.mrr.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import java.util.List;

/**
 * Runtime log-level changes without Actuator. In-memory: a restart restores
 * {@code logging.level} from config.
 */
public final class LogLevels {

    public static final String APP = "ro.bitboy.mrr";

    private static final List<String> WATCHED = List.of(Logger.ROOT_LOGGER_NAME, APP);

    private LogLevels() {
    }

    public static List<State> watched() {
        return WATCHED.stream().map(LogLevels::stateOf).toList();
    }

    public static State stateOf(String name) {
        Logger logger = logger(name);
        Level configured = logger.getLevel();
        return new State(logger.getName(),
                configured == null ? null : configured.toString(),
                logger.getEffectiveLevel().toString());
    }

    /**
     * @param level a Logback/Spring level name, or null/blank/INHERIT to clear an override
     */
    public static State set(String name, String level) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("logger name is required");
        }
        LoggingSystem system = LoggingSystem.get(LogLevels.class.getClassLoader());
        String trimmed = level == null ? "" : level.trim();
        if (trimmed.isEmpty() || "INHERIT".equalsIgnoreCase(trimmed) || "NULL".equalsIgnoreCase(trimmed)) {
            system.setLogLevel(name, null);
        } else {
            LogLevel parsed;
            try {
                parsed = LogLevel.valueOf(trimmed.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("level must be TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF, or INHERIT");
            }
            system.setLogLevel(name, parsed);
        }
        return stateOf(name);
    }

    private static Logger logger(String name) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            throw ApiException.badRequest("log levels require logback");
        }
        return context.getLogger(name);
    }

    public record State(String name, String configuredLevel, String effectiveLevel) {
    }
}
