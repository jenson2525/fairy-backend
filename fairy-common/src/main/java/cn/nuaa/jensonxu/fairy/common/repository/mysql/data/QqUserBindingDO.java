package cn.nuaa.jensonxu.fairy.common.repository.mysql.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * QQ 号与 fairy 用户绑定关系实体
 * 对应表：qq_user_binding
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("qq_user_binding")
public class QqUserBindingDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** QQ 号（OneBot 的 user_id） */
    @TableField("qq_number")
    private String qqNumber;

    /** fairy 服务端用户 ID */
    @TableField("user_id")
    private String userId;

    /** 该 QQ 用户默认使用的模型名称，为空时回发提示引导配置 */
    @TableField("default_model_name")
    private String defaultModelName;

    @TableField("is_deleted")
    private Integer isDeleted;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}