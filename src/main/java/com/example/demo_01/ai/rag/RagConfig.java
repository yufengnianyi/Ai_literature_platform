package com.example.demo_01.ai.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

//@Configuration
public class RagConfig {

    @Resource
    private EmbeddingModel quwenEmbeddingModel;


    @Resource
    private EmbeddingStore embeddingStore;

    @Bean
    public ContentRetriever contentRetriever(){
        // 加载文档
        Document document = FileSystemDocumentLoader.loadDocument("src/main/resources/docs");
        // 文档切割 Document Splitter
        DocumentByParagraphSplitter documentByParagraphSplitter =
                new DocumentByParagraphSplitter(1000,200);
        // 自定义文件加载器 实现Embedding转化向量的过程
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                // 加载文档分割规则
                .documentSplitter(documentByParagraphSplitter)
                // 为每个文档chunk添加额外的元信息
                .textSegmentTransformer(textSegment -> TextSegment.from(textSegment.metadata().getString("file_name")
                        + '\n' + textSegment.text(), textSegment.metadata())
                )
                // 指定embedding模型
                .embeddingModel(quwenEmbeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        // 加载
        ingestor.ingest(document);
        // 自定义内容加载器
        EmbeddingStoreContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(quwenEmbeddingModel)
                // 最多检索出5条
                .maxResults(3)
                // 过滤分数的>0.75
                .minScore(0.75)
                .build();
        return contentRetriever;
    }

}
