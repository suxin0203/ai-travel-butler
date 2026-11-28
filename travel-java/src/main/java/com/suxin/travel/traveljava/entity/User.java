package com.suxin.travel.traveljava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户表：极简单表，只存登录与提醒触达所需字段 */
@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 哈希，永远不存明文 */
    private String passwordHash;
    private String email;
    private String wechatOpenid;
    /** 推送渠道偏好（契约 §2.5b）：false = 该渠道不可用（发送侧静默跳过） */
    private Boolean notifyMail;
    private Boolean notifyWechat;
    private LocalDateTime createdAt;
}
