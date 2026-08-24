package ro.bitboy.mrr.forge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffTest {

    @Test
    void cutsOnDiffGitRatherThanOnTripleDash() {
        // The trap this exists for: a patch body contains its own "--- " lines, so cutting on
        // those would invent a file for every hunk header.
        List<FilePatch> patches = UnifiedDiff.parse("""
                diff --git a/A.java b/A.java
                index 1..2 100644
                --- a/A.java
                +++ b/A.java
                @@ -1 +1,2 @@
                +int x;
                diff --git a/B.java b/B.java
                --- a/B.java
                +++ b/B.java
                @@ -1 +1,2 @@
                +int y;
                """);

        assertThat(patches).extracting(FilePatch::path).containsExactly("A.java", "B.java");
        assertThat(patches.getFirst().diff()).contains("+int x;").doesNotContain("--- a/A.java");
    }

    @Test
    void aRenameIsFiledUnderItsNewName() {
        // The hunks and the model's line numbers refer to the b/ side, so that is the path a
        // finding has to be anchored against.
        List<FilePatch> patches = UnifiedDiff.parse("""
                diff --git a/old/Name.java b/new/Name.java
                similarity index 95%
                rename from old/Name.java
                rename to new/Name.java
                @@ -1 +1 @@
                +renamed
                """);

        assertThat(patches).extracting(FilePatch::path).containsExactly("new/Name.java");
        assertThat(patches.getFirst().diff()).contains("+renamed").doesNotContain("rename from");
    }

    @Test
    void aPathWithSpacesKeepsItsWholeName() {
        List<FilePatch> patches = UnifiedDiff.parse("""
                diff --git a/src/my file.java b/src/my file.java
                @@
                +x
                """);

        assertThat(patches).extracting(FilePatch::path).containsExactly("src/my file.java");
    }

    @Test
    void blankAndHeaderlessInputProduceNothing() {
        assertThat(UnifiedDiff.parse(null)).isEmpty();
        assertThat(UnifiedDiff.parse("   ")).isEmpty();
        assertThat(UnifiedDiff.parse("just some text\nwith no git header")).isEmpty();
    }

    @Test
    void assembleRoundTripsIntoMrrsOwnBlobFormat() {
        String assembled = UnifiedDiff.assemble(List.of(
                new FilePatch("A.java", "@@\n+int x;"),
                new FilePatch("B.java", "@@\n+int y;")));

        assertThat(assembled).isEqualTo("--- A.java\n@@\n+int x;\n--- B.java\n@@\n+int y;\n");
    }
}
