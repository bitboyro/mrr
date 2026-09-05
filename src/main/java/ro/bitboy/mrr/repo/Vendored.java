package ro.bitboy.mrr.repo;

import java.util.List;

/**
 * Paths that are in the repository but are not the team's code.
 *
 * <p>Detection is a heuristic and is labelled as one. Getting it wrong pollutes both the
 * co-change scores and the reference ranking, so the list stays conservative: directories
 * whose names are conventions across whole ecosystems, and lock files whose churn says
 * nothing about coupling.
 */
public final class Vendored {

    private static final List<String> DIRECTORIES = List.of(
            "node_modules/", "vendor/", "third_party/", "thirdparty/",
            "target/", "build/", "dist/", "out/", ".git/", ".gradle/", "__pycache__/",
            "site-packages/", ".venv/", "venv/");

    private static final List<String> FILES = List.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "go.sum",
            "Cargo.lock", "poetry.lock", "Gemfile.lock", "composer.lock");

    private static final List<String> SUFFIXES = List.of(
            ".min.js", ".min.css", ".map", ".pb.go", "_pb2.py", ".generated.ts", ".g.dart");

    private Vendored() {
    }

    /** Whether this path should be kept out of scoring and ranking entirely. */
    public static boolean ignored(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        for (String dir : DIRECTORIES) {
            if (normalized.startsWith(dir) || normalized.contains("/" + dir)) {
                return true;
            }
        }
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (FILES.contains(name)) {
            return true;
        }
        for (String suffix : SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a path looks like a test, which is ranked lower but never excluded. */
    public static boolean test(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/test/") || lower.contains("/tests/")
                || lower.startsWith("test/") || lower.startsWith("tests/")
                || lower.contains("/spec/") || lower.endsWith("_test.go")
                || lower.endsWith("test.java") || lower.endsWith("tests.java")
                || lower.endsWith(".test.ts") || lower.endsWith(".test.tsx")
                || lower.endsWith(".test.js") || lower.endsWith(".spec.ts")
                || lower.endsWith(".spec.js") || lower.endsWith("_test.py")
                || lower.startsWith("test_") || lower.contains("/test_");
    }
}
