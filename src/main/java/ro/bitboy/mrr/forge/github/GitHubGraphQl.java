package ro.bitboy.mrr.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.web.ApiException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one GraphQL call path GitHub forces on mrr.
 *
 * <p>Review threads are not in GitHub's REST API. Their resolved state can only be read through
 * {@code pullRequest.reviewThreads}, and only the mutations {@code resolveReviewThread} and
 * {@code unresolveReviewThread} can write it — both taking a {@code PRRT_…} node id that
 * nothing but a GraphQL query hands out. Since mrr's whole claim is that its thread state and
 * the host's agree, this is not optional and it is not a corner: the three places that keep
 * them in step all pass through here.
 *
 * <p>Deliberately not a GraphQL client library. One query and two mutations against
 * {@code /graphql} is a JSON string posted with the same {@link RestClient} everything else
 * uses, and a dependency for three strings would be the wrong trade.
 *
 * <p><strong>Permissions.</strong> A fine-grained PAT needs <b>Pull requests: Read and write</b>
 * and <b>Contents: Read and write</b>. The second is counter-intuitive for an operation about
 * pull requests and is a documented cause of a 403 that looks like anything but a scope
 * problem, which is why it is in the setup table.
 */
final class GitHubGraphQl {

    private static final Logger log = LoggerFactory.getLogger(GitHubGraphQl.class);

    /**
     * One page of review threads, with each thread's first comment. Matching on that comment's
     * {@code databaseId} is what connects a REST comment id — the only id mrr stored when it
     * opened the thread — to the node id the mutations need.
     */
    private static final String THREADS_QUERY = """
            query($owner:String!,$repo:String!,$number:Int!,$after:String){
              repository(owner:$owner,name:$repo){
                pullRequest(number:$number){
                  reviewThreads(first:100,after:$after){
                    pageInfo{hasNextPage endCursor}
                    nodes{
                      id
                      isResolved
                      resolvedBy{login}
                      comments(first:1){nodes{databaseId body}}
                    }
                  }
                }
              }
            }""";

    private static final String RESOLVE = """
            mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{id isResolved}}}""";

    private static final String UNRESOLVE = """
            mutation($id:ID!){unresolveReviewThread(input:{threadId:$id}){thread{id isResolved}}}""";

    private final RestClient http;

    GitHubGraphQl(RestClient.Builder builder) {
        this.http = builder.build();
    }

    /** github.com puts GraphQL on api.github.com; an Enterprise Server on {base}/api/graphql. */
    private static String endpoint(Project project) {
        String api = GitHubForge.apiBase(project);
        String v3 = "/api/v3";
        return api.endsWith(v3)
                ? api.substring(0, api.length() - v3.length()) + "/api/graphql"
                : api + "/graphql";
    }

    /** Every review thread on the request, paged. Empty when GitHub will not answer. */
    List<Thread> reviewThreads(Project project, long mrIid) {
        List<Thread> threads = new ArrayList<>();
        String[] parts = ownerRepo(project);
        String after = null;
        for (int page = 0; page < 20; page++) {
            Map<String, Object> variables = new java.util.LinkedHashMap<>();
            variables.put("owner", parts[0]);
            variables.put("repo", parts[1]);
            variables.put("number", (int) mrIid);
            variables.put("after", after);
            JsonNode data = post(project, THREADS_QUERY, variables);
            if (data == null) {
                break;
            }
            JsonNode connection = data.path("repository").path("pullRequest").path("reviewThreads");
            for (JsonNode node : connection.path("nodes")) {
                JsonNode first = node.path("comments").path("nodes").path(0);
                String databaseId = first.path("databaseId").isMissingNode()
                        ? null : first.path("databaseId").asText();
                if (databaseId == null || databaseId.isBlank()) {
                    continue;
                }
                threads.add(new Thread(
                        node.path("id").asText(),
                        databaseId,
                        first.path("body").asText(""),
                        node.path("isResolved").asBoolean(false),
                        node.path("resolvedBy").path("login").asText(null)));
            }
            if (!connection.path("pageInfo").path("hasNextPage").asBoolean(false)) {
                break;
            }
            after = connection.path("pageInfo").path("endCursor").asText(null);
            if (after == null) {
                break;
            }
        }
        return threads;
    }

    /** The node id for the thread whose first comment has this REST id, or null. */
    String threadNodeId(Project project, long mrIid, String commentDatabaseId) {
        for (Thread thread : reviewThreads(project, mrIid)) {
            if (thread.commentDatabaseId().equals(commentDatabaseId)) {
                return thread.nodeId();
            }
        }
        return null;
    }

    void setResolved(Project project, String threadNodeId, boolean resolved) {
        post(project, resolved ? RESOLVE : UNRESOLVE, Map.of("id", threadNodeId));
    }

    /**
     * A GraphQL error is a 200 with an {@code errors} array, so the status alone says nothing.
     * Both shapes fail closed the same way as a REST failure would — never with the response
     * body, which can echo the token back.
     */
    private JsonNode post(Project project, String query, Map<String, Object> variables) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        try {
            JsonNode response = http.post()
                    .uri(URI.create(endpoint(project)))
                    .header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + (project.gitlabToken() == null ? "" : project.gitlabToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return null;
            }
            JsonNode errors = response.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                String type = errors.get(0).path("type").asText("");
                log.warn("GitHub GraphQL refused {}: {}",
                        query.startsWith("query") ? "reviewThreads" : "a resolve mutation",
                        type.isBlank() ? "see GitHub's response" : type);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "forge_error",
                        "GitHub GraphQL refused the review-thread call"
                                + (type.isBlank() ? "" : " (" + type + ")")
                                + " — a fine-grained token needs Pull requests: Read and write "
                                + "and Contents: Read and write");
            }
            return response.path("data");
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "forge_error",
                    "GitHub POST /graphql failed with " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "forge_error",
                    "GitHub POST /graphql failed: " + e.getMessage());
        }
    }

    private static String[] ownerRepo(Project project) {
        String id = project.gitlabProjectId() == null ? "" : project.gitlabProjectId().trim();
        String[] parts = id.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw ApiException.badRequest(
                    "GitHub addresses repositories as owner/repo — \"" + id + "\" is not one");
        }
        return parts;
    }

    /** One review thread: its node id, the REST id mrr stored, and its state. */
    record Thread(String nodeId, String commentDatabaseId, String body, boolean resolved,
                  String resolvedBy) {
    }
}
