package ro.bitboy.mrr.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelExceptionTest {

    @Test
    void quotaStatusIsQuotaEvenOn429() {
        ModelException error = ModelException.fromHttp(429,
                "{\"error\":{\"code\":\"insufficient_quota\",\"type\":\"insufficient_quota\"}}");
        assertThat(error.kind()).isEqualTo(FailureKind.QUOTA);
    }

    @Test
    void plain429IsRateLimit() {
        ModelException error = ModelException.fromHttp(429,
                "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        assertThat(error.kind()).isEqualTo(FailureKind.RATE_LIMIT);
    }

    @Test
    void rejectedKeyIsAuth() {
        assertThat(ModelException.fromHttp(401, "invalid_api_key").kind())
                .isEqualTo(FailureKind.AUTH);
        assertThat(ModelException.fromHttp(403, "").kind())
                .isEqualTo(FailureKind.AUTH);
    }

    @Test
    void otherHttpIsProvider() {
        assertThat(ModelException.fromHttp(502, "bad gateway").kind())
                .isEqualTo(FailureKind.PROVIDER);
    }

    @Test
    void anthropicClassNamesClassify() {
        assertThat(ModelException.fromSignal(
                "com.anthropic.errors.RateLimitException", "429", null).kind())
                .isEqualTo(FailureKind.RATE_LIMIT);
        assertThat(ModelException.fromSignal(
                "com.anthropic.errors.AuthenticationException", "invalid x-api-key", null).kind())
                .isEqualTo(FailureKind.AUTH);
    }
}
