package ro.bitboy.mrr.forge;

import org.springframework.stereotype.Component;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.Project;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the adapter for a project.
 *
 * <p>Copies the selection pattern the repository already uses twice rather than inventing a
 * third: {@code ReviewModelFactory.forProject(project)} switches on {@code project.provider()}
 * for the LLM vendor, {@code SecretBoxConfig} chooses a {@code SecretBox} once at boot, and this
 * switches on {@code project.forge()}.
 *
 * <p>Unlike the model factory there is nothing to cache. Every adapter is stateless and takes
 * its base URL and token off the project row on each call, so one instance per forge serves
 * every project — including two projects on different self-hosted instances of the same product.
 *
 * <p><strong>Naming trap.</strong> {@code Project.provider} already means the LLM vendor. The
 * column this reads is {@code forge}. Never {@code provider}; getting that wrong would read as
 * correct in a review.
 */
@Component
public class ForgeFactory {

    private final Map<ForgeKind, Forge> adapters = new EnumMap<>(ForgeKind.class);

    public ForgeFactory(List<Forge> forges) {
        for (Forge forge : forges) {
            adapters.put(forge.kind(), forge);
        }
    }

    public Forge forProject(Project project) {
        ForgeKind kind = project.forge() == null ? ForgeKind.DEFAULT : project.forge();
        Forge forge = adapters.get(kind);
        if (forge == null) {
            throw new IllegalStateException("no adapter registered for forge " + kind);
        }
        return forge;
    }

    /** The capabilities of the host this project sits on. */
    public ForgeCapabilities capabilitiesOf(Project project) {
        return forProject(project).capabilities(project);
    }

    /**
     * One adapter for every project. For tests, which drive a {@code FakeForge} rather than a
     * network, and for nothing else — production always resolves by {@code project.forge()}.
     */
    public static ForgeFactory fixed(Forge forge) {
        return new ForgeFactory(List.of(forge)) {
            @Override
            public Forge forProject(Project project) {
                return forge;
            }
        };
    }
}
