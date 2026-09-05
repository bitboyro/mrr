package ro.bitboy.mrr.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * First boot only: create the admin and print its token. There is no other way into a fresh
 * instance — mrr has no password login and no external identity provider, so this token is the
 * root of the whole trust chain.
 *
 * <p>It is shown once. Nothing in mrr can recover it afterwards; the database holds only its
 * hash. Losing it means adding another admin by hand or starting over.
 */
@Component
@Order(10)
public class Bootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final Store store;
    private final MrrProperties properties;

    public Bootstrap(Store store, MrrProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Printed every boot so a watcher can tell the process came up and which public URL
        // GitLab should be calling. A reminted cloudflared hostname will not match this.
        log.info("mrr ready baseUrl={} workers={}",
                properties.baseUrl(), properties.review().workers());

        if (store.countUsers() > 0) {
            return;
        }

        User admin = store.createUser("admin", Role.ADMIN);
        String token = store.createToken(admin.id(), "root");

        banner(token);
        writeTokenFile(token);
    }

    /**
     * The box the root token is printed in.
     *
     * <p>Every row is {@code inner} characters wide between the corners. It used to be built
     * from three unrelated expressions — a title padded by slicing a rule, a middle row of
     * {@code token + 4}, and a bottom of {@code token + 7} — so the bottom edge overhung by
     * two columns on every boot, and a token shorter than the title collapsed the box
     * entirely.
     */
    private void banner(String token) {
        box(token).forEach(System.out::println);
        System.out.println("   Shown once. Create users and tokens with it:");
        System.out.println("   curl -H 'Authorization: Bearer " + Ids.prefixOf(token) + "…' "
                + properties.baseUrl() + "/api/users");
    }

    static java.util.List<String> box(String token) {
        String title = "─ mrr  root admin token ";
        int inner = Math.max(title.length(), token.length() + 4);
        return java.util.List.of(
                "┌" + title + "─".repeat(inner - title.length()) + "┐",
                "│" + pad("  " + token + "  ", inner) + "│",
                "└" + "─".repeat(inner) + "┘");
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private void writeTokenFile(String token) {
        String target = properties.rootTokenFile();
        if (target == null || target.isBlank()) {
            return;
        }
        Path path = Path.of(target);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, token + System.lineSeparator());
            trySetOwnerOnly(path);
            log.info("root token written to {}", path);
        } catch (IOException e) {
            // Not fatal: the banner above is the primary delivery. A read-only or non-POSIX
            // volume should not stop the service from starting.
            log.warn("could not write root token file {}: {}", path, e.getMessage());
        }
    }

    private void trySetOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("could not restrict permissions on {} — treat it as world-readable", path);
        }
    }

    /** Exposed so tests can assert the set of roles that may mint tokens. */
    public static Set<Role> minters() {
        return Set.of(Role.ADMIN);
    }
}
