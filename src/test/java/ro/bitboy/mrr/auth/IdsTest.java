package ro.bitboy.mrr.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void mintsDistinctPrefixedTokens() {
        String a = Ids.newToken();
        String b = Ids.newToken();

        assertThat(a).startsWith(Ids.TOKEN_PREFIX).isNotEqualTo(b);
        assertThat(Ids.prefixOf(a)).hasSize(Ids.TOKEN_PREFIX.length() + 8);
        assertThat(a).startsWith(Ids.prefixOf(a));
    }

    @Test
    void hashesAreStableAndOneWay() {
        String token = Ids.newToken();

        assertThat(Ids.sha256(token)).isEqualTo(Ids.sha256(token));
        assertThat(Ids.sha256(token)).isNotEqualTo(Ids.sha256(token + "x"));
        assertThat(Ids.sha256(token)).doesNotContain(token);
    }

    /**
     * The webhook secret check runs on an unauthenticated endpoint, so a timing side channel
     * would let an attacker recover the secret byte by byte.
     */
    @Test
    void secureEqualsMatchesOnlyIdenticalValuesAndNeverNull() {
        assertThat(Ids.secureEquals("secret", "secret")).isTrue();
        assertThat(Ids.secureEquals("secret", "secrey")).isFalse();
        assertThat(Ids.secureEquals("secret", "secret ")).isFalse();
        assertThat(Ids.secureEquals(null, "secret")).isFalse();
        assertThat(Ids.secureEquals("secret", null)).isFalse();
        assertThat(Ids.secureEquals(null, null)).isFalse();
    }
}
