package com.example.demo_01.ai.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class MarkdownChunkBuffer {

    static final int DEFAULT_CHAR_THRESHOLD = 240;
    static final long DEFAULT_EMIT_INTERVAL_MS = 100L;

    private final int charThreshold;
    private final long emitIntervalMs;
    private final LongSupplier nowSupplier;
    private final StringBuilder buffer = new StringBuilder();
    private long lastEmitAtMs;

    public MarkdownChunkBuffer() {
        this(DEFAULT_CHAR_THRESHOLD, DEFAULT_EMIT_INTERVAL_MS, System::currentTimeMillis);
    }

    MarkdownChunkBuffer(int charThreshold, long emitIntervalMs, LongSupplier nowSupplier) {
        if (charThreshold <= 0) {
            throw new IllegalArgumentException("charThreshold must be positive");
        }
        if (emitIntervalMs < 0) {
            throw new IllegalArgumentException("emitIntervalMs must be >= 0");
        }
        this.charThreshold = charThreshold;
        this.emitIntervalMs = emitIntervalMs;
        this.nowSupplier = Objects.requireNonNull(nowSupplier, "nowSupplier");
        this.lastEmitAtMs = nowSupplier.getAsLong();
    }

    public List<String> append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }

        buffer.append(chunk);
        return drain(false);
    }

    public List<String> flushRemaining() {
        return drain(true);
    }

    private List<String> drain(boolean flushAll) {
        List<String> emittedChunks = new ArrayList<>();

        while (buffer.length() > 0) {
            BoundaryScanResult boundaries = scanBoundaries(buffer);
            int boundary = boundaries.safeBoundary();

            if (boundary <= 0 && !flushAll && shouldFallbackEmit(boundaries)) {
                boundary = boundaries.newlineBoundary();
            }

            if (boundary <= 0) {
                break;
            }

            emittedChunks.add(take(boundary));
        }

        if (flushAll && buffer.length() > 0) {
            emittedChunks.add(take(buffer.length()));
        }

        return emittedChunks;
    }

    private boolean shouldFallbackEmit(BoundaryScanResult boundaries) {
        if (boundaries.newlineBoundary() <= 0) {
            return false;
        }

        long ageMs = nowSupplier.getAsLong() - lastEmitAtMs;
        return buffer.length() >= charThreshold || ageMs >= emitIntervalMs;
    }

    private String take(int boundary) {
        String chunk = buffer.substring(0, boundary);
        buffer.delete(0, boundary);
        lastEmitAtMs = nowSupplier.getAsLong();
        return chunk;
    }

    private static BoundaryScanResult scanBoundaries(CharSequence text) {
        int safeBoundary = 0;
        int newlineBoundary = 0;
        FenceState activeFence = null;
        int offset = 0;

        while (offset < text.length()) {
            int newlineIndex = indexOf(text, '\n', offset);
            int lineEnd = newlineIndex >= 0 ? newlineIndex : text.length();
            int segmentEnd = newlineIndex >= 0 ? newlineIndex + 1 : text.length();
            String line = text.subSequence(offset, lineEnd).toString();

            if (activeFence != null) {
                if (isFenceClose(line, activeFence)) {
                    activeFence = null;
                    safeBoundary = segmentEnd;
                    newlineBoundary = segmentEnd;
                }
            } else {
                FenceState fence = parseFence(line);
                if (fence != null) {
                    activeFence = fence;
                } else if (line.trim().isEmpty()) {
                    safeBoundary = segmentEnd;
                    newlineBoundary = segmentEnd;
                } else if (newlineIndex >= 0) {
                    newlineBoundary = segmentEnd;
                }
            }

            offset = segmentEnd;
        }

        return new BoundaryScanResult(safeBoundary, newlineBoundary);
    }

    private static int indexOf(CharSequence text, char target, int start) {
        for (int index = start; index < text.length(); index += 1) {
            if (text.charAt(index) == target) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isFenceClose(String line, FenceState activeFence) {
        FenceState currentFence = parseFence(line);
        return currentFence != null
                && currentFence.character() == activeFence.character()
                && currentFence.length() >= activeFence.length();
    }

    private static FenceState parseFence(String line) {
        int index = 0;
        while (index < line.length() && index < 3 && line.charAt(index) == ' ') {
            index += 1;
        }

        if (index >= line.length()) {
            return null;
        }

        char marker = line.charAt(index);
        if (marker != '`' && marker != '~') {
            return null;
        }

        int markerEnd = index;
        while (markerEnd < line.length() && line.charAt(markerEnd) == marker) {
            markerEnd += 1;
        }

        int length = markerEnd - index;
        if (length < 3) {
            return null;
        }

        return new FenceState(marker, length);
    }

    private record BoundaryScanResult(int safeBoundary, int newlineBoundary) {
    }

    private record FenceState(char character, int length) {
    }
}
