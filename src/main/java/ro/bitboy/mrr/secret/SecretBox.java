package ro.bitboy.mrr.secret;

/**
 * The at-rest transform for the credentials mrr holds on a project's behalf: the GitLab PATs,
 * the webhook secret, and the model key. Everything else in the row stays readable.
 *
 * <p>Deliberately narrow. A {@code SecretBox} maps one string to one string, so the sealed
 * value drops into the same {@code TEXT} column the plaintext used and the choice of database
 * never enters into it — SQLite today, an external Postgres later, identical either way.
 *
 * <p>Implementations must be thread-safe: {@link ro.bitboy.mrr.store.Store} is a singleton
 * shared by every review worker.
 */
public interface SecretBox {

    /** Seals a value for storage. {@code null} in, {@code null} out — a secret that isn't set. */
    String seal(String plaintext);

    /**
     * Reverses {@link #seal}. Fails loudly rather than returning something unusable: handing a
     * half-decrypted string to GitLab as a PAT would surface as a puzzling 401 three layers away.
     *
     * @throws IllegalStateException if the value was not sealed by this box
     */
    String open(String stored);

    /** One line for the startup banner and {@code GET /api/settings}. Never includes the key. */
    String describe();
}
