package com.example.demo_01.ai.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkBufferTest {

    @Test
    void shouldEmitAtBlankLineBoundary() {
        MarkdownChunkBuffer buffer = new MarkdownChunkBuffer();

        List<String> emitted = buffer.append("Intro paragraph\n\n# Heading");

        assertThat(emitted).containsExactly("Intro paragraph\n\n");
        assertThat(buffer.flushRemaining()).containsExactly("# Heading");
    }

    @Test
    void shouldHoldOpenFencedCodeBlockUntilClosed() {
        MarkdownChunkBuffer buffer = new MarkdownChunkBuffer();

        assertThat(buffer.append("```ts\nconst answer = 42\n")).isEmpty();

        List<String> emitted = buffer.append("```\nNext line");

        assertThat(emitted).containsExactly("```ts\nconst answer = 42\n```\n");
        assertThat(buffer.flushRemaining()).containsExactly("Next line");
    }

    @Test
    void shouldFallbackToLatestNewlineWhenThresholdExceeded() {
        AtomicLong now = new AtomicLong(0L);
        MarkdownChunkBuffer buffer = new MarkdownChunkBuffer(240, 100L, now::get);

        assertThat(buffer.append("Line 1\n")).isEmpty();

        now.set(150L);
        List<String> emitted = buffer.append("Line 2");

        assertThat(emitted).containsExactly("Line 1\n");
        assertThat(buffer.flushRemaining()).containsExactly("Line 2");
    }

    @Test
    void shouldFlushRemainingWithoutChangingRawContent() {
        MarkdownChunkBuffer buffer = new MarkdownChunkBuffer();

        String firstChunk = "Summary line\n";
        String secondChunk = "Trailing text";

        List<String> emitted = buffer.append(firstChunk);
        emitted.addAll(buffer.append(secondChunk));
        emitted.addAll(buffer.flushRemaining());

        assertThat(String.join("", emitted)).isEqualTo(firstChunk + secondChunk);
    }
}
