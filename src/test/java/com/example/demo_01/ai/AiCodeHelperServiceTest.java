package com.example.demo_01.ai;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AiCodeHelperServiceTest {


    @Resource
    private AiCodeHelperService aiCodeHelperService;

    @Test
    void chat() {
        String s  = aiCodeHelperService.chat("你好");
        System.out.println(s);
    }

    @Test
    void chatWithMemory() {
        String s  = aiCodeHelperService.chatWithMemory(11,"我是红薯");
        System.out.println(s);
        s = aiCodeHelperService.chatWithMemory(22,"我是谁？");
        System.out.println(s);
    }

    @Test
    void chatForReport() {
        String useMessage = "家族分类如果基于结构进行聚类有哪些好处？";
        // 将ai 返回的结果传回定义的report变量之中
        AiCodeHelperService.Report report = aiCodeHelperService.chatForReport(useMessage);
        System.out.println(report);
    }

    @Test
    void chatWithSources() {
        String userMessage = "球石藻是否真的完全不需要硅？";
        dev.langchain4j.service.Result<String> result = aiCodeHelperService.chatWithSources(userMessage);
        
        System.out.println("回答内容: " + result.content());
        System.out.println("\n--- 引用来源 ---");
        
        result.sources().forEach(content -> {
            System.out.println("来源文件: " + content.textSegment().metadata().getString("file_name"));
            System.out.println("论文标题: " + content.textSegment().metadata().getString("title"));
            System.out.println("章节: " + content.textSegment().metadata().getString("section"));
            System.out.println("----------------");
        });
    }

}