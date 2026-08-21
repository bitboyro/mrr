package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.util.List;

/**
 * Users and tokens.
 *
 * <p>Rule 2 of the auth model lives here: <b>only an ADMIN token may mint a token.</b> A USER
 * may list and revoke its own tokens and nothing else. The root token printed on first boot
 * is the only way into a fresh instance, and this is what it unlocks.
 */
@RestController
@Tag(name = "Users and tokens",
     description = """
             Identity. Only an ADMIN token can create a user or mint a token — this is the \
             single rule the whole multi-tenant model rests on. A USER token can list and \
             revoke its own tokens and do nothing else here.""")
public class AdminController {

    private final Store store;

    public AdminController(Store store) {
        this.store = store;
    }

    @Operation(summary = "Who the presented token belongs to",
            description = "Used by the web UI after a token is pasted. No secrets.")
    @GetMapping("/api/me")
    public Principal me(Principal caller) {
        return caller;
    }

    @Operation(summary = "Create a user",
            description = "Admin only. A new user owns no projects until it registers one.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "`name` missing", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @PostMapping("/api/users")
    @ResponseStatus(HttpStatus.CREATED)
    public User createUser(Principal caller, @RequestBody CreateUser request) {
        requireAdmin(caller);
        ApiException.requireBody(request);
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("name is required");
        }
        Role role = request.role() == null ? Role.USER : request.role();
        return store.createUser(request.name().trim(), role);
    }

    @Operation(summary = "List all users", description = "Admin only.")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @GetMapping("/api/users")
    public List<User> listUsers(Principal caller) {
        requireAdmin(caller);
        return store.listUsers();
    }

    /**
     * Mints a token. The plaintext in the response is the only time it exists outside the
     * caller's hands — the database holds a SHA-256 hash and a display prefix.
     */
    @Operation(summary = "Mint an API token",
            description = """
                    Admin only. Omit `userId` to mint one for yourself.

                    The plaintext token in the response exists nowhere else — the database \
                    holds only its SHA-256 hash and a display prefix. There is no endpoint \
                    that can return it again.""")
    @ApiResponse(responseCode = "201", description = "Created; the response carries the only copy of the secret")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such `userId`", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @PostMapping("/api/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedToken createToken(Principal caller, @RequestBody CreateToken request) {
        requireAdmin(caller);
        ApiException.requireBody(request);

        String userId = request.userId() == null ? caller.userId() : request.userId();
        store.findUser(userId).orElseThrow(() -> ApiException.notFound("user"));

        String name = request.name() == null || request.name().isBlank() ? "token" : request.name();
        return new CreatedToken(store.createToken(userId, name), userId, name);
    }

    /** Own tokens only — metadata, never the secret. */
    @Operation(summary = "List your own tokens",
            description = "Metadata only — prefix, name, and last use. Never the secret.")
    @GetMapping("/api/tokens")
    public List<Store.TokenInfo> listTokens(Principal caller) {
        return store.listTokens(caller.userId());
    }

    /** Scoped to the caller, so a USER can never revoke somebody else's access. */
    @Operation(summary = "Revoke one of your own tokens",
            description = """
                    Scoped to the caller, so a USER can never revoke somebody else's access. \
                    Revoking the token you are authenticating with immediately locks you out.""")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @ApiResponse(responseCode = "404", description = "No such token, or not yours", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @DeleteMapping("/api/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteToken(Principal caller, @PathVariable String id) {
        if (!store.deleteToken(id, caller.userId())) {
            throw ApiException.notFound("token");
        }
    }

    private void requireAdmin(Principal caller) {
        if (!caller.isAdmin()) {
            throw ApiException.forbidden("Only an admin token can perform this operation.");
        }
    }

    @Schema(description = "New user.")
    public record CreateUser(
            @Schema(description = "Unique, human-readable.", example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "Defaults to USER. ADMIN can mint tokens and see every project.", example = "USER")
            Role role) {
    }

    @Schema(description = "Token request.")
    public record CreateToken(
            @Schema(description = "Whose token this is. Omit to mint one for yourself.")
            String userId,
            @Schema(description = "Label to recognise it by later. Defaults to \"token\".", example = "ci")
            String name) {
    }

    @Schema(description = "A freshly minted token. The only time the secret is readable.")
    public record CreatedToken(
            @Schema(description = "The secret. Store it now — it cannot be retrieved again.",
                    example = "mrr_ec031d0820e28221c5584065aa46566006e1fbeeb4cdb587815e80df5096d326")
            String token,
            @Schema(description = "Owner of the token.") String userId,
            @Schema(description = "The label given at creation.") String name) {
    }
}
