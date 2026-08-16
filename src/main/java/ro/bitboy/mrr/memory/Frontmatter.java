package ro.bitboy.mrr.memory;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The OKF on-disk format: a YAML frontmatter block delimited by {@code ---} lines,
 * followed by a markdown body. Copied from drd — metadata stays in the frontmatter so
 * updates never rewrite the prose.
 */
public final class Frontmatter {

    private static final String DELIM = "---";

    private Frontmatter() {
    }

    public record Parsed(Map<String, Object> frontmatter, String body) {
    }

    public static String serialize(Map<String, Object> frontmatter, String body) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        String yaml = new Yaml(options).dump(frontmatter == null ? new LinkedHashMap<>() : frontmatter);
        String safeBody = body == null ? "" : body.strip();
        return DELIM + "\n" + yaml + DELIM + "\n\n" + safeBody + (safeBody.isEmpty() ? "" : "\n");
    }

    @SuppressWarnings("unchecked")
    public static Parsed parse(String content) {
        if (content == null) {
            return new Parsed(new LinkedHashMap<>(), "");
        }
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith(DELIM + "\n") && !normalized.startsWith(DELIM + "\r")) {
            return new Parsed(new LinkedHashMap<>(), normalized.strip());
        }
        int start = normalized.indexOf('\n') + 1;
        int end = normalized.indexOf("\n" + DELIM, start);
        if (end < 0) {
            return new Parsed(new LinkedHashMap<>(), normalized.strip());
        }
        String yamlBlock = normalized.substring(start, end);
        int bodyStart = normalized.indexOf('\n', end + 1);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1).strip();

        Object loaded = new Yaml().load(yamlBlock);
        Map<String, Object> fm = (loaded instanceof Map)
                ? (Map<String, Object>) loaded
                : new LinkedHashMap<>();
        return new Parsed(fm, body);
    }
}
