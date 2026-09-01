package ro.bitboy.mrr.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import ro.bitboy.mrr.repo.Languages.Grammar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a file into a syntax tree, and caches the result by content.
 *
 * <p><strong>Keyed on blob sha</strong>, which JGit hands over for free. The key is
 * content-addressed, so an entry is correct across refs and can never go stale — which is
 * exactly the pattern this cache exists for, since pre-fetch and then several review slices
 * all ask about the same unchanged files at the same commit. Where there is no mirror and the
 * bytes came from the forge API instead, the key is derived from path, ref and length.
 *
 * <p><strong>Bounded by source bytes, not entry count.</strong> Entry count is meaningless
 * when the files in a repository run from 200 bytes to two megabytes.
 *
 * <p><strong>A published tree is never closed here.</strong> {@link TSTree} owns a native
 * allocation and closing it flips a flag that makes every later read throw, so closing one
 * that a caller is still traversing turns a cache detail into that caller's failure. Once a
 * {@code Parsed} has been handed out this class cannot know when the caller is finished, so
 * eviction drops the reference and the binding's own {@link java.lang.ref.Cleaner} reclaims
 * it once it is unreachable. What bounds memory is the byte budget on what is retained, not
 * the timing of the free. Only a tree that never escaped — a discarded fallback, or a
 * duplicate parse that lost a race — is closed explicitly, because for those the answer to
 * "is anyone using this" is known.
 *
 * <p>{@link TSParser} is not thread-safe and mrr runs several reviews at once, so one is
 * built per parse and closed immediately. Construction is trivial next to the parse itself.
 */
public final class Ast {

    private static final Logger log = LoggerFactory.getLogger(Ast.class);

    /**
     * A parsed file. {@code recovered} means the grammar hit errors and the tree is a best
     * effort — the enclosing definition may be wrong, and callers say so rather than hide it.
     */
    public record Parsed(Source source, Grammar grammar, TSTree tree, boolean recovered) {

        public TSNode root() {
            return tree.getRootNode();
        }
    }

    private final long maxCacheBytes;
    private final long maxFileBytes;

    /** Access-ordered, so the eldest entry is the least recently used. */
    private final LinkedHashMap<String, Parsed> cache;
    private long cachedBytes;

    public Ast(long maxCacheBytes, long maxFileBytes) {
        this.maxCacheBytes = maxCacheBytes;
        this.maxFileBytes = maxFileBytes;
        this.cache = new LinkedHashMap<>(64, 0.75f, true);
    }

    /**
     * Parses, or returns the cached tree for this exact content.
     *
     * <p>Empty is a normal answer, not a failure: no grammar for the extension, a file past
     * the size ceiling, binary content, or a parser that threw. Every one of those means the
     * caller answers at {@link Tier#TEXTUAL} and reports it.
     */
    public Optional<Parsed> parse(Source source) {
        if (source == null || source.binary() || source.byteLength() > maxFileBytes) {
            return Optional.empty();
        }
        List<Grammar> candidates = Languages.forPath(source.path());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String key = keyFor(source);
        synchronized (this) {
            Parsed hit = cache.get(key);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        Parsed parsed = parseWith(candidates, source);
        if (parsed == null) {
            return Optional.empty();
        }
        return Optional.of(publish(key, parsed));
    }

    /**
     * The cache key: this path, holding exactly these bytes.
     *
     * <p>Both halves are load-bearing and each was learned the hard way.
     *
     * <p>The path is needed because git is content-addressed, so files with identical bytes
     * share one blob sha — empty {@code __init__.py} files, re-export barrels, generated
     * stubs. Keying on content alone handed the second path the first path's {@code Source},
     * so its symbols were reported under the wrong file name, and an identical {@code .ts} and
     * {@code .js} pair would have shared a tree built by the wrong grammar.
     *
     * <p>The content hash is needed because anything derived from a ref can go stale. A key of
     * path, ref and length looked immutable enough until you point {@code symbol_at} at a
     * branch: {@code main} moves to different content of the same size and the cache returns
     * the previous revision's tree, with line ranges describing code that is no longer there.
     *
     * <p>Computed here rather than accepted from callers, so neither half can be reintroduced
     * by a call site. That is why {@code parse} takes no key at all.
     */
    private static String keyFor(Source source) {
        return source.path() + "\u0000" + source.contentHash();
    }

    /**
     * Tries each candidate grammar in turn and takes the first clean parse.
     *
     * <p>The fallback is not defensive coding, it is required: the TypeScript grammar rejects
     * JSX outright, so a {@code .tsx} file parses to a single ERROR node under it and parses
     * cleanly under JavaScript. Neither grammar covers both, so the extension names an order
     * and the first clean result wins. When every candidate errors, the best one is kept and
     * marked recovered — tree-sitter's error recovery usually still finds the definitions,
     * and a recovered tree that says so is more useful than no answer.
     */
    private Parsed parseWith(List<Grammar> candidates, Source source) {
        String text = source.text();
        Parsed fallback = null;
        for (Grammar grammar : candidates) {
            try (TSParser parser = new TSParser()) {
                if (!parser.setLanguage(Languages.load(grammar))) {
                    log.warn("grammar {} would not load — skipping it for {}",
                            grammar.name(), source.path());
                    continue;
                }
                TSTree tree = parser.parseString(null, text);
                if (!tree.getRootNode().hasError()) {
                    if (fallback != null) {
                        // A .ts file carrying JSX takes exactly this path every time: the
                        // TypeScript grammar errors, JavaScript succeeds. The error tree never
                        // escaped, so closing it is safe and not doing so leaks on every parse.
                        fallback.tree().close();
                    }
                    return new Parsed(source, grammar, tree, false);
                }
                if (fallback == null) {
                    fallback = new Parsed(source, grammar, tree, true);
                } else {
                    tree.close();
                }
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                log.warn("parsing {} as {} failed: {}", source.path(), grammar.name(),
                        e.getMessage());
            }
        }
        return fallback;
    }

    /**
     * Stores a freshly parsed tree, or yields to one that got there first.
     *
     * <p>Two reviews can miss the same key and both parse. Whoever loses that race closes its
     * own tree — safe, because it was never returned to anyone — and returns the winner's. The
     * alternative, replacing and closing the entry already in the cache, would close a tree
     * another thread had already been handed and was still reading.
     */
    private Parsed publish(String key, Parsed parsed) {
        synchronized (this) {
            Parsed existing = cache.get(key);
            if (existing != null) {
                parsed.tree().close();
                return existing;
            }
            cache.put(key, parsed);
            cachedBytes += parsed.source().byteLength();
            evictWhileOverBudget();
            return parsed;
        }
    }

    /**
     * Drops entries until the byte budget is met.
     *
     * <p>Deliberately does not close what it drops. An evicted entry may be one a caller is
     * still traversing, and this class has no way to know; the binding's Cleaner frees it once
     * it is genuinely unreachable. The budget bounds what is <em>retained</em>, which is what
     * it is for.
     */
    private void evictWhileOverBudget() {
        var it = cache.entrySet().iterator();
        while (cachedBytes > maxCacheBytes && it.hasNext()) {
            Map.Entry<String, Parsed> eldest = it.next();
            it.remove();
            cachedBytes -= eldest.getValue().source().byteLength();
        }
    }

    /** Bytes currently held. For tests and for the stats page. */
    public synchronized long cachedBytes() {
        return cachedBytes;
    }

    public synchronized int cachedEntries() {
        return cache.size();
    }

    /**
     * Forgets everything cached.
     *
     * <p>Same reasoning as eviction: the trees are dropped rather than closed, because a
     * caller may still hold one.
     */
    public synchronized void clear() {
        cache.clear();
        cachedBytes = 0;
    }
}
