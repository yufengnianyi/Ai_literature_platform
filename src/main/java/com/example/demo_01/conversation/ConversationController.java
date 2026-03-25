package com.example.demo_01.conversation;

import com.example.demo_01.user.UserService;
import com.example.demo_01.user.model.entity.User;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/conversations")
public class ConversationController {

    @Resource
    private ConversationService conversationService;

    @Resource
    private UserService userService;

    @PostMapping
    public ConversationService.ConversationResponse create(
            HttpServletRequest httpServletRequest,
            @RequestBody(required = false) ConversationService.CreateConversationRequest request) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        return conversationService.createConversation(loginUser.getUserId(), request);
    }

    @GetMapping
    public List<ConversationService.ConversationResponse> list(HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        return conversationService.listConversations(loginUser.getUserId());
    }

    @GetMapping("/{conversationId}/messages")
    public List<ConversationService.ConversationMessageResponse> listMessages(
            HttpServletRequest httpServletRequest,
            @PathVariable String conversationId) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        return conversationService.listConversationMessages(loginUser.getUserId(), conversationId);
    }

    @PatchMapping("/{conversationId}")
    public ConversationService.ConversationResponse rename(
            HttpServletRequest httpServletRequest,
            @PathVariable String conversationId,
            @RequestBody ConversationService.RenameConversationRequest request) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        return conversationService.renameConversation(loginUser.getUserId(), conversationId, request);
    }

    @PatchMapping("/{conversationId}/pin")
    public ConversationService.ConversationResponse pin(
            HttpServletRequest httpServletRequest,
            @PathVariable String conversationId,
            @RequestBody ConversationService.PinConversationRequest request) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        return conversationService.pinConversation(loginUser.getUserId(), conversationId, request);
    }

    @DeleteMapping("/{conversationId}")
    public void delete(
            HttpServletRequest httpServletRequest,
            @PathVariable String conversationId) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        conversationService.deleteConversation(loginUser.getUserId(), conversationId);
    }
}
