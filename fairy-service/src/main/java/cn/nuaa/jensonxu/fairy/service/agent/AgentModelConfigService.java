package cn.nuaa.jensonxu.fairy.service.agent;

import cn.nuaa.jensonxu.fairy.common.data.llm.agent.request.AgentModelConfigFormDTO;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.response.AgentModelConfigVO;
import cn.nuaa.jensonxu.fairy.common.exception.BusinessException;
import cn.nuaa.jensonxu.fairy.common.exception.ErrorCode;
import cn.nuaa.jensonxu.fairy.common.repository.mysql.data.AgentModelConfigDO;
import cn.nuaa.jensonxu.fairy.integration.agent.model.manager.CustomModelManager;

import com.alibaba.fastjson2.JSON;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentModelConfigService {

    private final CustomModelManager customModelManager;

    public void addConfig(AgentModelConfigFormDTO dto) {
        if (customModelManager.existsByUserIdAndModelName(dto.getUserId(), dto.getModelName())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "模型名称已存在: " + dto.getModelName());
        }

        AgentModelConfigDO record = AgentModelConfigDO.builder()
                .userId(dto.getUserId())
                .modelName(dto.getModelName())
                .provider(dto.getProvider())
                .apiKey(dto.getApiKey())
                .baseUrl(dto.getBaseUrl())
                .isEnabled(dto.getIsEnabled())
                .parameters(dto.getParameters() != null ? JSON.toJSONString(dto.getParameters()) : null)
                .isDeleted(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        customModelManager.insert(record);
        log.info("[agent] 新增模型配置, userId: {}, modelName: {}", dto.getUserId(), dto.getModelName());
    }

    public void updateConfig(Long id, AgentModelConfigFormDTO dto) {
        AgentModelConfigDO existing = customModelManager.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "配置不存在，id: " + id));
        if (!existing.getModelName().equals(dto.getModelName()) && customModelManager.existsByUserIdAndModelName(dto.getUserId(), dto.getModelName())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "模型名称已存在: " + dto.getModelName());
        }

        existing.setModelName(dto.getModelName());
        existing.setProvider(dto.getProvider());
        existing.setApiKey(dto.getApiKey());
        existing.setBaseUrl(dto.getBaseUrl());
        existing.setIsEnabled(dto.getIsEnabled());
        existing.setParameters(dto.getParameters() != null ? JSON.toJSONString(dto.getParameters()) : null);
        existing.setUpdatedAt(LocalDateTime.now());

        customModelManager.updateById(existing);
        log.info("[agent] 更新配置, userId: {}, id: {}", dto.getUserId(), id);
    }

    public void deleteConfig(Long id) {
        customModelManager.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "配置不存在，id: " + id));
        customModelManager.deleteById(id);
        log.info("[agent] 删除配置, id: {}", id);
    }

    public List<AgentModelConfigVO> listConfigs(String userId) {
        return customModelManager.findByUserId(userId);
    }

    public List<String> listModelNames(String userId) {
        return customModelManager.listModelNames(userId);
    }
}