package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.CrossrefClient.CrossrefWork;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.parser.DocumentTitleHeuristics;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PretreatmentTitleMetadataResolver {

    @Resource
    private CrossrefClient crossrefClient;

    public RagDocumentMetadata resolve(RagDocumentMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        boolean invalidTitle = DocumentTitleHeuristics.isInvalidExtractedTitle(metadata.title(), metadata.journal());
        boolean invalidJournal = DocumentTitleHeuristics.isInvalidExtractedTitle(metadata.journal(), metadata.title());
        if ((!invalidTitle && !invalidJournal) || metadata.doiNormalized() == null || metadata.doiNormalized().isBlank()) {
            return metadata;
        }
        try {
            CrossrefWork work = crossrefClient.findByDoi(metadata.doiNormalized());
            if (work == null) {
                return metadata;
            }
            String title = metadata.title();
            String crossrefTitle = DocumentTitleHeuristics.stripMarkup(work.title());
            if (invalidTitle && !DocumentTitleHeuristics.isInvalidExtractedTitle(crossrefTitle, work.journal())) {
                title = crossrefTitle;
            }
            String journal = metadata.journal();
            if (invalidJournal && work.journal() != null && !work.journal().isBlank()) {
                journal = work.journal();
            }
            if (same(title, metadata.title()) && same(journal, metadata.journal())) {
                return metadata;
            }
            return new RagDocumentMetadata(
                    metadata.doiRaw(),
                    metadata.doiNormalized(),
                    title,
                    metadata.authors(),
                    metadata.affiliations(),
                    metadata.abstractText(),
                    journal,
                    metadata.publicationDate(),
                    metadata.publicationYear()
            );
        } catch (Exception ex) {
            log.warn("Crossref title resolution failed for DOI {}: {}", metadata.doiNormalized(), rootMessage(ex));
            return metadata;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
