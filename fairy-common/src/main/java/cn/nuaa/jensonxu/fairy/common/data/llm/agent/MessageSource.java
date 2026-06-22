package cn.nuaa.jensonxu.fairy.common.data.llm.agent;

/**
 * 消息来源常量
 * 标识 agent_session_message 中每条消息的产生渠道，
 * 用于区分 web/客户端与各 IM 平台
 */
public final class MessageSource {

    private MessageSource() {}

    /** web / 客户端来源（默认） */
    public static final String NORMAL = "normal";

    /** QQ（NapCat）平台来源 */
    public static final String QQ = "qq";
}