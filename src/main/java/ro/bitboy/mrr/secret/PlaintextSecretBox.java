package ro.bitboy.mrr.secret;

/**
 * Stores secrets exactly as they arrive. Selected when no key is configured, which keeps
 * {@code docker compose up} working with no setup — at the cost of a database file that is
 * worth stealing. {@link SecretBoxConfig} says so in the log on every boot.
 */
public final class PlaintextSecretBox implements SecretBox {

    @Override
    public String seal(String plaintext) {
        return plaintext;
    }

    /**
     * Refuses a sealed value instead of returning it. Without this, dropping the key from the
     * environment would quietly post {@code mrr1.…} to GitLab as a PAT and surface as a 401
     * with nothing pointing at the cause.
     */
    @Override
    public String open(String stored) {
        if (stored != null && stored.startsWith(AesGcmSecretBox.VERSION + ".")) {
            throw new IllegalStateException(
                    "stored secret is sealed, but no key is configured. Set MRR_SECRET_KEY (or "
                            + "MRR_SECRET_KEY_FILE) back to the key that wrote this database.");
        }
        return stored;
    }

    @Override
    public String describe() {
        return "plaintext (no key configured)";
    }
}
