package com.example.demo_01.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 专门用于加载 GROBID 处理后的 JSONL 格式文献数据的加载器。
 * <p>
 * 在 RAG (Retrieval-Augmented Generation) 流程中，数据加载（Ingestion）是第一步。
 * 此加载器将 JSONL 文件中的每一行（代表一个段落/Chunk）解析为一个独立的 {@link Document} 对象。
 * 这样做的优势是保留了原始数据的颗粒度，使得 Metadata（如章节、页码、Paper ID）能够精确对应到具体的文本片段。
 */
@Component
public class JsonlLoader {

    private static final Logger log = LoggerFactory.getLogger(JsonlLoader.class);
    // Jackson 的 ObjectMapper，用于解析 JSON 字符串
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 加载指定目录下的所有 .jsonl 文件
     *
     * @param docsDir 文档目录路径
     * @return 解析后的文档列表，每个 JSON 对象对应一个 Document
     */
    public List<Document> loadDirectory(Path docsDir) {
        log.info("开始扫描目录加载 JSONL 文档: {}", docsDir);
        List<Document> allDocuments = new ArrayList<>();
        
        // 使用 try-with-resources 确保流被正确关闭
        try (Stream<Path> pathStream = Files.walk(docsDir)) {
            pathStream
                    // 过滤出普通文件（非目录）
                    .filter(Files::isRegularFile)
                    // 仅处理 .jsonl 后缀的文件（忽略大小写）
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jsonl"))
                    // 对每个找到的文件调用 loadFile，并将结果添加到总列表中
                    .forEach(path -> allDocuments.addAll(loadFile(path)));
        } catch (IOException e) {
            log.error("遍历目录失败: {}", docsDir, e);
            throw new UncheckedIOException(e);
        }
        
        log.info("目录扫描完成，共加载 {} 个文档片段", allDocuments.size());
        return allDocuments;
    }

    /**
     * 加载单个 JSONL 文件
     *
     * @param jsonlPath 文件路径
     * @return 文档列表
     */
    public List<Document> loadFile(Path jsonlPath) {
        List<Document> documents = new ArrayList<>();
        log.debug("正在解析文件: {}", jsonlPath);

        // 使用 BufferedReader 按行读取文件，适合处理大文件，避免一次性加载到内存
        try (BufferedReader br = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            
            // 逐行读取
            while ((line = br.readLine()) != null) {
                lineNum++;
                // 跳过空行
                if (line.isBlank()) {
                    continue;
                }

                try {
                    // 1. 解析 JSON 行
                    JsonNode node = MAPPER.readTree(line);

                    // 2. 提取正文内容 (Text)
                    // GROBID 输出的 JSONL 中，核心文本通常在 "text" 字段
                    if (!node.has("text") || node.get("text").isNull()) {
                        log.warn("文件 {} 第 {} 行缺少 'text' 字段，跳过", jsonlPath.getFileName(), lineNum);
                        continue;
                    }
                    String text = node.get("text").asText();
                    if (text.isBlank()) {
                        continue; // 文本为空则跳过
                    }

                    // 3. 提取元数据 (Metadata)
                    // Metadata 对于 RAG 的检索质量至关重要，它提供了上下文（如来源、章节、标题）
                    Metadata metadata = new Metadata();
                    
                    // 总是记录来源文件名
                    metadata.put("file_name", jsonlPath.getFileName().toString());
                    metadata.put("absolute_path", jsonlPath.toAbsolutePath().toString());

                    // 遍历 JSON 对象的所有字段，将非 "text" 的字段全部放入 Metadata
                    // 这样可以动态适应不同的 JSON 结构，保留尽可能多的信息
                    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        String key = field.getKey();
                        JsonNode value = field.getValue();

                        // 跳过 text 字段，因为已经作为 Document 的正文了
                        if ("text".equals(key)) {
                            continue;
                        }

                        // 将基本类型的值存入 Metadata
                        if (value.isTextual()) {
                            metadata.put(key, value.asText());
                        } else if (value.isInt()) {
                            metadata.put(key, value.asInt());
                        } else if (value.isLong()) {
                            metadata.put(key, value.asLong());
                        } else if (value.isDouble() || value.isFloat()) {
                            metadata.put(key, value.asDouble());
                        } else if (value.isBoolean()) {
                            metadata.put(key, String.valueOf(value.asBoolean()));
                        }
                        // 复杂对象（Object/Array）暂时忽略或转为 String 存储，视需求而定
                        // 这里简单处理，如果需要可以扩展
                    }

                    // 4. 构建 Document 对象
                    // 在 LangChain4j 中，Document = Text + Metadata
                    Document document = Document.from(text, metadata);
                    documents.add(document);

                } catch (Exception e) {
                    log.error("解析文件 {} 第 {} 行 JSON 失败", jsonlPath, lineNum, e);
                    // 单行解析失败不应该中断整个文件的加载，记录错误后继续
                }
            }
        } catch (IOException e) {
            log.error("读取文件失败: {}", jsonlPath, e);
            throw new UncheckedIOException(e);
        }

        return documents;
    }
}
