package com.github.lystran.mochat.call.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** 好友关系实体，对应 user_friendships 表。 */
@TableName("user_friendships")
public class Friendship {
    @TableId
    private Long id;

    @TableField("uid_1")
    private Long uid1;

    @TableField("uid_2")
    private Long uid2;

    @TableField("status")
    private String status;

    @TableField("blocked_by")
    private Integer blockedBy;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUid1() { return uid1; }
    public void setUid1(Long uid1) { this.uid1 = uid1; }

    public Long getUid2() { return uid2; }
    public void setUid2(Long uid2) { this.uid2 = uid2; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getBlockedBy() { return blockedBy; }
    public void setBlockedBy(Integer blockedBy) { this.blockedBy = blockedBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
