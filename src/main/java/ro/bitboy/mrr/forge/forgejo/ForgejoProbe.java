package ro.bitboy.mrr.forge.forgejo;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.model.Project;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a Forgejo or Gitea instance what its API actually exposes, instead of guessing from a
 * version number.
 *
 * <p>This exists because of the design's open question: conversation-resolve, threaded replies
 * and approval dismissal are all present in some builds and absent in others, and for
 * self-hosted instances that varies per deployment rather than per product. Every one of those
 * is a capability the reviewer branches on, so answering "probably" is the same as answering
 * wrong.
 *
 * <p>The instance publishes its own OpenAPI document at {@code /swagger.v1.json}. Reading the
 * path list is the only way to know without writing to a live merge request. When the document
 * cannot be read at all — an instance with the API docs disabled, a network failure — every
 * uncertain flag comes back false <em>and named in {@link ForgeCapabilities#unknown()}</em>, so
 * the UI can say "unknown" rather than "missing" and mrr still fails closed.
 *
 * <p>Cached per host for {@link #TTL}. Capabilities are asked for on every review; probing on
 * every review would be rude to somebody else's server for an answer that changes at upgrades.
 */
final class ForgejoProbe {

    private static final Logger log = LoggerFactory.getLogger(ForgejoProbe.class);

    private static final Duration TTL = Duration.ofMinutes(30);

    private final RestClient http;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    ForgejoProbe(RestClient.Builder builder) {
        this.http = builder.build();
    }

    ForgeCapabilities capabilities(Project project) {
        String base = project.gitlabUrl() == null ? "" : project.gitlabUrl().replaceAll("/+$", "");
        Cached cached = cache.get(base);
        if (cached != null && cached.freshAt().isAfter(Instant.now().minus(TTL))) {
            return cached.capabilities();
        }
        ForgeCapabilities probed = probe(base, project);
        cache.put(base, new Cached(probed, Instant.now()));
        return probed;
    }

    /** Drops the cached answer for a host, so an upgrade can be picked up without a restart. */
    void forget(String baseUrl) {
        cache.remove(baseUrl == null ? "" : baseUrl.replaceAll("/+$", ""));
    }

    private ForgeCapabilities probe(String base, Project project) {
        Set<String> unknown = new LinkedHashSet<>();
        JsonNode paths = swaggerPaths(base, project);
        if (paths == null) {
            unknown.add("resolvableThreads");
            unknown.add("threadedReplies");
            unknown.add("unapprove");
            return new ForgeCapabilities(true, false, false, true, false, true, true, unknown);
        }
        boolean resolve = anyPathContains(paths, "/comments/", "/resolve");
        boolean replies = anyPathContains(paths, "/pulls/", "/replies");
        boolean dismiss = anyPathContains(paths, "/reviews/", "/dismissals");
        log.info("Forgejo probe of {} — resolve={} replies={} dismissals={}",
                base, resolve, replies, dismiss);
        return new ForgeCapabilities(true, resolve, replies, true, dismiss, true, true, unknown);
    }

    private JsonNode swaggerPaths(String base, Project project) {
        if (base.isBlank()) {
            return null;
        }
        try {
            JsonNode document = http.get()
                    .uri(URI.create(base + "/swagger.v1.json"))
                    .header("Authorization", "token "
                            + (project.gitlabToken() == null ? "" : project.gitlabToken()))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode paths = document == null ? null : document.path("paths");
            return paths != null && paths.isObject() ? paths : null;
        } catch (Exception e) {
            log.info("Forgejo capability probe of {} could not read swagger.v1.json ({}) — "
                    + "treating resolve, replies and dismissal as unknown", base, e.getMessage());
            return null;
        }
    }

    /** True when some documented path contains every fragment, in order. */
    private static boolean anyPathContains(JsonNode paths, String... fragments) {
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String path = it.next();
            int at = 0;
            boolean all = true;
            for (String fragment : fragments) {
                int found = path.indexOf(fragment, at);
                if (found < 0) {
                    all = false;
                    break;
                }
                at = found + fragment.length();
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private record Cached(ForgeCapabilities capabilities, Instant freshAt) {
    }
}
