package ro.bitboy.mrr.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogRingTest {

    @Test
    void afterZeroReturnsTheTail() {
        LogRing ring = new LogRing();
        for (int i = 1; i <= 5; i++) {
            ring.record("INFO", "ro.bitboy.mrr", "line " + i, null, Instant.parse("2026-08-15T10:00:00Z"));
        }

        List<LogRing.Line> tail = ring.after(0, 3);
        assertThat(tail).extracting(LogRing.Line::message)
                .containsExactly("line 3", "line 4", "line 5");
        assertThat(tail.getLast().seq()).isEqualTo(5);
    }

    @Test
    void afterSeqReturnsOnlyNewerLines() {
        LogRing ring = new LogRing();
        ring.record("INFO", "ro.bitboy.mrr", "a", null, Instant.now());
        ring.record("WARN", "ro.bitboy.mrr", "b", null, Instant.now());
        ring.record("ERROR", "ro.bitboy.mrr", "c", null, Instant.now());

        assertThat(ring.after(2, 50)).extracting(LogRing.Line::message).containsExactly("c");
        assertThat(ring.after(3, 50)).isEmpty();
    }

    @Test
    void dropsTheOldestWhenFull() {
        LogRing ring = new LogRing();
        for (int i = 0; i < LogRing.CAPACITY + 3; i++) {
            ring.record("INFO", "t", Integer.toString(i), null, Instant.now());
        }
        assertThat(ring.size()).isEqualTo(LogRing.CAPACITY);
        assertThat(ring.after(0, 1).getFirst().message())
                .isEqualTo(Integer.toString(LogRing.CAPACITY + 2));
    }

    @Test
    void clearEmptiesTheRing() {
        LogRing ring = new LogRing();
        ring.record("INFO", "t", "x", null, Instant.now());
        ring.clear();
        assertThat(ring.after(0, 50)).isEmpty();
    }
}
