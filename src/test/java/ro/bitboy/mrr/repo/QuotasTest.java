package ro.bitboy.mrr.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Quotas bound breadth; budgets bound size. This is the breadth half. */
class QuotasTest {

    @Test
    void findReferencesStopsAfterItsAllowance() {
        Quotas quotas = Quotas.perUnit();

        for (int i = 0; i < Quotas.FIND_REFERENCES_PER_UNIT; i++) {
            assertThat(quotas.claim(RepoToolNames.FIND_REFERENCES)).isTrue();
        }

        assertThat(quotas.claim(RepoToolNames.FIND_REFERENCES)).isFalse();
        assertThat(quotas.usedFor(RepoToolNames.FIND_REFERENCES))
                .isEqualTo(Quotas.FIND_REFERENCES_PER_UNIT);
    }

    @Test
    void symbolAtIsUnlimitedBecauseItIsBoundedByConstruction() {
        Quotas quotas = Quotas.perUnit();

        for (int i = 0; i < 200; i++) {
            assertThat(quotas.claim(RepoToolNames.SYMBOL_AT)).isTrue();
        }
    }

    @Test
    void eachUnitGetsItsOwnAllowance() {
        // The reason the context is per slice: one slice spending everything must not leave
        // the next slice unable to look anything up.
        Quotas first = Quotas.perUnit();
        while (first.claim(RepoToolNames.FIND_REFERENCES)) {
            // exhaust it
        }

        Quotas second = Quotas.perUnit();

        assertThat(second.claim(RepoToolNames.FIND_REFERENCES)).isTrue();
    }

    @Test
    void aRefusalIsRecordedSoTheReviewCanSayWhereItStoppedLooking() {
        Quotas quotas = Quotas.perUnit();

        quotas.recordRefusal("find_references reached its per-slice limit of 4");

        assertThat(quotas.refusals()).containsExactly(
                "find_references reached its per-slice limit of 4");
    }

    @Test
    void aQuotaRefusalIsStructuredRatherThanAnError() {
        // The model should read this as "conclude", not as "something broke, retry".
        Refusal refusal = Refusal.quotaExhausted(RepoToolNames.FIND_REFERENCES, 4);

        assertThat(refusal.refusal()).isEqualTo("quota_exhausted");
        assertThat(refusal.limit()).isEqualTo(4);
        assertThat(refusal.advice()).contains("Conclude");
    }
}
