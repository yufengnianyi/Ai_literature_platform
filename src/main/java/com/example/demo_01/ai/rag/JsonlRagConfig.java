package com.example.demo_01.ai.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.IngestionResult;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 专门针对 GROBID JSONL 格式数据的 RAG 配置类。
 * 相比于通用的 RagConfig，这里针对已经结构化的 JSONL 数据进行了优化：
 * 1. 使用 JsonlLoader 按行加载，保留原始 Chunk 结构
 * 2. 优化 Metadata 注入逻辑，增强检索上下文
 */
@Configuration
// 可以通过配置文件控制是否启用这个特定的配置，避免与默认的 RagConfig 冲突
// 这里默认开启，但实际生产中建议用 @Profile 或 @ConditionalOnProperty 控制
public class JsonlRagConfig {

    private static final Logger log = LoggerFactory.getLogger(JsonlRagConfig.class);

    @Resource
    private EmbeddingModel quwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private JsonlLoader jsonlLoader;

    /**
     * 定义一个名为 jsonlContentRetriever 的 Bean，专门用于检索 JSONL 数据。
     * 这样在业务代码中可以通过 @Qualifier("jsonlContentRetriever") 注入使用。
     */
    @Bean("jsonlContentRetriever")
    public ContentRetriever jsonlContentRetriever() {
        log.info("初始化 JSONL RAG 检索器...");

        // 1. 加载数据
        // 使用自定义的 JsonlLoader，它会把每一行解析为一个独立的 Document
        // 且 Metadata 中已经包含了 paper_id, section, title 等信息
        Path docsPath = Paths.get("src/main/resources/docs");
        List<Document> documents = jsonlLoader.loadDirectory(docsPath);
        
        if (documents.isEmpty()) {
            log.warn("目录下没有找到 JSONL 文件: {}", docsPath);
        }

        // 2. 文档切分策略 (Document Splitter)
        // 更好的写法解释：
        // 虽然 JSONL 已经是 Chunk 了，但我们仍然需要一个 Splitter。
        // 原因：
        // a) Embedding 模型有最大 Token 限制（例如 8k 或 512），如果某一行 JSON 特别长，直接 Embed 会报错。
        // b) Ingestor 流程通常需要 Splitter 将 Document 转为 TextSegment。
        // 
        // 优化点：使用 DocumentByParagraphSplitter 并设置一个较大的 maxSegmentSize (1000字符)。
        // 这样对于大多数正常的段落，它不会进行二次切分，保持了 GROBID 的原始结构；
        // 仅当段落极长时才会切分，起到保护作用。
        DocumentByParagraphSplitter safeSplitter = new DocumentByParagraphSplitter(1200, 200);

        // 3. 构建 Ingestor
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(safeSplitter)
                // 更好的写法解释：Metadata 增强 (Context Injection)
                // 原始的 JSONL 包含 title 和 section 信息。如果不利用起来，检索时模型只能看到孤立的段落。
                // 这里我们将 title 和 section 拼接到 Text 前面。
                // 效果：当用户问 "关于 Diatom 的 Methods"，带有 "Section: Methods" 前缀的片段更容易被检索到。
                .textSegmentTransformer(segment -> {
                    String title = segment.metadata().getString("title");
                    String section = segment.metadata().getString("section");
                    String originalText = segment.text();

                    StringBuilder contextBuilder = new StringBuilder();
                    if (title != null && !title.isBlank()) {
                        contextBuilder.append("Paper: ").append(title).append("\n");
                    }
                    if (section != null && !section.isBlank()) {
                        contextBuilder.append("Section: ").append(section).append("\n");
                    }
                    contextBuilder.append(originalText);

                    return TextSegment.from(contextBuilder.toString(), segment.metadata());
                })
                .embeddingModel(quwenEmbeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        // 4. 执行 Ingestion (向量化并存储)
        // 注意：这是全量加载。生产环境通常会检查 Store 是否为空，或者使用增量加载。
        // 由于这里用的是 InMemoryEmbeddingStore，每次启动必须重新加载。
        IngestionResult result = ingestor.ingest(documents);
        log.info("JSONL 文档加载完成。Token使用统计: {}", result.tokenUsage());

        // 5. 构建 Retriever
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(quwenEmbeddingModel)
                .maxResults(5)
                // 提高一点门槛，过滤掉相关性低的片段
                .minScore(0.6) 
                .build();
    }
}
