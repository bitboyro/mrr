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
    void httpMessageIncludesResponseBody() {
        ModelException error = ModelException.fromHttp(400,
                "{\"error\":{\"message\":\"max_tokens must be less than 8192\"}}");
        assertThat(error.getMessage())
                .contains("HTTP 400")
                .contains("max_tokens must be less than 8192");
    }

    @Test
    void httpMessageOmitsColonWhenBodyIsBlank() {
        assertThat(ModelException.fromHttp(500, "").getMessage())
                .isEqualTo("Model endpoint failed with HTTP 500");
    }

    @Test
    void httpMessageTruncatesLongBody() {
        String longBody = "x".repeat(500);
        ModelException error = ModelException.fromHttp(400, longBody);
        assertThat(error.getMessage()).hasSize("Model endpoint failed with HTTP 400: ".length() + 240);
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
