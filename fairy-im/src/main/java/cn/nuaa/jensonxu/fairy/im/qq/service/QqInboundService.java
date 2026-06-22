package cn.nuaa.jensonxu.fairy.im.qq.service;

import cn.nuaa.jensonxu.fairy.common.data.llm.agent.MessageSource;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.request.AgentChatDTO;
import cn.nuaa.jensonxu.fairy.common.repository.mysql.QqUserBindingRepository;
import cn.nuaa.jensonxu.fairy.common.repository.mysql.data.QqUserBindingDO;
import cn.nuaa.jensonxu.fairy.common.repository.redis.RedisUtil;
import cn.nuaa.jensonxu.fairy.im.qq.client.NapCatApiClient;
import cn.nuaa.jensonxu.fairy.im.qq.config.NapCatProperties;
import cn.nuaa.jensonxu.fairy.im.qq.dto.OneBotMessageEventDTO;
import cn.nuaa.jensonxu.fairy.im.qq.listener.QqSegmentListener;
import cn.nuaa.jensonxu.fairy.service.agent.AgentModelConfigService;
import cn.nuaa.jensonxu.fairy.service.agent.AgentService;

import com.alibaba.fastjson2.JSON;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * QQ 入站消息编排服务
 * 收敛 OneBot11 私聊消息处理全流程：过滤、防回环、幂等去重、纯文本判定、模型校验，
 * 通过后组装 AgentChatDTO 并异步驱动 Agent 分段回复
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QqInboundService {

    /** 上报类型：消息 */
    private static final String POST_TYPE_MESSAGE = "message";

    /** 消息类型：私聊 */
    private static final String MESSAGE_TYPE_PRIVATE = "private";

    /** CQ 码前缀，出现即非纯文本 */
    private static final String CQ_PREFIX = "[CQ:";

    /** 去重 key 模板与 TTL（秒） */
    private static final String DEDUP_KEY = "im:dedup:qq:%s";
    private static final long DEDUP_TTL_SECONDS = 300;

    /** 各类校验提示文案 */
    private static final String PROMPT_NON_TEXT = "⚠️ 首期仅支持纯文本消息，暂不支持图片/文件等内容。";
    private static final String PROMPT_NOT_BOUND = "⚠️ 你的 QQ 尚未绑定 fairy 账号，请先完成绑定。";
    private static final String PROMPT_NO_MODEL = "⚠️ 你还没有设置默认对话模型，请先绑定一个模型后再试。";
    private static final String PROMPT_MODEL_NOT_CONFIGURED = "⚠️ 你设置的模型「%s」尚未在系统中配置，请先完成模型配置后再试。";

    private final QqUserBindingRepository qqUserBindingRepository;
    private final AgentModelConfigService agentModelConfigService;
    private final AgentService agentService;
    private final NapCatApiClient napCatApiClient;
    private final NapCatProperties napCatProperties;
    private final RedisUtil redisUtil;

    /**
     * 处理 NapCat 推送的原始上报报文
     * @param rawBody 原始 JSON 报文
     */
    public void onReport(String rawBody) {
        try {
            OneBotMessageEventDTO event = JSON.parseObject(rawBody, OneBotMessageEventDTO.class);

            // 只处理私聊消息，其余（心跳、群聊、通知等）忽略
            if (event == null
                    || !POST_TYPE_MESSAGE.equals(event.getPostType())
                    || !MESSAGE_TYPE_PRIVATE.equals(event.getMessageType())) {
                return;
            }
            handlePrivateMessage(event);
        } catch (Exception e) {
            log.error("[im-qq] 处理上报报文失败, rawBody: {}", rawBody, e);  // 吞掉异常，保证 webhook 快速返回
        }
    }

    /**
     * 私聊消息处理主流程
     * @param event 私聊消息事件
     */
    private void handlePrivateMessage(OneBotMessageEventDTO event) {
        Long qqNumber = event.getUserId();
        Long botId = event.getSelfId();

        // ① 防回环：bot 自身消息跳过
        if (qqNumber == null || qqNumber.equals(botId)) {
            return;
        }

        // ② 幂等去重：同一 message_id 短时间内只处理一次
        String dedupKey = String.format(DEDUP_KEY, event.getMessageId());
        if (!redisUtil.setIfAbsent(dedupKey, "1", DEDUP_TTL_SECONDS, TimeUnit.SECONDS)) {
            log.info("[im-qq] 重复消息，跳过 - messageId: {}", event.getMessageId());
            return;
        }

        // ③ 纯文本判定：含 CQ 码或空则不处理
        String userText = event.getRawMessage();
        if (StringUtils.isBlank(userText) || userText.contains(CQ_PREFIX)) {
            napCatApiClient.sendPrivateText(qqNumber, PROMPT_NON_TEXT);
            return;
        }

        // ④ 校验一：是否绑定
        Optional<QqUserBindingDO> bindingOpt = qqUserBindingRepository.findByQqNumber(String.valueOf(qqNumber));
        if (bindingOpt.isEmpty()) {
            napCatApiClient.sendPrivateText(qqNumber, PROMPT_NOT_BOUND);
            return;
        }
        QqUserBindingDO binding = bindingOpt.get();

        // ⑤ 校验二：是否设置默认模型
        String modelName = binding.getDefaultModelName();
        if (StringUtils.isBlank(modelName)) {
            napCatApiClient.sendPrivateText(qqNumber, PROMPT_NO_MODEL);
            return;
        }

        // ⑥ 校验三：模型是否已在 agent_model_config 配置且启用
        List<String> models = agentModelConfigService.listModelNames(binding.getUserId());
        if (models == null || !models.contains(modelName)) {
            napCatApiClient.sendPrivateText(qqNumber, String.format(PROMPT_MODEL_NOT_CONFIGURED, modelName));
            return;
        }

        // ⑦ 组装 AgentChatDTO
        AgentChatDTO dto = new AgentChatDTO();
        dto.setUserId(binding.getUserId());
        dto.setMessage(userText);
        dto.setModelName(modelName);
        dto.setSessionId(buildSessionId(botId, qqNumber));
        dto.setSource(MessageSource.QQ);

        // ⑧ 异步驱动 Agent 分段回复，避免阻塞 webhook 响应
        QqSegmentListener listener = new QqSegmentListener(napCatApiClient, qqNumber, napCatProperties.getSendIntervalMs());
        Thread.ofVirtual().name("qq-agent-", 0).start(() -> {
            try {
                agentService.chatSegmented(dto, listener);
            } catch (Exception e) {
                log.error("[im-qq] Agent 执行异常 - sessionId: {}", dto.getSessionId(), e);
                listener.onError(e.getMessage());
            }
        });
    }

    /**
     * 构造 IM 会话 ID：qq_c2c_<botQQ>_<userQQ>
     */
    private String buildSessionId(Long botId, Long qqNumber) {
        return String.format("qq_c2c_%d_%d", botId, qqNumber);
    }
}