package cn.nuaa.jensonxu.fairy.web.controller;

import cn.nuaa.jensonxu.fairy.im.qq.service.QqInboundService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * QQ（NapCat / OneBot11）消息上报接入
 * 作为 HTTP 入口接收 NapCat 推送，业务编排委托给 QqInboundService
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/im/qq")
public class QqWebhookController {

    private final QqInboundService qqInboundService;

    /**
     * 接收 NapCat 的 OneBot11 上报
     * @param rawBody NapCat 推送的原始 JSON 报文
     * @return 空对象，告知 NapCat 无需执行快速操作
     */
    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> webhook(@RequestBody String rawBody) {
        log.info("[im-qq] 收到上报报文: {}", rawBody);
        qqInboundService.onReport(rawBody);
        return Collections.emptyMap();
    }
}