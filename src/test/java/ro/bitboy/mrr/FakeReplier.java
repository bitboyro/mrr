package ro.bitboy.mrr;

import ro.bitboy.mrr.review.Replier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Records reply submits without calling a model. */
public final class FakeReplier extends Replier {

    public record Call(String projectId, long mrIid, String discussionId, String sourceNoteId,
                       String note, String author) {
    }

    public final List<Call> calls = new ArrayList<>();

    public FakeReplier() {
        super(null, null, TestStores.properties(), p -> {
            throw new IllegalStateException("no model");
        }, Executors.newSingleThreadExecutor());
    }

    @Override
    public void submit(String projectId, long mrIid, String discussionId, String sourceNoteId,
                       String note, String author) {
        calls.add(new Call(projectId, mrIid, discussionId, sourceNoteId, note, author));
    }
}
