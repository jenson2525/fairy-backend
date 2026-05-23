package cn.nuaa.jensonxu.fairy.web.controller;

import cn.nuaa.jensonxu.fairy.common.data.file.response.CustomResponse;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.request.AgentChatDTO;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.response.AgentSessionMessageVO;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.response.AgentSessionVO;
import cn.nuaa.jensonxu.fairy.service.agent.AgentService;
import cn.nuaa.jensonxu.fairy.service.agent.AgentSessionQueryService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/agent")
public class AgentController {

    private final AgentService agentService;
    private final AgentSessionQueryService sessionQueryService;

    /**
     * Agent SSE 流式对话接口
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentChat(@Valid @RequestBody AgentChatDTO agentChatDTO) {
        return agentService.chat(agentChatDTO);
    }

    /**
     * 查询用户会话列表
     * GET /agent/sessions?userId=xxx
     */
    @GetMapping("/sessions")
    public CustomResponse<List<AgentSessionVO>> listSessions(@RequestParam @NotBlank(message = "userId 不能为空") String userId) {
        return CustomResponse.success(sessionQueryService.listSessions(userId));
    }

    /**
     * 查询某会话的完整消息记录
     * GET /agent/sessions/{sessionId}/messages
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public CustomResponse<List<AgentSessionMessageVO>> listMessages(@PathVariable @NotBlank(message = "sessionId 不能为空") String sessionId) {
        return CustomResponse.success(sessionQueryService.listMessages(sessionId));
    }
}