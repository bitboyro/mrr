package ro.bitboy.mrr.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.model.Role;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentKeyScopeTest {
    @TempDir Path dir;

    @Test
    void anAdminsAgentKeyCannotMintOrRevokeTokensButCanSubmitReviews() throws Exception {
        var store = TestStores.sqlite(dir);
        var admin = store.createUser("root", Role.ADMIN);
        var project = TestStores.persist(store, TestStores.project(admin.id(), "app", "hook"));
        String token = store.createAgentKey(admin.id(), "agent", List.of(project.id()));
        var filter = new TokenFilter(store, new ObjectMapper());
        for (String[] route : List.of(new String[]{"POST", "/api/tokens"},
                new String[]{"DELETE", "/api/tokens/root-token"},
                new String[]{"GET", "/api/tokens"},
                new String[]{"PUT", "/api/projects/" + project.id()})) {
            var request = new MockHttpServletRequest(route[0], route[1]);
            request.addHeader("X-API-Key", token);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(chain.getRequest()).isNull();
        }
        var request = new MockHttpServletRequest("POST", "/api/projects/" + project.id()
                + "/merge-requests/7/agent-reviews");
        request.addHeader("X-API-Key", token);
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(((Principal) request.getAttribute(TokenFilter.PRINCIPAL_ATTRIBUTE)).isAdmin()).isFalse();
    }
}
