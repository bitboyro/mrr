package ro.bitboy.mrr.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.bitboy.mrr.auth.Principal;

import java.util.List;

@Configuration
public class OpenApiConfig {

    static {
        // Principal is resolved from the validated token by PrincipalArgumentResolver, not
        // supplied by the caller. Without this springdoc advertises it as a query parameter
        // and publishes a schema for it, which would have clients trying to send their own.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Principal.class);
    }

    @Value("${mrr.base-url:http://localhost:8130}")
    private String baseUrl;

    @Bean
    public OpenAPI mrrOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("mrr")
                        .version("0.1.0")
                        .description(DESCRIPTION)
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server()
                        .url(baseUrl)
                        .description("This mrr instance")))
                .components(new Components()
                        .addSecuritySchemes("token", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .name("Authorization")
                                .description("""
                                        An mrr API token, as `Authorization: Bearer mrr_…`. \
                                        `X-API-Key: mrr_…` is accepted as an alternative and \
                                        is what the MCP endpoint uses.

                                        Tokens are minted by an **admin** token via \
                                        `POST /api/tokens`. The very first one is printed to \
                                        the console on first boot and written to \
                                        `/data/root-token.txt`; it is never recoverable \
                                        afterwards, because only its SHA-256 hash is stored.""")))
                .addSecurityItem(new SecurityRequirement().addList("token"));
    }

    private static final String DESCRIPTION = """
            AI code review for GitLab merge requests.

            A merge request is opened or pushed to, GitLab calls the webhook, mrr pulls the \
            diff, sends it to a model with that project's review instructions, and posts the \
            review back to the MR as a resolvable discussion thread. Every payload, prompt, \
            trace, and response is kept.

            ## Authentication

            Every endpoint under `/api` needs an API token. `/webhooks/gitlab/{projectId}` \
            does **not** — GitLab cannot hold an mrr token, so it authenticates with the \
            per-project secret in `X-Gitlab-Token` instead. `/health` is open.

            ## Authorization

            Three rules govern the whole API:

            1. Only an `ADMIN` token can create users or mint tokens. A `USER` token may list \
               and revoke its own tokens and nothing else.
            2. Projects are owned. A `USER` sees and edits only its own; an `ADMIN` sees all.
            3. Asking for a project you do not own returns **404, not 403** — a 403 would \
               confirm that the id exists.

            ## Reviews are asynchronous

            Anything that calls a model returns `202 Accepted` with a `Location` header to \
            poll. A review moves `PENDING → PROCESSING → READY | FAILED`; `READY` and \
            `FAILED` are terminal, so stop polling when you see one.

            Reviews are keyed on `(project, mr_iid, head_sha)`. A redelivered webhook, or an \
            `update` event that changed only a label, returns the review that already exists \
            rather than spending tokens on a duplicate. A new push is a new head SHA and so a \
            new review.

            ## Secrets

            GitLab tokens, model keys, and webhook secrets are write-only. They are accepted \
            on create and update, and always masked on read (`****ab12`). The webhook secret \
            is returned exactly once, in the response to `POST /api/projects`.
            """;
}
