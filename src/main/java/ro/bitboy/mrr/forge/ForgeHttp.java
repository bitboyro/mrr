package ro.bitboy.mrr.forge;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.web.ApiException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The HTTP plumbing every adapter shares: one {@link RestClient}, the same error mapping, the
 * same page walk, and the same refusal to put a host's response body into an exception message
 * (a body can echo the token back).
 *
 * <p>Subclasses supply three things — where the API root is for a project, what the auth header
 * looks like, and how this host paginates. Everything else about talking to a forge is the
 * same shape, and duplicating it three times is how the three adapters would drift.
 */
public abstract class ForgeHttp {

    /** Twenty pages of 100 is 2000 items — enough for any real merge request, bounded for a bad one. */
    protected static final int MAX_PAGES = 20;
    protected static final int PAGE_SIZE = 100;

    protected final RestClient http;

    protected ForgeHttp(RestClient.Builder builder) {
        this.http = builder.build();
    }

    /** {@code https://host/api/v4/projects/42} and the like — everything a path is appended to. */
    protected abstract String apiRoot(Project project);

    /** How this host is told who is calling. */
    protected abstract Map<String, String> authHeaders(Project project);

    /** Name used in error messages and logs. */
    protected abstract String label();

    /** The query this host wants for page {@code n}. GitLab and GitHub differ from Forgejo. */
    protected String pageQuery(int page) {
        return "per_page=" + PAGE_SIZE + "&page=" + page;
    }

    /** The forge base URL off the project row, with any trailing slashes trimmed. */
    protected static String baseUrl(Project project) {
        String base = project.gitlabUrl() == null ? "" : project.gitlabUrl().replaceAll("/+$", "");
        return base;
    }

    /**
     * {@code owner/repo}, path-encoded a segment at a time.
     *
     * <p>Forgejo and GitHub address a repository by owner and name; only GitLab accepts a
     * numeric id or a deep group path. A project registered against one of them with a numeric
     * id in {@code gitlabProjectId} is a configuration mistake worth naming, because every call
     * would otherwise 404 with nothing to point at.
     */
    protected static String ownerRepo(Project project) {
        String id = project.gitlabProjectId() == null ? "" : project.gitlabProjectId().trim();
        String[] parts = id.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw ApiException.badRequest(
                    "this forge addresses repositories as owner/repo — \"" + id + "\" is not one");
        }
        return encode(parts[0]) + "/" + encode(parts[1]);
    }

    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Path-safe encoding: {@code %20} rather than {@code +}, which a raw-file path needs. */
    protected static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    protected JsonNode exchange(Project project, HttpMethod method, String path, Object body) {
        return exchangeEntity(project, method, path, body).getBody();
    }

    protected ResponseEntity<JsonNode> exchangeEntity(Project project, HttpMethod method,
                                                      String path, Object body) {
        return exchangeAbsolute(project, method, apiRoot(project) + path, path, body);
    }

    /**
     * Same as {@link #exchangeEntity} for a URL that is not under the project's API root —
     * GitHub's {@code /user} and {@code /graphql}, GitLab's {@code /api/v4/user}.
     *
     * @param label what the error message calls this call; never the full URL, which carries ids
     */
    protected ResponseEntity<JsonNode> exchangeAbsolute(Project project, HttpMethod method,
                                                        String url, String label, Object body) {
        try {
            return send(project, method, url, body);
        } catch (RestClientResponseException e) {
            throw failed(method, label, "with " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw failed(method, label, ": " + e.getMessage());
        }
    }

    /**
     * Like {@link #exchange}, but for a call whose whole purpose is to ask the host "will you
     * accept this?" — a comment anchored to a diff line, which a host routinely refuses for an
     * unchanged line or an invented path. A 4xx is that refusal, not a failure, so it comes back
     * as empty. Everything else — a 5xx, a timeout, an unreachable host — still throws, exactly
     * as {@link #exchange} would: those are not evidence the request itself was invalid, and
     * treating them as a routine refusal would silently fall back to an unanchored thread
     * (or, worse, post a duplicate one) instead of failing the review the way an outage should.
     *
     * <p><strong>401 and 403 are excluded from that.</strong> No host answers "this line cannot
     * be commented on" with an authentication or permission status — they use 400, 404 or 422 —
     * so reading those two as a position refusal turns a token problem into a sentence about
     * the diff. It matters most on GitHub, where a fine-grained token or an App installation
     * answers 403 for a scope it was never granted: a token that may comment but may not review
     * would otherwise post every finding unanchored for good, while the log blamed each line in
     * turn. The caller cannot tell the difference, so it is drawn here.
     */
    protected Optional<JsonNode> exchangeRejectable(Project project, HttpMethod method,
                                                    String path, Object body) {
        String url = apiRoot(project) + path;
        try {
            return Optional.ofNullable(send(project, method, url, body).getBody());
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                throw failed(method, path, "with " + e.getStatusCode()
                        + " — the token is not allowed to do this, which is not the same as the "
                        + "host refusing the line");
            }
            if (e.getStatusCode().is4xxClientError()) {
                return Optional.empty();
            }
            throw failed(method, path, "with " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw failed(method, path, ": " + e.getMessage());
        }
    }

    /** Builds and sends the request. No catch here — every caller decides what a failure means. */
    private ResponseEntity<JsonNode> send(Project project, HttpMethod method, String url,
                                          Object body) {
        // URI, not String. RestClient treats a String as a URI *template* and re-encodes
        // it, turning the %2F of an already-encoded project path into %252F — the host then
        // looks for a repository literally named "group%2Fname" and returns 404. Passing a
        // built URI skips template expansion.
        var spec = http.method(method).uri(URI.create(url));
        for (Map.Entry<String, String> header : authHeaders(project).entrySet()) {
            spec = spec.header(header.getKey(), header.getValue());
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(JsonNode.class);
    }

    /** Surfaces the host's status but not its body — the body can echo the token back. */
    protected ApiException failed(HttpMethod method, String path, String detail) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "forge_error",
                label() + " " + method + " " + path + " failed " + detail);
    }

    /**
     * Walks the host's pages. Stops on a short page, on a missing next-page hint, or at
     * {@link #MAX_PAGES} — an unpaged read quietly stops at the first slice, after which the
     * scan treats every unlisted live request as closed.
     */
    protected List<JsonNode> pagedArray(Project project, String path) {
        List<JsonNode> items = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String join = path.contains("?") ? "&" : "?";
            ResponseEntity<JsonNode> response =
                    exchangeEntity(project, HttpMethod.GET, path + join + pageQuery(page), null);
            JsonNode body = response.getBody();
            int size = 0;
            if (body != null && body.isArray()) {
                body.forEach(items::add);
                size = body.size();
            }
            if (size == 0 || !hasNextPage(response.getHeaders(), page, size)) {
                break;
            }
        }
        return items;
    }

    /**
     * Whether a further page exists. Each host says so differently — GitLab sets
     * {@code X-Next-Page}, GitHub a {@code Link: …rel="next"}, Forgejo neither — so the default
     * is the only thing all three agree on: a full page probably has more behind it.
     */
    protected boolean hasNextPage(HttpHeaders headers, int page, int size) {
        return size >= PAGE_SIZE;
    }

    /** 404 is "nothing there" for tree/commit/compare on an empty repo. Anything else fails closed. */
    protected JsonNode exchangeAllow404(Project project, HttpMethod method, String path,
                                        Object body) {
        try {
            return exchange(project, method, path, body);
        } catch (ApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                return null;
            }
            throw e;
        }
    }

    /**
     * A body the host returns as text rather than JSON — a raw file.
     *
     * <p>The accept override <em>replaces</em> whatever {@link #authHeaders} set, rather than
     * adding to it. {@code header()} appends, so adding here would send two Accept values;
     * GitHub echoes the pair back as one comma-joined {@code Content-Type}, which is not a
     * legal media type and fails while reading the response, not while sending it.
     */
    protected String getText(Project project, String url, String... accept) {
        var spec = http.get().uri(URI.create(url)).headers(headers -> {
            authHeaders(project).forEach(headers::set);
            if (accept.length > 0) {
                headers.put(HttpHeaders.ACCEPT, List.of(accept));
            }
        });
        return spec.retrieve().body(String.class);
    }

    protected static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Reads a file body the way every adapter needs it: an error string the model can read
     * rather than an exception, because a missing file is a normal answer to a tool call.
     */
    protected String readFileBody(Project project, String url, String path, Integer startLine,
                                  Integer endLine, int maxChars, String... accept) {
        String body;
        try {
            body = getText(project, url, accept);
        } catch (RestClientResponseException e) {
            return "error: " + label() + " returned " + e.getStatusCode() + " for " + path;
        } catch (ResourceAccessException e) {
            return "error: " + label() + " timed out or could not be reached for " + path;
        } catch (RestClientException | IllegalArgumentException e) {
            // A malformed or unreadable response is still a normal answer to a tool call: the
            // model gets a line it can act on instead of losing the turn to an exception.
            return "error: " + label() + " sent a response mrr could not read for " + path;
        }
        if (body == null) {
            return "error: empty file";
        }
        if (body.indexOf('\0') >= 0) {
            return "error: binary file, not shown";
        }
        String sliced = sliceLines(body, startLine, endLine);
        if (sliced.length() > maxChars) {
            return sliced.substring(0, maxChars) + "\n\n[file truncated by mrr]";
        }
        return sliced;
    }

    /**
     * Lines {@code startLine}..{@code endLine} of a file, 1-based and inclusive.
     *
     * <p>A range that lies past the end of the file, or one whose ends are the wrong way
     * round, says so. It used to fall back to returning the whole file, which is the worst
     * available answer: a model that asked for lines 5000–5100 of a hundred-line file was
     * handed lines 1–100 with nothing to say they were not the lines it asked for, and would
     * then report a finding against a line number that does not exist.
     */
    protected static String sliceLines(String body, Integer startLine, Integer endLine) {
        if (startLine == null && endLine == null) {
            return body;
        }
        String[] lines = body.split("\n", -1);
        int from = startLine == null ? 1 : Math.max(1, startLine);
        int to = endLine == null ? lines.length : Math.min(lines.length, endLine);
        if (from > lines.length) {
            return "error: this file has " + lines.length + " line(s); there is no line " + from;
        }
        if (from > to) {
            return "error: start_line " + from + " is after end_line " + endLine;
        }
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (!out.isEmpty()) out.append('\n');
            out.append(lines[i - 1]);
        }
        return out.toString();
    }

    /** Guards a sha before it goes into a URL path. */
    protected static void requireObjectId(String sha) {
        if (sha == null || sha.isBlank() || sha.contains("..") || sha.contains("/")) {
            throw ApiException.badRequest("commit sha is missing or not a git object id");
        }
    }
}
