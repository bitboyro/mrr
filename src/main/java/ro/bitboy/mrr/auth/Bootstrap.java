package ro.bitboy.mrr.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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

    private void banner(String token) {
        String line = "─".repeat(token.length() + 4);
        System.out.println("┌─ mrr  root admin token " + line.substring(Math.min(line.length(), 24)) + "┐");
        System.out.println("│  " + token + "  │");
        System.out.println("└" + line + "──┘");
        System.out.println("   Shown once. Create users and tokens with it:");
        System.out.println("   curl -H 'Authorization: Bearer " + Ids.prefixOf(token) + "…' "
                + properties.baseUrl() + "/api/users");
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
