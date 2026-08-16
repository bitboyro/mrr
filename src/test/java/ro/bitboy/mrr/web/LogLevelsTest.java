package ro.bitboy.mrr.web;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogLevelsTest {

    @AfterEach
    void restore() {
        LogLevels.set(LogLevels.APP, "INHERIT");
    }

    @Test
    void switchingToDebugTakesEffectImmediately() {
        LogLevels.set(LogLevels.APP, "DEBUG");
        LogLevels.State state = LogLevels.stateOf(LogLevels.APP);
        assertThat(state.configuredLevel()).isEqualTo("DEBUG");
        assertThat(state.effectiveLevel()).isEqualTo("DEBUG");
    }

    @Test
    void inheritClearsTheOverride() {
        LogLevels.set(LogLevels.APP, "TRACE");
        LogLevels.set(LogLevels.APP, "INHERIT");
        assertThat(LogLevels.stateOf(LogLevels.APP).configuredLevel()).isNull();
    }

    @Test
    void rejectsAnUnknownLevel() {
        assertThatThrownBy(() -> LogLevels.set(LogLevels.APP, "LOUD"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsDebugOnHttpHeaderLoggers() {
        LogLevels.pinHttpSecretLoggers();
        assertThatThrownBy(() -> LogLevels.set("org.apache.hc.client5.http.headers", "DEBUG"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PRIVATE-TOKEN");
        assertThat(LogLevels.stateOf("org.apache.hc.client5.http.headers").effectiveLevel())
                .isEqualTo("WARN");
    }

    @Test
    void rootDebugDoesNotEnableHeaderLogging() {
        LogLevels.set(Logger.ROOT_LOGGER_NAME, "DEBUG");
        try {
            assertThat(LogLevels.stateOf("org.apache.hc.client5.http.headers").effectiveLevel())
                    .isEqualTo("WARN");
            assertThat(LogLevels.stateOf("org.apache.hc.client5.http.wire").effectiveLevel())
                    .isEqualTo("WARN");
        } finally {
            LogLevels.set(Logger.ROOT_LOGGER_NAME, "INFO");
        }
    }
}
