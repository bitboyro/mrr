package ro.bitboy.mrr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.repo.Degradation;
import ro.bitboy.mrr.repo.RepoState;
import ro.bitboy.mrr.repo.Tier;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire values and parsers must not depend on the JVM's default locale.
 *
 * <p>Turkish is the case that breaks it, and it is not hypothetical: a container started with
 * {@code LANG=tr_TR.UTF-8} lower-cases {@code I} to a dotless {@code ı}. Every
 * {@code name().toLowerCase()} that renders a wire value, and every
 * {@code valueOf(raw.toUpperCase())} that reads one back, was locale-sensitive — so on such a
 * host {@code AST_FILTERED} went out as {@code ast_fıltered}, a severity of {@code minor}
 * parsed as {@code MINOR} only by luck, and {@code find_references} threw
 * {@code IllegalArgumentException} reading its own tier back.
 *
 * <p>Pinned in one test rather than one per class, because the property is about the whole
 * boundary and a per-class version would be seven copies of the same setup.
 */
class LocaleIndependenceTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private Locale original;

    @BeforeEach
    void useATurkishDefault() {
        original = Locale.getDefault();
        Locale.setDefault(TURKISH);
    }

    @AfterEach
    void restore() {
        Locale.setDefault(original);
    }

    /** The demonstration that the default locale really is hostile here. */
    @Test
    void theDefaultLocaleWouldOtherwiseMangleTheseNames() {
        assertThat("AST_FILTERED".toLowerCase()).isEqualTo("ast_fıltered");
        assertThat("minor".toUpperCase()).isEqualTo("MİNOR");
    }

    @Test
    void tierRendersAndReadsBackTheSameName() {
        assertThat(Tier.AST_FILTERED.wire()).isEqualTo("ast_filtered");
        assertThat(Tier.TEXTUAL.wire()).isEqualTo("textual");
        assertThat(Tier.valueOf(Tier.AST_FILTERED.wire().toUpperCase(Locale.ROOT)))
                .isEqualTo(Tier.AST_FILTERED);
    }

    @Test
    void degradationRendersItsName() {
        assertThat(Degradation.RANKED_SAMPLE.wire()).isEqualTo("ranked_sample");
    }

    @Test
    void severityParsesTheNamesTheModelActuallySends() {
        assertThat(Finding.Severity.parse("minor")).isEqualTo(Finding.Severity.MINOR);
        assertThat(Finding.Severity.parse("Major")).isEqualTo(Finding.Severity.MAJOR);
        assertThat(Finding.Severity.parse("critical")).isEqualTo(Finding.Severity.BLOCKER);
    }

    @Test
    void repoStateParsesItsOwnStoredName() {
        assertThat(RepoState.parse(RepoState.CLONING.name())).isEqualTo(RepoState.CLONING);
        assertThat(RepoState.parse("too_large")).isEqualTo(RepoState.TOO_LARGE);
    }

    @Test
    void forgeKindRoundTripsItsSlug() {
        assertThat(ForgeKind.parse(ForgeKind.GITLAB.slug())).isEqualTo(ForgeKind.GITLAB);
        assertThat(ForgeKind.parse("GitLab")).isEqualTo(ForgeKind.GITLAB);
    }
}
