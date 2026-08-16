package ro.bitboy.mrr;

import ro.bitboy.mrr.memory.MemoryDocument;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.ReviewModel;

import java.util.ArrayList;
import java.util.List;

/** Memory double that does not touch the filesystem. */
public final class FakeMemory extends MemoryService {

    public int upserts;
    public final List<String> closed = new ArrayList<>();

    public FakeMemory() {
        super(null);
    }

    @Override
    public MemoryDocument upsertMrCard(String projectId, long mrIid, MergeRequestInfo mr,
                                       String headSha, ReviewModel.Verdict verdict,
                                       List<Finding> opened, String fallbackSummary) {
        upserts++;
        return null;
    }

    @Override
    public void markMrClosed(String projectId, long mrIid, String action) {
        closed.add(projectId + "!" + mrIid + ":" + action);
    }
}
