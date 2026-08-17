package ro.bitboy.mrr.review;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.gitlab.GitLabClient.FilePatch;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffWindowsTest {

    @Test
    void filesThatFitStayInOneSlice() {
        List<DiffWindows.Slice> slices = DiffWindows.pack(List.of(
                new FilePatch("A.java", "+int a;\n"),
                new FilePatch("B.java", "+int b;\n")), 256, 8);

        assertThat(slices).hasSize(1);
        assertThat(slices.getFirst().paths()).containsExactly("A.java", "B.java");
        assertThat(slices.getFirst().clipped()).isFalse();
    }

    @Test
    void twoFilesThatExceedTheWindowBecomeTwoSlices() {
        FilePatch a = new FilePatch("A.java", "+" + "a".repeat(200) + "\n");
        FilePatch b = new FilePatch("B.java", "+" + "b".repeat(200) + "\n");
        List<DiffWindows.Slice> slices = DiffWindows.pack(List.of(a, b), 256, 8);

        assertThat(slices).hasSize(2);
        assertThat(slices.get(0).paths()).containsExactly("A.java");
        assertThat(slices.get(0).text()).contains("--- A.java").doesNotContain("B.java");
        assertThat(slices.get(1).paths()).containsExactly("B.java");
        assertThat(slices.get(1).of()).isEqualTo(2);
    }

    @Test
    void anOversizedFileSplitsOnHunks() {
        String hunk1 = "@@ -1,2 +1,2 @@\n+" + "a".repeat(180) + "\n";
        String hunk2 = "@@ -10,2 +10,2 @@\n+" + "b".repeat(180) + "\n";
        List<DiffWindows.Slice> slices = DiffWindows.pack(
                List.of(new FilePatch("Big.java", hunk1 + hunk2)), 256, 8);

        assertThat(slices).hasSize(2);
        assertThat(slices.get(0).text()).contains("@@ -1,2").doesNotContain("@@ -10,2");
        assertThat(slices.get(1).text()).contains("@@ -10,2");
        assertThat(slices.get(0).paths()).containsExactly("Big.java");
    }

    @Test
    void leftoverSlicesMergeIntoTheLastAndClip() {
        List<FilePatch> files = List.of(
                new FilePatch("A.java", "+" + "a".repeat(200) + "\n"),
                new FilePatch("B.java", "+" + "b".repeat(200) + "\n"),
                new FilePatch("C.java", "+" + "c".repeat(200) + "\n"),
                new FilePatch("D.java", "+" + "d".repeat(200) + "\n"));
        List<DiffWindows.Slice> slices = DiffWindows.pack(files, 256, 2);

        assertThat(slices).hasSize(2);
        assertThat(slices.get(0).paths()).containsExactly("A.java");
        assertThat(slices.get(1).clipped()).isTrue();
        assertThat(slices.get(1).text()).contains("[diff truncated by mrr]");
        assertThat(slices.get(1).text().length()).isLessThanOrEqualTo(256);
    }

    @Test
    void slicesNeverExceedAWindowBelow256() {
        FilePatch a = new FilePatch("A.java", "+" + "a".repeat(50) + "\n");
        FilePatch b = new FilePatch("B.java", "+" + "b".repeat(50) + "\n");
        List<DiffWindows.Slice> slices = DiffWindows.pack(List.of(a, b), 80, 8);
        assertThat(slices).hasSize(2);
        assertThat(slices).allSatisfy(s -> assertThat(s.text().length()).isLessThanOrEqualTo(80));
    }

    @Test
    void aHardSplitFileStaysInsideTheWindow() {
        List<DiffWindows.Slice> slices = DiffWindows.pack(
                List.of(new FilePatch("Big.java", "+" + "x".repeat(500) + "\n")), 40, 20);
        assertThat(slices.size()).isGreaterThan(1);
        assertThat(slices).allSatisfy(s -> assertThat(s.text().length()).isLessThanOrEqualTo(40));
    }
}
