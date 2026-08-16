package ro.bitboy.mrr;

import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.review.Replier;

import java.util.ArrayList;
import java.util.List;

/** Records reply submits without calling a model. */
public final class FakeReplier extends Replier {

    public record Call(String projectId, long mrIid, String discussionId, String note, String author) {
    }

    public final List<Call> calls = new ArrayList<>();

    public FakeReplier() {
        super(null, null, RestClient.builder(), TestStores.properties());
    }

    @Override
    public void submit(String projectId, long mrIid, String discussionId, String note, String author) {
        calls.add(new Call(projectId, mrIid, discussionId, note, author));
    }
}
