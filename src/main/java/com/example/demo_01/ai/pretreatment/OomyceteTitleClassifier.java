package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.TitleDecision;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class OomyceteTitleClassifier {

    private static final List<String> OOMYCETE_TERMS = List.of(
            "oomycete",
            "oomycetes",
            "phytophthora",
            "pythium",
            "saprolegnia",
            "plasmopara",
            "peronospora",
            "pseudoperonospora",
            "aphanomyces",
            "achlya",
            "bremia",
            "hyaloperonospora",
            "albugo",
            "peronosclerospora",
            "downy mildew",
            "late blight",
            "pythiosis",
            "water mold",
            "water mould"
    );

    public TitleDecision classify(RagDocumentMetadata metadata, List<RagChunk> chunks) {
        String title = metadata == null ? null : metadata.title();
        if (containsOomyceteSignal(title)) {
            return TitleDecision.TITLE_MATCH;
        }
        String metadataText = join(metadata == null ? null : metadata.abstractText(),
                metadata == null ? null : metadata.journal());
        if (containsOomyceteSignal(metadataText) || containsOomyceteSignal(firstChunkText(chunks, 6))) {
            return TitleDecision.TITLE_UNCERTAIN;
        }
        return TitleDecision.REJECT_NO_OOMYCETE_SIGNAL;
    }

    boolean containsOomyceteSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return OOMYCETE_TERMS.stream().anyMatch(lower::contains);
    }

    private String firstChunkText(List<RagChunk> chunks, int maxChunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(chunks.size(), maxChunks); i++) {
            builder.append(' ').append(chunks.get(i).text());
        }
        return builder.toString();
    }

    private String join(String first, String second) {
        return (first == null ? "" : first) + " " + (second == null ? "" : second);
    }
}
