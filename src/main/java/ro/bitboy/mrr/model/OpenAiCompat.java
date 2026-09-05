package ro.bitboy.mrr.model;

import java.util.List;

/**
 * Which OpenAI wire format to use. GPT-5.4 and 5.6 reject function tools on
 * {@code /v1/chat/completions} unless reasoning is disabled; the durable path is
 * {@code /v1/responses}. Ollama, vLLM, llama.cpp, and other compatible endpoints only
 * speak chat-completions — a custom base URL never switches.
 */
final class OpenAiCompat {

    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private OpenAiCompat() {
    }

    static boolean firstParty(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        String normalised = baseUrl.replaceAll("/+$", "").toLowerCase(java.util.Locale.ROOT);
        return normalised.equals("https://api.openai.com/v1")
                || normalised.equals("https://api.openai.com");
    }

    /**
     * {@code gpt-5.4}, {@code gpt-5.6-luna}, {@code gpt-5.10} — not {@code gpt-5},
     * {@code gpt-5.1}–{@code gpt-5.3}, or {@code gpt-4o}.
     */
    static boolean responsesFamily(String model) {
        if (model == null) {
            return false;
        }
        String id = model.trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.startsWith("gpt-5.")) {
            return false;
        }
        String minor = id.substring("gpt-5.".length()).split("-", 2)[0];
        try {
            return Integer.parseInt(minor) >= 4;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Stay on Responses for the rest of a tool loop even when the last turn advertises no
     * tools, otherwise the conversation would jump endpoints mid-flight.
     */
    static boolean useResponses(String baseUrl, String model, List<ReviewModel.ToolSpec> tools,
                                List<ReviewModel.Turn> turns) {
        if (!firstParty(baseUrl) || !responsesFamily(model)) {
            return false;
        }
        return (tools != null && !tools.isEmpty()) || hasEcho(turns);
    }

    /**
     * Chat-completions' token-limit field. A first-party {@link #responsesFamily} model
     * 400s there on {@code max_tokens} — tools or not — and wants {@code max_completion_tokens}
     * instead; the merge turn has no tools, so it never reaches {@link #useResponses} and
     * still needs this on the chat-completions path.
     */
    static String chatMaxTokensField(String baseUrl, String model) {
        return firstParty(baseUrl) && responsesFamily(model) ? "max_completion_tokens" : "max_tokens";
    }

    static boolean hasEcho(List<ReviewModel.Turn> turns) {
        if (turns == null) {
            return false;
        }
        for (ReviewModel.Turn turn : turns) {
            if (turn.echo() != null) {
                return true;
            }
        }
        return false;
    }
}
