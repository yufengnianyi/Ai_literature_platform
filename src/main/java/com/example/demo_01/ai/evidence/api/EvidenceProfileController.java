package com.example.demo_01.ai.evidence.api;

import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels;
import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.user.constant.UserConstant;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/stages/evidence-profiles")
public class EvidenceProfileController {
    @Resource
    private EvidenceProfileRegistry registry;

    @GetMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ProfileSet> get(@RequestParam(required = false) String profileVersion) {
        String version = registry.resolveVersion(profileVersion);
        return ResultUtils.success(new ProfileSet(version, MultiProfileEvidenceModels.DEFAULT_PROFILE_VERSION,
                List.of(MultiProfileEvidenceModels.PROFILE_VERSION,
                        MultiProfileEvidenceModels.EXPERT_PROFILE_VERSION), registry.all(version)));
    }

    public record ProfileSet(String profileVersion, String defaultProfileVersion,
                             List<String> availableVersions,
                             List<EvidenceProfileRegistry.EvidenceProfile> questions) { }
}
