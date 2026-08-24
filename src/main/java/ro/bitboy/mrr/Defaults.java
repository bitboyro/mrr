package ro.bitboy.mrr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.BundledSkills;
import ro.bitboy.mrr.store.Store;

/**
 * What a fresh instance should already have, applied on every boot so an account created
 * before a release still picks up what that release added.
 *
 * <p>Runs after {@code Bootstrap}, which is what creates the first admin — there is nothing to
 * seed a library for until a user exists.
 *
 * <p>Both defaults are additive and remember what they have done: the shared GitLab entry is
 * only created into a catalog that has never had one, and a bundled skill is only offered to
 * an account once. Deleting either is a decision this holds to rather than undoes.
 */
@Component
@Order(20)
public class Defaults implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Defaults.class);

    private final Store store;
    private final BundledSkills bundled;

    public Defaults(Store store, BundledSkills bundled) {
        this.store = store;
        this.bundled = bundled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (store.ensureSharedForgeInstance("gitlab.com", ForgeKind.GITLAB,
                "https://gitlab.com")) {
            log.info("defaults: added the shared gitlab.com catalog entry");
        }
        for (User user : store.listUsers()) {
            int created = store.seedLibrarySkills(user.id(), bundled.all());
            if (created > 0) {
                log.info("defaults: gave {} {} bundled skill(s)", user.name(), created);
            }
        }
    }
}
