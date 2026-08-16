package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;
import ro.bitboy.mrr.web.ApiException;

import java.time.Instant;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One named instruction document the reviewer may use. A project has a set of these,
 * enabled independently — the same shape as {@code agentTools}.
 *
 * <p>{@code branchPattern} scopes a skill to merge requests whose <em>source</em> branch
 * matches. Blank means every branch. A name with no glob or regex syntax is an exact
 * match (dots are literal). {@code *} / {@code ?} are globs ({@code hotfix/*}); a Java
 * regex ({@code release-\\d+}, {@code hotfix/.*}) is matched against the whole branch.
 */
@Schema(description = "A named skill. Enabled ones that match the MR source branch are "
        + "concatenated into the system prompt.")
public record AgentSkill(
        String id,
        String projectId,
        @Schema(example = "security") String name,
        String body,
        boolean enabled,
        @Schema(description = "Glob or Java regex against the MR source branch. Blank = every "
                + "branch. `hotfix` is exact, `hotfix/*` is a prefix, `release-\\d+` is a regex.",
                example = "hotfix/*", nullable = true) String branchPattern,
        int position,
        Instant createdAt
) {

    static final int MAX_PATTERN_CHARS = 200;

    /**
     * Whether this skill should be in the prompt for an MR coming from {@code sourceBranch}.
     * Disabled skills are a separate gate — this only answers the branch question.
     * A stored pattern that does not compile is treated as a miss, not a review failure.
     */
    public boolean appliesTo(String sourceBranch) {
        return matches(branchPattern, sourceBranch);
    }

    public static boolean matches(String pattern, String sourceBranch) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        if (sourceBranch == null || sourceBranch.isBlank()) {
            return false;
        }
        try {
            return Pattern.compile(toRegex(pattern.trim())).matcher(sourceBranch).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Normalise for storage: blank becomes {@code null}, otherwise trimmed and proven to
     * compile. Call on create/update so a typo is a 400, not a silently skipped skill.
     */
    public static String validatePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        String trimmed = pattern.trim();
        if (trimmed.length() > MAX_PATTERN_CHARS) {
            throw ApiException.badRequest("branchPattern is too long");
        }
        try {
            Pattern.compile(toRegex(trimmed));
        } catch (PatternSyntaxException e) {
            throw ApiException.badRequest("branchPattern is not a valid glob or regex: "
                    + e.getDescription());
        }
        return trimmed;
    }

    /**
     * {@code hotfix/*} is what people type; {@code hotfix/.*} is what they mean. A name
     * with no glob or regex syntax — including dots, as in {@code release-1.0} — is an
     * exact match. {@code *} / {@code ?} with no other regex syntax are globs. Anything
     * with backslashes, groups, character classes, or {@code .*} is a Java regex matched
     * against the whole branch.
     */
    static String toRegex(String pattern) {
        if (isExplicitRegex(pattern)) {
            return pattern;
        }
        if (isGlob(pattern)) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    regex.append(".*");
                } else if (c == '?') {
                    regex.append('.');
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return regex.toString();
        }
        return Pattern.quote(pattern);
    }

    private static boolean isExplicitRegex(String pattern) {
        if (pattern.contains(".*") || pattern.contains(".+") || pattern.contains(".?")) {
            return true;
        }
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' || c == '^' || c == '$' || c == '+' || c == '(' || c == ')'
                    || c == '[' || c == ']' || c == '{' || c == '}' || c == '|') {
                return true;
            }
        }
        return false;
    }

    private static boolean isGlob(String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
    }
}
