package ro.bitboy.mrr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.review.Reviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Records {@code submit} without running a review. */
public final class FakeReviewer extends Reviewer {

    public final List<String> submitted = new ArrayList<>();
    public boolean accept = true;
    public int aligns;

    public FakeReviewer() {
        super(null, ForgeFactory.fixed(new FakeForge()), TestStores.properties(),
                new ObjectMapper(), null, p -> {
                    throw new IllegalStateException("no model");
                }, Executors.newSingleThreadExecutor());
    }

    @Override
    public boolean submit(String reviewId) {
        submitted.add(reviewId);
        return accept;
    }

    @Override
    public void alignForge(Project project, long mrIid) {
        aligns++;
    }
}
