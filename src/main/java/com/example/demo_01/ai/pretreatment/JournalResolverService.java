package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.CrossrefClient.CrossrefWork;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.JournalQuality;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.ResolvedJournal;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class JournalResolverService {

    @Resource
    private PretreatmentProperties properties;

    @Resource
    private CrossrefClient crossrefClient;

    @Resource
    private JournalQualityService journalQualityService;

    public ResolvedJournal resolve(RagDocumentMetadata metadata, Map<String, JournalQuality> journalQualityMap) {
        String rawJournal = metadata == null ? null : metadata.journal();
        String doi = metadata == null ? null : metadata.doiNormalized();
        if (properties.getJournalResolution().isEnabled() && doi != null && !doi.isBlank()) {
            try {
                CrossrefWork work = crossrefClient.findByDoi(doi);
                if (work != null && work.journal() != null && !work.journal().isBlank()) {
                    JournalQuality quality = journalQualityService.matchByIssnOrName(work.issns(), work.journal(), journalQualityMap);
                    return new ResolvedJournal(rawJournal, work.journal(), work.issns(), work.publisher(),
                            "CROSSREF_DOI", 0.98, quality);
                }
            } catch (Exception ex) {
                log.warn("Crossref DOI journal resolution failed for DOI {}: {}", doi, ex.getMessage());
            }
        }
        JournalQuality quality = journalQualityService.match(rawJournal, journalQualityMap);
        return ResolvedJournal.raw(rawJournal, quality);
    }
}
