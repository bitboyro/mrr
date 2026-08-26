package ro.bitboy.mrr.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The syntax that turns a comment into an act on the ledger.
 *
 * <p>Most of these tests are about <em>not</em> firing. A command that triggers from prose
 * about mrr would file findings nobody asked for, on somebody else's merge request, which is
 * worse than a command that occasionally has to be retyped.
 */
class NoteCommandTest {

    @Test
    void aCommandIsTheFirstThingInTheComment() {
        NoteCommand command = NoteCommand.parse("mrr: finding The retry loop has no ceiling");

        assertThat(command).isNotNull();
        assertThat(command.kind()).isEqualTo(NoteCommand.Kind.FINDING);
        assertThat(command.text()).isEqualTo("The retry loop has no ceiling");
    }

    @Test
    void whatFollowsOnLaterLinesIsTheDetail() {
        NoteCommand command = NoteCommand.parse("""
                mrr: finding The retry loop has no ceiling

                It spins forever on a 500 because the counter never increments.""");

        assertThat(command.text()).isEqualTo("The retry loop has no ceiling\n\n"
                + "It spins forever on a 500 because the counter never increments.");
    }

    @Test
    void aCommandOnItsOwnLineTakesTheRestAsItsText() {
        NoteCommand command = NoteCommand.parse("mrr: wontfix\nWe accept this on the admin path.");

        assertThat(command.kind()).isEqualTo(NoteCommand.Kind.WONT_FIX);
        assertThat(command.text()).isEqualTo("We accept this on the admin path.");
    }

    @Test
    void aBareCommandHasNoText() {
        assertThat(NoteCommand.parse("mrr: dispute").text()).isNull();
    }

    /** The case this syntax exists to survive: people talking about mrr on their own MRs. */
    @Test
    void proseMentioningTheSyntaxIsNotACommand() {
        assertThat(NoteCommand.parse("you can write mrr: finding to raise one")).isNull();
        assertThat(NoteCommand.parse("Does mrr: wontfix work here?")).isNull();
        assertThat(NoteCommand.parse("See the docs — mrr: dispute is the one you want.")).isNull();
    }

    /** A quoted reply carries the whole earlier comment, marker and all. */
    @Test
    void aQuotedCommandIsNotACommand() {
        assertThat(NoteCommand.parse("> mrr: wontfix we accept this\n\nI disagree.")).isNull();
    }

    @Test
    void anUnknownWordAfterTheMarkerIsNotACommand() {
        assertThat(NoteCommand.parse("mrr: please have a look at this")).isNull();
        assertThat(NoteCommand.parse("mrr: ")).isNull();
    }

    @Test
    void theMarkerIsCaseInsensitiveAndToleratesLeadingSpace() {
        assertThat(NoteCommand.parse("  MRR: Finding something").kind())
                .isEqualTo(NoteCommand.Kind.FINDING);
        assertThat(NoteCommand.parse("   mrr: finding three spaces is still a command").kind())
                .isEqualTo(NoteCommand.Kind.FINDING);
    }

    /**
     * Four spaces is a Markdown code block, and a pasted code sample or chunk of documentation
     * that happens to start with the marker must not act on somebody's finding. On a thread
     * that already carries one, this would have closed it.
     */
    @Test
    void anIndentedCodeBlockIsNotACommand() {
        assertThat(NoteCommand.parse("    mrr: wontfix we accept this")).isNull();
        assertThat(NoteCommand.parse("        mrr: finding deeply indented")).isNull();
        assertThat(NoteCommand.parse("\tmrr: wontfix tab-indented")).isNull();
    }

    /** "The very first thing in the comment" means the first line, not the first non-blank one. */
    @Test
    void aCommandBelowBlankLinesIsNotACommand() {
        assertThat(NoteCommand.parse("\n\nmrr: finding buried under blank lines")).isNull();
    }

    @Test
    void theSpellingsPeopleActuallyTypeAllWork() {
        assertThat(NoteCommand.parse("mrr: wont-fix x").kind()).isEqualTo(NoteCommand.Kind.WONT_FIX);
        assertThat(NoteCommand.parse("mrr: disagree x").kind()).isEqualTo(NoteCommand.Kind.DISPUTE);
    }

    @Test
    void nullAndEmptyAreNotCommands() {
        assertThat(NoteCommand.parse(null)).isNull();
        assertThat(NoteCommand.parse("")).isNull();
        assertThat(NoteCommand.parse("   ")).isNull();
    }
}
