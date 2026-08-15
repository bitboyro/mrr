package ro.bitboy.mrr.web;

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
}
