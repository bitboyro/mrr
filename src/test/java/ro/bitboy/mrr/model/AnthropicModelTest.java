package ro.bitboy.mrr.model;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.model.ReviewModel.ToolCall;
import ro.bitboy.mrr.model.ReviewModel.Turn;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicModelTest {

    @Test
    void assistantTurnPrefersEchoedThinkingBlocksOverARebuild() {
        ContentBlockParam thinking = ContentBlockParam.ofThinking(
                ThinkingBlockParam.builder()
                        .thinking("look at callers")
                        .signature("sig-abc")
                        .build());
        ContentBlockParam text = ContentBlockParam.ofText(
                TextBlockParam.builder().text("calling read_file").build());
        Turn turn = Turn.assistant("calling read_file",
                List.of(new ToolCall("t1", "read_file", "{\"path\":\"A.java\"}", null)),
                List.of(thinking, text));

        List<ContentBlockParam> blocks = AnthropicModel.assistantBlocks(turn);

        assertThat(blocks).containsExactly(thinking, text);
        assertThat(blocks.getFirst().isThinking()).isTrue();
    }

    @Test
    void withoutEchoTheAssistantTurnIsRebuiltFromTextAndToolCalls() {
        Turn turn = Turn.assistant("calling read_file",
                List.of(new ToolCall("t1", "read_file", "{\"path\":\"A.java\"}", null)));

        List<ContentBlockParam> blocks = AnthropicModel.assistantBlocks(turn);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.getFirst().isText()).isTrue();
        assertThat(blocks.get(1).isToolUse()).isTrue();
    }
}
