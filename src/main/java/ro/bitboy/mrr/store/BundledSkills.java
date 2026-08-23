package ro.bitboy.mrr.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/**
 * The review skills that ship inside the jar, offered to every account as a starting library.
 *
 * <p>{@code librarian.md} is deliberately not among them: it is the librarian agent's own
 * system prompt, loaded directly by {@code Librarian}, not something a project attaches to a
 * review.
 */
@Component
public class BundledSkills {

    private static final Logger log = LoggerFactory.getLogger(BundledSkills.class);
    private static final String NOT_A_REVIEW_SKILL = "librarian";

    public record Skill(String name, String body) {
    }

    private final List<Skill> skills;

    public BundledSkills() {
        this.skills = load();
        log.info("bundled skills: {}", skills.stream().map(Skill::name).toList());
    }

    public List<Skill> all() {
        return skills;
    }

    private static List<Skill> load() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:skills/*.md");
            return java.util.Arrays.stream(found)
                    .map(BundledSkills::read)
                    .filter(skill -> !NOT_A_REVIEW_SKILL.equals(skill.name()))
                    .sorted(Comparator.comparing(Skill::name))
                    .toList();
        } catch (IOException e) {
            // A jar that cannot read its own resources is broken in a way worth failing on:
            // booting with an empty library would look like a deliberately empty one.
            throw new UncheckedIOException("could not read the bundled skills", e);
        }
    }

    private static Skill read(Resource resource) {
        String filename = resource.getFilename() == null ? "skill" : resource.getFilename();
        String name = filename.endsWith(".md")
                ? filename.substring(0, filename.length() - 3)
                : filename;
        try (var in = resource.getInputStream()) {
            return new Skill(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read skills/" + filename, e);
        }
    }
}
