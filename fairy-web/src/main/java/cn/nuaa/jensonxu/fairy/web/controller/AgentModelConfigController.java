package cn.nuaa.jensonxu.fairy.web.controller;

import cn.nuaa.jensonxu.fairy.common.data.file.response.CustomResponse;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.request.AgentModelConfigFormDTO;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.response.AgentModelConfigVO;
import cn.nuaa.jensonxu.fairy.service.agent.AgentModelConfigService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/agent/model/config")
@RequiredArgsConstructor
public class AgentModelConfigController {

    private final AgentModelConfigService agentModelConfigService;

    @PostMapping
    public CustomResponse<String> addConfig(@Valid @RequestBody AgentModelConfigFormDTO agentModelConfigFormDTO) {
        agentModelConfigService.addConfig(agentModelConfigFormDTO);
        return CustomResponse.success("模型配置新增成功");
    }

    @PutMapping("/{id}")
    public CustomResponse<String> updateConfig(@PathVariable Long id, @Valid @RequestBody AgentModelConfigFormDTO agentModelConfigFormDTO) {
        agentModelConfigService.updateConfig(id, agentModelConfigFormDTO);
        return CustomResponse.success("模型配置更新成功");
    }

    @DeleteMapping("/{id}")
    public CustomResponse<String> deleteConfig(@PathVariable Long id) {
        agentModelConfigService.deleteConfig(id);
        return CustomResponse.success("模型配置已删除");
    }

    @GetMapping("/models")
    public CustomResponse<List<String>> listModels(@RequestParam("userId") String userId) {
        return CustomResponse.success(agentModelConfigService.listModelNames(userId));
    }

    @GetMapping("/config")
    public CustomResponse<List<AgentModelConfigVO>> listConfigs(@RequestParam("userId") String userId) {
        return CustomResponse.success(agentModelConfigService.listConfigs(userId));
    }
}