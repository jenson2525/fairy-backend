package cn.nuaa.jensonxu.fairy.im.qq.dto;

import com.alibaba.fastjson2.annotation.JSONField;

import lombok.Data;

/**
 * OneBot11 消息上报报文
 * NapCat 收到 QQ 消息后，按 OneBot11 标准将事件 POST 到 fairy 的 webhook，
 * 本类映射其中「私聊消息」事件所需的核心字段
 */
@Data
public class OneBotMessageEventDTO {

    /** 事件发生的时间戳（秒） */
    private Long time;

    /** 收到事件的机器人自身 QQ 号 */
    @JSONField(name = "self_id")
    private Long selfId;

    /** 上报类型：message / meta_event / notice / request，首期只处理 message */
    @JSONField(name = "post_type")
    private String postType;

    /** 消息类型：private（私聊）/ group（群聊），首期只处理 private */
    @JSONField(name = "message_type")
    private String messageType;

    /** 消息子类型：friend / group 等 */
    @JSONField(name = "sub_type")
    private String subType;

    /** 消息 ID，用于后续回复定位 */
    @JSONField(name = "message_id")
    private Long messageId;

    /** 发送者 QQ 号，作为身份映射 qq_openid -> fairy_userId 的 key */
    @JSONField(name = "user_id")
    private Long userId;

    /** 纯文本形态的消息内容，纯文本场景即用户原文 */
    @JSONField(name = "raw_message")
    private String rawMessage;

    /** 发送者信息 */
    private Sender sender;

    /**
     * 发送者信息
     */
    @Data
    public static class Sender {

        /** 发送者 QQ 号 */
        @JSONField(name = "user_id")
        private Long userId;

        /** 发送者昵称 */
        private String nickname;
    }
}