package ro.bitboy.mrr.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillLibraryControllerTest {

    @TempDir
    Path tempDir;

    private SkillLibraryController skills;
    private Principal alice;

    @BeforeEach
    void setUp() {
        Store store = TestStores.sqlite(tempDir);
        User user = store.createUser("alice", Role.USER);
        alice = new Principal(user.id(), user.name(), user.role());
        skills = new SkillLibraryController(store);
    }

    @Test
    void createRejectsANullBody() {
        assertThatThrownBy(() -> skills.create(alice, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).isEqualTo("body is required");
                });
        assertThat(skills.list(alice)).isEmpty();
    }

    @Test
    void updateRejectsANullBody() {
        var created = skills.create(alice, new SkillLibraryController.LibraryUpsert("sec", "Care."));
        assertThatThrownBy(() -> skills.update(alice, created.id(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThat(skills.list(alice)).extracting(s -> s.body()).containsExactly("Care.");
    }
}
