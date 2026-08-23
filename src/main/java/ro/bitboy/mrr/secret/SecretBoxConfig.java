package ro.bitboy.mrr.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Picks the {@link SecretBox} once, at startup, from configuration — the same shape as
 * {@code ReviewModelFactory} switching on {@code project.provider()}. Adding a third
 * implementation (a KMS, say) means a new branch here and nothing else.
 */
@Configuration
public class SecretBoxConfig {

    private static final Logger log = LoggerFactory.getLogger(SecretBoxConfig.class);

    /**
     * Fails startup on a key that is present but unusable, and on two keys where one is
     * expected. Booting into plaintext because someone fat-fingered the env var is the one
     * outcome worth refusing outright — it would look exactly like a working deployment.
     */
    @Bean
    public SecretBox secretBox(SecretProperties properties) {
        if (properties.hasBothKeys()) {
            throw new IllegalStateException(
                    "MRR_SECRET_KEY and MRR_SECRET_KEY_FILE are both set. Supply exactly one: "
                            + "nothing here can tell which of them sealed the database, and "
                            + "silently preferring one is how a key rotation ends up writing "
                            + "rows nobody can open.");
        }

        if (!properties.hasKey()) {
            log.warn("SECRETS ARE NOT ENCRYPTED. GitLab tokens, webhook secrets, and model keys "
                    + "are stored in plaintext; anyone holding a copy of the database file holds "
                    + "them too. Set MRR_SECRET_KEY or MRR_SECRET_KEY_FILE to encrypt them.");
            return new PlaintextSecretBox();
        }

        String material = properties.hasInlineKey()
                ? properties.key()
                : SecretKeys.read(Path.of(properties.keyFile().trim()));

        SecretBox box = new AesGcmSecretBox(SecretKeys.parse(material));
        log.info("secrets at rest: {}", box.describe());
        return box;
    }
}
