package cn.nuaa.jensonxu.fairy.integration.agent.harness.pii;

import com.alibaba.cloud.ai.graph.agent.hook.pii.PIIDetector;
import com.alibaba.cloud.ai.graph.agent.hook.pii.PIIDetectors;
import com.alibaba.cloud.ai.graph.agent.hook.pii.PIIMatch;

import com.alibaba.nacos.common.utils.StringUtils;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 中文场景 PII 复合检测器
 * 组合 Email、信用卡号、中国大陆手机号、居民身份证号四类检测器，
 * 对输入文本执行并行扫描并合并检测结果。
 * 供 PIIDetectionHook 在 Agent 消息管道中调用，实现敏感信息自动脱敏。
 */
@Component
public class ChinesePIIDetector implements PIIDetector {

    // 复用框架内置检测器
    private static final PIIDetector EMAIL_DETECTOR = PIIDetectors.emailDetector();
    private static final PIIDetector CREDIT_CARD_DETECTOR = PIIDetectors.creditCardDetector();

    // 中国大陆手机号：11 位，第一位 1，第二位 3-9，负向前后查找防止匹配到更长数字串中的子串
    private static final PIIDetector PHONE_DETECTOR = PIIDetectors.regexDetector(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)", "CHINESE_PHONE");

    // 中国居民身份证号：18 位，覆盖合法地区码、日期段（年份 19xx/20xx）、顺序码与校验位
    private static final PIIDetector ID_CARD_DETECTOR = PIIDetectors.regexDetector(
            "(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)",
            "CHINESE_ID");

    private static final List<PIIDetector> DETECTORS = List.of(EMAIL_DETECTOR, CREDIT_CARD_DETECTOR, PHONE_DETECTOR, ID_CARD_DETECTOR);

    /**
     * 对文本执行全类型 PII 扫描
     * 并行调用所有子检测器，合并返回命中的 PIIMatch 列表
     *
     * @param text 待检测的文本内容
     * @return 命中的 PII 匹配结果列表，未命中时返回空列表
     */
    @Override
    public List<PIIMatch> detect(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return DETECTORS.stream()
                .flatMap(detector -> detector.detect(text).stream())
                .toList();
    }
}
