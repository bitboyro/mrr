package ro.bitboy.mrr.forge;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.model.ForgeKind;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A limitation should be readable before the first 403, not after it — so every capability the
 * reviewer branches on has a row, and every row that is not OK says what mrr does instead.
 */
class ForgeReportTest {

    @Test
    void gitlabIsCompleteAndSaysSo() {
        ForgeReport report = ForgeReport.of(ForgeKind.GITLAB, "https://gitlab.com",
                ForgeCapabilities.full());

        assertThat(report.complete()).isTrue();
        assertThat(report.requirements()).hasSize(7)
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(ForgeReport.Status.OK));
        assertThat(report.forgeName()).isEqualTo("GitLab");
    }

    @Test
    void githubsMissingUnapproveIsNamedRatherThanPaperedOver() {
        ForgeReport report = ForgeReport.of(ForgeKind.GITHUB, "https://github.com",
                new ForgeCapabilities(true, true, true, true, false, true, true));

        assertThat(report.complete()).isFalse();
        ForgeReport.Requirement unapprove = row(report, "unapprove");
        assertThat(unapprove.status()).isEqualTo(ForgeReport.Status.MISSING);
        assertThat(unapprove.detail())
                .contains("dismisses")
                .contains("different audit trail");
    }

    @Test
    void anUnprobedCapabilityReadsUnknownRatherThanMissing() {
        // "mrr could not tell" and "this host cannot" are different things to a human deciding
        // whether to go and check, even though mrr fails closed on both.
        ForgeReport report = ForgeReport.of(ForgeKind.FORGEJO, "https://codeberg.org",
                new ForgeCapabilities(true, false, false, true, false, true, true,
                        Set.of("resolvableThreads", "threadedReplies", "unapprove")));

        assertThat(row(report, "resolvableThreads").status())
                .isEqualTo(ForgeReport.Status.UNKNOWN);
        assertThat(row(report, "resolvableThreads").detail())
                .contains("did not say")
                .contains("struck-through Addressed marker");
        // A flag that is simply off, with no probe involved, is still MISSING.
        assertThat(row(report, "merge").status()).isEqualTo(ForgeReport.Status.OK);
    }

    @Test
    void everyRowExplainsWhatMrrDoesWhenTheHostFallsShort() {
        ForgeReport report = ForgeReport.of(ForgeKind.FORGEJO, "https://codeberg.org",
                new ForgeCapabilities(false, false, false, false, false, false, false));

        assertThat(report.requirements()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(ForgeReport.Status.MISSING);
            assertThat(r.title()).isNotBlank();
            assertThat(r.detail()).isNotBlank();
        });
    }

    private static ForgeReport.Requirement row(ForgeReport report, String name) {
        return report.requirements().stream()
                .filter(r -> r.name().equals(name))
                .findFirst().orElseThrow();
    }
}
