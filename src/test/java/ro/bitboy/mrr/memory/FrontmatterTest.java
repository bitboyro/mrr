package ro.bitboy.mrr.memory;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FrontmatterTest {

    @Test
    void roundTripsMetadataAndBody() {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("id", "abc");
        fm.put("kind", "project");
        fm.put("title", "overview");
        fm.put("tags", List.of("java", "spring"));
        String raw = Frontmatter.serialize(fm, "Goal: review merge requests.\n");
        Frontmatter.Parsed parsed = Frontmatter.parse(raw);
        assertThat(parsed.frontmatter().get("id")).isEqualTo("abc");
        assertThat(parsed.frontmatter().get("kind")).isEqualTo("project");
        assertThat(parsed.body()).contains("Goal: review merge requests.");
    }

    @Test
    void bodyWithoutFrontmatterIsTheWholeFile() {
        Frontmatter.Parsed parsed = Frontmatter.parse("just prose");
        assertThat(parsed.frontmatter()).isEmpty();
        assertThat(parsed.body()).isEqualTo("just prose");
    }
}
