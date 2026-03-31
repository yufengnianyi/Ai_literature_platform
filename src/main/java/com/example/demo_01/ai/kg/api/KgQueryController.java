package com.example.demo_01.ai.kg.api;

import com.example.demo_01.ai.kg.model.QuestionGraphModels.QuestionGraphView;
import com.example.demo_01.ai.kg.service.QuestionGraphQueryService;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/kg")
public class KgQueryController {

    @Resource
    private QuestionGraphQueryService questionGraphQueryService;

    @GetMapping("/query")
    public BaseResponse<QuestionGraphView> query(@RequestParam String prompt) {
        return ResultUtils.success(questionGraphQueryService.query(prompt));
    }
}
