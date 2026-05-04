package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CompoundIdentityResolver {

    public CompoundIdentity resolve(DocumentKnowledgeCompound compound) {
        if (compound == null || compound.resolutionStatus() == CompoundResolutionStatus.UNRESOLVED) {
            return null;
        }
        String key = identityKey(compound);
        if (key == null) {
            return null;
        }
        String compoundId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        String canonicalName = firstNonBlank(compound.canonicalName(), compound.resolvedName(), compound.iupacName());
        LinkedHashSet<String> synonyms = new LinkedHashSet<>();
        add(synonyms, compound.localAlias());
        add(synonyms, compound.resolvedName());
        add(synonyms, compound.canonicalName());
        add(synonyms, compound.iupacName());
        return new CompoundIdentity(
                compoundId,
                canonicalName,
                compound.iupacName(),
                compound.casNumber(),
                compound.smiles(),
                compound.inchiKey(),
                compound.molecularFormula(),
                compound.structureType(),
                List.copyOf(synonyms),
                compound.confidence()
        );
    }

    public DocumentKnowledgeCompound attachIdentity(DocumentKnowledgeCompound compound, CompoundIdentity identity) {
        if (compound == null || identity == null) {
            return compound;
        }
        return new DocumentKnowledgeCompound(
                compound.localAlias(),
                compound.resolvedName(),
                firstNonBlank(compound.canonicalName(), identity.canonicalName()),
                compound.iupacName(),
                compound.casNumber(),
                compound.smiles(),
                compound.inchiKey(),
                compound.molecularFormula(),
                compound.structureType(),
                compound.source(),
                compound.bioactivity(),
                compound.targetOrganism(),
                compound.mechanism(),
                compound.resolutionStatus(),
                compound.evidenceChunkId(),
                compound.evidenceText(),
                compound.confidence(),
                identity.compoundId()
        );
    }

    private String identityKey(DocumentKnowledgeCompound compound) {
        String inchiKey = clean(compound.inchiKey());
        if (inchiKey != null) {
            return "inchi:" + inchiKey;
        }
        String smiles = clean(compound.smiles());
        if (smiles != null) {
            return "smiles:" + smiles;
        }
        String cas = clean(compound.casNumber());
        if (cas != null) {
            return "cas:" + cas;
        }
        String name = clean(firstNonBlank(compound.canonicalName(), compound.resolvedName(), compound.iupacName()));
        String formula = clean(compound.molecularFormula());
        if (name != null && formula != null) {
            return "name-formula:" + name + ":" + formula;
        }
        return null;
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private void add(LinkedHashSet<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
