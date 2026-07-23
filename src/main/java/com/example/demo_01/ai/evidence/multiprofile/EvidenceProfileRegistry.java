package com.example.demo_01.ai.evidence.multiprofile;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EvidenceProfileRegistry {

    private static final List<String> ANTIMICROBIAL_COMPOUND_HEADERS = List.of(
            "Compound original name", "Compound standard name", "Structural class", "Source category",
            "Specific source description", "Tested oomycete Latin name", "Assay method", "Activity data",
            "Positive control", "Target/mechanism of action", "Target validation method", "Cytotoxicity",
            "Resistance/cross-resistance", "Synergy", "Reference", "Patent information"
    );

    private final Map<String, EvidenceProfile> profiles;

    public EvidenceProfileRegistry() {
        Map<String, EvidenceProfile> values = new LinkedHashMap<>();
        add(values, profile("Q1", "Antimicrobial compound evidence",
                ANTIMICROBIAL_COMPOUND_HEADERS, List.of(0, 5, 6),
                "Evidence that compounds, extracts, mixtures, pesticides, or derivatives are active against oomycetes.",
                "One compound-tested oomycete-assay method combination.",
                "Create separate rows for multiple oomycete species or assay methods. Summarize multiple concentrations or time points for the same method in Activity data. For Markush series, extract the Markush core plus at most the three most active representative derivatives."));
        add(values, profile("Q2", "Effector pathogenicity mechanisms, secretion, and structure", List.of(
                        "Effector name", "Alias/homologous gene", "Effector family", "Source oomycete species", "Strain/isolate",
                        "Gene ID/accession", "Amino acid length", "Signal peptide presence", "Conserved domain", "Effector type",
                        "Host target protein", "Target function", "Mechanism of action", "Subcellular localization", "Induced expression condition",
                        "Secretion/transport mechanism", "Host-cell entry mechanism", "Functional validation phenotype",
                        "Plant recognition/AVR activity", "Structure determination method", "PDB ID", "Reference", "Patent information", "Notes"),
                List.of(0),
                "Evidence on oomycete effectors, host targets, pathogenicity mechanisms, secretion or transport, host-cell entry, localization, expression, structural characterization, and functional validation.",
                "One effector-oomycete species-host target combination.",
                "Create separate rows for multiple host targets or oomycete species. For reviews, extract only concrete effector information that explicitly cites original studies and mark it as review-cited in Notes."));
        add(values, profile("Q3", "Resistance genes: host and non-host resistance", List.of(
                        "Resistance type", "Gene/locus name", "Alias/homologous gene", "Plant species (Latin name)", "Plant variety/line",
                        "Resistance target (oomycete species/strain)", "Recognized avirulence gene (AVR)", "Resistance spectrum (race range)",
                        "Gene type", "Nucleotide-binding domain", "Chromosomal position", "Cloning status", "Resistance mechanism (molecular level)",
                        "Resistance level", "Allelic variation", "Resistance breakdown", "PTI/ETI involvement", "Signaling pathway (SA/JA/ET)",
                        "Transgenic/gene-editing application", "Molecular marker", "Breeding application", "Functional validation method",
                        "Expression pattern", "Reference", "Patent information", "Notes"), List.of(0, 1, 3),
                "Evidence on host resistance, non-host resistance, and partial or quantitative resistance genes, loci, QTLs, mechanisms, and validation against oomycetes.",
                "One gene or mechanism-plant species-resistance type combination.",
                "Create separate rows for different pathogen targets, races, alleles, or resistance types. Clearly label host resistance and non-host resistance in Resistance type. For uncloned QTLs or GWAS candidates, record the cloning status explicitly."));
        add(values, profile("Q4", "Fungicide/oomyceticide resistance and targets", List.of(
                        "Fungicide common name", "Fungicide class (FRAC code)", "Mode-of-action target (protein/pathway)", "Target function",
                        "Target validation method", "Target oomycete species", "Strain/isolate", "Resistance mutation gene",
                        "Mutation site (amino acid change)", "Mutation type", "Resistance level (EC50 ratio)", "Field occurrence",
                        "Molecular detection method", "Cross-resistance", "Resistance management recommendation", "Reference", "Patent information", "Notes"),
                List.of(0, 5),
                "Evidence on oomycete resistance to fungicides or oomyceticides, molecular targets, target validation, target mutations, resistance levels, field occurrence, and detection methods.",
                "One agent-oomycete species-mutation site or target combination.",
                "Create separate rows for different mutation sites or oomycete species. If a paper reports target identification rather than resistance, leave resistance-related fields blank and populate target-related fields."));
        add(values, profile("Q5", "Genome, pan-genome, and effector repertoire", List.of(
                        "Oomycete species (Latin name)", "Strain/isolate", "Study type (genome/pan-genome)", "Sample count (pan-genome)",
                        "Genome size (Mb)", "Contig N50 (kb)", "Total genes", "Core gene count", "Pan-gene count", "Total effectors",
                        "RXLR count", "CRN count", "Other effector families", "Repeat sequence proportion (%)", "Reference genome version",
                        "Reference", "Notes"), List.of(0),
                "Evidence on oomycete genome assemblies, pan-genomes, population genomes, gene counts, core and pan-gene counts, effector repertoires, repeats, and reference genome versions.",
                "One oomycete species-strain or isolate genome record.",
                "Create separate rows for different strains or isolates of the same species. If only a preliminary assembly is reported and effectors were not analyzed, leave effector statistics blank."));
        add(values, profile("Q6", "Oomycete functional genes, including metabolism and nutrition", List.of(
                        "Gene name", "Alias/homologous gene", "Gene ID/accession", "Oomycete species (Latin name)", "Strain/isolate",
                        "Gene functional category", "Encoded protein/product", "Functional validation method", "Mutation/silencing phenotype",
                        "Overexpression phenotype", "Expression pattern", "Biological process involved", "Reference", "Notes"),
                List.of(0, 3),
                "Evidence on oomycete functional genes related to growth and development, pathogenicity or virulence, metabolism and nutrition, stress responses, cell wall or membrane biosynthesis, signaling, reproduction, and other non-classical-effector functions.",
                "One oomycete species-gene combination.",
                "Create separate rows for different genes or strains. Combine multiple validation experiments for the same gene in the relevant fields using semicolons. If the gene is a confirmed classical RXLR or CRN effector, prefer Q2."));
        add(values, profile("Q7", "Biological control and green disease management", List.of(
                        "Biocontrol/control type", "Name", "Source (strain/plant species/material)", "Target oomycete species", "Mode of action",
                        "Antimicrobial/control mechanism", "In vitro activity data", "In vivo/field control efficacy (%)", "Application method",
                        "Synergy with chemical agents", "Viral genome type (oomycete viruses only)", "Effect on host (oomycete viruses only)",
                        "Reference", "Patent information", "Notes"), List.of(0, 1, 3),
                "Evidence on biological control agents, plant-derived inducers, nanomaterials, novel compounds, oomycete viruses, and other green-control approaches against oomycete diseases.",
                "One biocontrol agent, material, compound, or virus-target oomycete combination.",
                "Create separate rows for different target oomycetes or application methods. Distinguish in vitro activity from greenhouse or field efficacy and preserve application method, dose, synergistic agent, and experimental conditions."));
        add(values, profile("Q8", "Disease diagnosis and molecular detection", List.of(
                        "Target oomycete species", "Detection target gene", "Detection technology", "Primer/probe name", "Primer/probe sequence (5' to 3')",
                        "Amplicon size (bp)", "Sensitivity (limit of detection)", "Specificity (cross-reaction with other species)", "Applicable sample type",
                        "Reference", "Patent information", "Notes"), List.of(0, 2),
                "Evidence on PCR, qPCR, LAMP, RPA, and other diagnostic or molecular detection methods for oomycete pathogens.",
                "One detection target-technology combination.",
                "Create separate rows for different detection technologies targeting the same gene or species. Copy primer and probe sequences exactly when provided."));
        add(values, profile("Q9", "Disease epidemiology and prediction models", List.of(
                        "Disease name", "Pathogenic oomycete species", "Main host", "Favorable environmental factors", "Prediction model name",
                        "Model input variables", "Prediction output", "Model accuracy/validation", "Reference", "Notes"), List.of(0, 1, 4),
                "Evidence on environmental drivers of oomycete disease epidemics, risk warnings, and construction or validation of prediction models.",
                "One disease-model combination.",
                "Create separate rows for different models for the same disease. Distinguish general environmental correlations from true prediction models, and record input variables, prediction output, and validation metrics."));
        add(values, profile("Q10", "Physiological races, population diversity, and evolution", List.of(
                        "Oomycete species", "Host crop", "Study type (race identification/population genetics/evolution)", "Physiological race/pathotype name",
                        "Differential host variety", "Avirulence genotype (Avr)", "Corresponding resistance gene (R)",
                        "Population genetic structure/evolutionary relationship", "Geographic distribution", "Source sample", "Reference", "Notes"),
                List.of(0, 1, 2),
                "Evidence on physiological races, pathotypes, population genetic diversity, Avr-R relationships, geographic distributions, and evolutionary relationships in oomycete pathogens.",
                "One oomycete species-host-race or population combination.",
                "Create separate rows for different races, populations, or regions. Do not force general population diversity into a race entry unless differential hosts, Avr/R relationships, geography, or sample evidence support it."));
        profiles = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public List<EvidenceProfile> all() {
        return profiles.values().stream().toList();
    }

    public EvidenceProfile require(String questionId) {
        EvidenceProfile profile = profiles.get(questionId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown evidence question: " + questionId);
        }
        return profile;
    }

    private void add(Map<String, EvidenceProfile> values, EvidenceProfile profile) {
        values.put(profile.questionId(), profile);
    }

    private EvidenceProfile profile(String id, String title, List<String> headers,
                                    List<Integer> primaryFieldIndexes, String scope,
                                    String rowUnit, String splitRules) {
        return new EvidenceProfile(id, title, List.copyOf(headers), List.copyOf(primaryFieldIndexes),
                scope, rowUnit, splitRules, guidance(id));
    }

    private String guidance(String questionId) {
        return switch (questionId) {
            case "Q1" -> """
                    Source category must use only plant natural product, microbial, chemical synthesis, commercialized, or semi-synthetic.
                    Preserve activity values exactly, including EC50, IC50, MIC, concentration, time, inhibition rate, field efficacy, and units.
                    Leave standard names blank when the source text or accepted naming evidence is absent. For positive controls, record both the control name and the corresponding data.
                    """;
            case "Q2" -> """
                    Effector families include RXLR, CRN, NLPP, GP15, elicitin, and similar families. Effector type must be cytoplasmic or apoplastic when stated.
                    Functional phenotypes must describe how overexpression, silencing, knockout, or mutation affects pathogenicity. AVR activity records recognition by the corresponding R gene.
                    Record secretion or transport mechanism, host-cell entry mechanism, structure determination method, and PDB ID only when the paper provides evidence.
                    """;
            case "Q3" -> """
                    Resistance type must be host resistance, non-host resistance, or partial/quantitative resistance.
                    Fill PTI/ETI and SA/JA/ET only when the source provides evidence. Distinguish cloned genes from mapped QTLs and GWAS candidates.
                    """;
            case "Q4" -> """
                    Preserve FRAC code, target gene or pathway, target function, target validation method, amino acid change, EC50 resistance ratio, field region, and molecular detection method.
                    Leave mutation fields blank when no explicit mutation site is reported. Do not infer mutations from fungicide class.
                    """;
            case "Q5" -> """
                    Preserve original units and assembly or reference genome versions for genome size, N50, gene counts, core genes, pan-genes, effector counts, and repeat proportions.
                    Leave pan-genome fields blank for single-genome studies, and leave effector statistics blank when they were not analyzed.
                    """;
            case "Q6" -> """
                    Functional categories must use growth/development, pathogenicity/virulence, metabolism/nutrition, stress response, cell wall/membrane biosynthesis, signaling, reproduction/sexual reproduction, or other.
                    Record validation methods such as knockout, RNAi, overexpression, mutant phenotype analysis, chemical inhibition, heterologous expression, in vitro biochemical assay, transcriptomics, or complementation.
                    Mark review-derived entries as review-cited in Notes.
                    """;
            case "Q7" -> """
                    Biocontrol/control type must use microbial biocontrol agent, plant-derived inducer, nanomaterial, novel compound, oomycete virus, or other.
                    Distinguish in vitro activity from greenhouse or field efficacy, and preserve application method, dose, synergistic chemical agents, viral genome type, host effect, and experimental conditions.
                    """;
            case "Q8" -> """
                    Copy primer and probe sequences in the 5' to 3' direction exactly as provided.
                    Preserve limit of detection, amplicon size, specificity, cross-reactions with related species, and applicable sample type.
                    """;
            case "Q9" -> """
                    Distinguish environmental correlation descriptions from true prediction models.
                    Record model input variables, prediction output, independent validation metrics, and field validation context when available.
                    """;
            case "Q10" -> """
                    Physiological races or pathotypes require evidence such as differential hosts, Avr/R relationships, geography, or source samples.
                    Population genetics and evolution studies should capture population structure, diversity, clades, migration, host association, and geographic distribution.
                    """;
            default -> "";
        };
    }

    public record EvidenceProfile(
            String questionId,
            String title,
            List<String> headers,
            List<Integer> primaryFieldIndexes,
            String scope,
            String rowUnit,
            String splitRules,
            String guidance
    ) {
    }
}
