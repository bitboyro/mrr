package ro.bitboy.mrr.model;

/**
 * Why a review stopped before posting. Anything other than a successful verdict is fail-closed:
 * the UI shows it, the forge is not touched.
 */
public enum FailureKind {

    /** Provider rejected the call because the account is out of credit / quota. */
    QUOTA,
    /** Provider asked us to slow down. Distinct from quota so the UI can say "retry shortly". */
    RATE_LIMIT,
    /** Key rejected — 401/403 from the model endpoint. */
    AUTH,
    /** Model HTTP client could not complete a request (network, 5xx, malformed). */
    PROVIDER,
    /**
     * The model call finished, but the assistant text was not a usable JSON verdict
     * (blank, prose, or missing {@code summary}). Distinct from {@link #PROVIDER}:
     * the endpoint was reached.
     */
    OUTPUT,
    /**
     * An MCP client the agent uses (none today; Covrigi and memory later) could not be reached.
     * Same fail-closed rule: show in the UI, post nothing.
     */
    MCP,
    /**
     * The forge itself failed before we had a verdict to post.
     *
     * <p>{@link #GITLAB} is what this used to be called, and it stays because it is stored as
     * text in {@code reviews.error_kind} on every historical row and must keep deserialising.
     * Nothing writes it any more.
     */
    FORGE,
    /** @deprecated historical rows only — {@link #FORGE} is what new failures record. */
    @Deprecated
    GITLAB,
    /** The model declined to produce a review. */
    REFUSAL,
    UNKNOWN;

    public static FailureKind of(Throwable error) {
        if (error instanceof ModelException model) {
            return model.kind();
        }
        String haystack = error.getClass().getSimpleName() + " " + String.valueOf(error.getMessage());
        if (haystack.contains("forge_error") || haystack.contains("gitlab_error")
                || haystack.startsWith("ApiException GitLab ")) {
            return FORGE;
        }
        return UNKNOWN;
    }

    /** Short line the UI can show without the exception class name. */
    public String headline() {
        return switch (this) {
            case QUOTA -> "Model API budget exhausted. Nothing was posted.";
            case RATE_LIMIT -> "Model API rate-limited. Nothing was posted.";
            case AUTH -> "Model API key was rejected. Nothing was posted.";
            case PROVIDER -> "Model provider could not be reached. Nothing was posted.";
            case OUTPUT -> "The model finished without a usable JSON verdict. Nothing was posted.";
            case MCP -> "An MCP client the agent needs is unavailable. Nothing was posted.";
            case FORGE -> "The forge could not be reached. Nothing was posted.";
            case GITLAB -> "GitLab could not be reached. Nothing was posted.";
            case REFUSAL -> "The model declined to review. Nothing was posted.";
            case UNKNOWN -> "The review failed before posting.";
        };
    }
}
