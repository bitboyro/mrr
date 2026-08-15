package ro.bitboy.mrr.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Replaces Spring AI's auto-configured streamable-HTTP transport so the caller's API token
 * travels from the HTTP request into the MCP tool body.
 *
 * <p>Two things matter here:
 *
 * <ul>
 *   <li>The context extractor is the only supported way to get a header into a tool call. The
 *       exchange carries it, not a ThreadLocal — tool execution is not guaranteed to run on
 *       the HTTP request thread.
 *   <li>Every property the auto-configuration applied must be re-applied. {@code keepAliveInterval}
 *       especially: dropping it silently kills idle SSE sessions behind proxies.
 * </ul>
 */
@Configuration
public class McpTransportConfig {

    private static final Logger log = LoggerFactory.getLogger(McpTransportConfig.class);

    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String HEADER_AUTHORIZATION = "Authorization";

    /** Registers the {@code @Tool}-annotated methods on {@link McpTools} with the server. */
    @Bean
    public ToolCallbackProvider mrrToolCallbacks(McpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            ObjectMapper objectMapper, McpServerStreamableHttpProperties properties) {

        log.info("mcp streamable transport: endpoint={} keepAlive={}",
                properties.getMcpEndpoint(), properties.getKeepAliveInterval());

        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(request -> {
                    Map<String, Object> context = new HashMap<>();
                    String token = extractToken(
                            request.headers().firstHeader(HEADER_API_KEY),
                            request.headers().firstHeader(HEADER_AUTHORIZATION));
                    if (token != null) {
                        context.put(McpTools.TOKEN_KEY, token);
                    }
                    return McpTransportContext.create(context);
                })
                .build();
    }

    /** Accepts {@code X-API-Key: <token>} or {@code Authorization: Bearer <token>}. */
    private String extractToken(String apiKeyHeader, String authorizationHeader) {
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            return apiKeyHeader.trim();
        }
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String candidate = authorizationHeader.substring(7).trim();
            return candidate.isEmpty() ? null : candidate;
        }
        return null;
    }
}
