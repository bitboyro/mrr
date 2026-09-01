package ro.bitboy.mrr.repo;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which module a file belongs to.
 *
 * <p>"Same module" has to mean something before it can be a ranking signal, and in a
 * repository that is Java on the back end and TypeScript on the front, a path prefix means
 * nothing — {@code src/} is three languages. So a file's module is <strong>the nearest
 * ancestor directory containing a build file</strong>. Those are boundaries the repository's
 * own authors declared, rather than ones mrr invented, and the same definition answers the
 * question of how to scope a monorepo.
 *
 * <p>Falls back to the first path segment when a repository declares nothing, which is still
 * better than treating the whole tree as one module.
 */
public final class Modules {

    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "package.json", "go.mod", "Cargo.toml", "pyproject.toml", "setup.py",
            "setup.cfg", "BUILD", "BUILD.bazel", "CMakeLists.txt", "composer.json",
            "Gemfile", "mix.exs");

    private final Set<String> moduleRoots;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private Modules(Set<String> moduleRoots) {
        this.moduleRoots = moduleRoots;
    }

    /** Built once per mirror sync from the paths present at a commit. */
    public static Modules from(List<String> allPaths) {
        Set<String> roots = ConcurrentHashMap.newKeySet();
        for (String path : allPaths) {
            int slash = path.lastIndexOf('/');
            String name = slash < 0 ? path : path.substring(slash + 1);
            if (BUILD_FILES.contains(name)) {
                roots.add(slash < 0 ? "" : path.substring(0, slash));
            }
        }
        return new Modules(roots);
    }

    public static Modules empty() {
        return new Modules(Set.of());
    }

    /** The module owning this path — the deepest declared root above it. */
    public String of(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return cache.computeIfAbsent(path, this::resolve);
    }

    private String resolve(String path) {
        String best = null;
        for (String root : moduleRoots) {
            boolean covers = root.isEmpty() || path.startsWith(root + "/");
            if (covers && (best == null || root.length() > best.length())) {
                best = root;
            }
        }
        if (best != null) {
            return best.isEmpty() ? "(root)" : best;
        }
        int slash = path.indexOf('/');
        return slash < 0 ? "(root)" : path.substring(0, slash);
    }

    public boolean sameModule(String a, String b) {
        return of(a).equals(of(b));
    }
}
