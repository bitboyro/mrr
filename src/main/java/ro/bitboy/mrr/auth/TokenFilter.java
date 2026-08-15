package ro.bitboy.mrr.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ErrorResponse;

import java.io.IOException;
import java.util.Optional;

/**
 * The whole of mrr's HTTP authentication: resolve a token to a {@link Principal} and hang it on
 * the request. Spring Security would give us filter chains, a SecurityContext, and an
 * authorization DSL we do not use — this is the same guarantee in forty lines.
 *
 * <p>Applies to {@code /api/**} only. Webhooks authenticate with GitLab's {@code X-Gitlab-Token}
 * against the project's own secret, and {@code /mcp} authenticates per tool call in
 * {@code McpTools} — the MCP transport needs the raw header, not a servlet attribute.
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "mrr.principal";

    private final Store store;
    private final ObjectMapper mapper;

    public TokenFilter(Store store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<String> token = presentedToken(request);
        if (token.isEmpty()) {
            unauthorized(response, "missing_token", "Provide an API token via Authorization: Bearer <token>.");
            return;
        }

        Optional<Principal> principal = store.resolveToken(token.get());
        if (principal.isEmpty()) {
            unauthorized(response, "invalid_token", "The presented API token is not valid.");
            return;
        }

        store.touchToken(token.get());
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        chain.doFilter(request, response);
    }

    /** Accepts {@code Authorization: Bearer <token>} or {@code X-API-Key: <token>}. */
    public static Optional<String> presentedToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.of(authorization.substring(7).trim()).filter(t -> !t.isEmpty());
        }
        String apiKey = request.getHeader("X-API-Key");
        return Optional.ofNullable(apiKey).map(String::trim).filter(t -> !t.isEmpty());
    }

    private void unauthorized(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message, null));
    }
}
