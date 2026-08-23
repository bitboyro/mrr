package ro.bitboy.mrr.secret;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the key comes from. Supply exactly one: {@code key} for a plain env var,
 * {@code keyFile} to keep it out of the process environment and off {@code docker inspect}.
 * Neither means no encryption; both is a configuration error {@link SecretBoxConfig} refuses
 * to start on.
 */
@ConfigurationProperties(prefix = "mrr.secret")
public record SecretProperties(String key, String keyFile) {

    public boolean hasKey() {
        return notBlank(key) || notBlank(keyFile);
    }

    /** Both sources set. Nothing here can tell which one the database was sealed with. */
    public boolean hasBothKeys() {
        return notBlank(key) && notBlank(keyFile);
    }

    /** The inline key is the one to use; otherwise the key file is. */
    public boolean hasInlineKey() {
        return notBlank(key);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
