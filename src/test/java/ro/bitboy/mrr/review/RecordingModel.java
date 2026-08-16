package ro.bitboy.mrr.review;

import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.ReviewModel.Result;
import ro.bitboy.mrr.model.ReviewModel.ToolSpec;
import ro.bitboy.mrr.model.ReviewModel.Turn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Scripted {@code complete()} calls for loop tests. */
final class RecordingModel implements ReviewModel {

    private final List<Result> script;
    private final Supplier<Result> live;
    private int i;
    final List<List<ToolSpec>> toolLists = new ArrayList<>();
    final List<List<Turn>> turns = new ArrayList<>();

    RecordingModel(List<Result> script) {
        this.script = script;
        this.live = null;
    }

    RecordingModel(Supplier<Result> live) {
        this.script = List.of();
        this.live = live;
    }

    @Override
    public Result review(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, List.of(Turn.user(userPrompt)), List.of());
    }

    @Override
    public Result complete(String systemPrompt, List<Turn> turns, List<ToolSpec> tools) {
        toolLists.add(List.copyOf(tools));
        this.turns.add(List.copyOf(turns));
        if (live != null) {
            return live.get();
        }
        return script.get(Math.min(i++, script.size() - 1));
    }
}
