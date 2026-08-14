package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

@Data
public class AnnouncementDismissal {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long announcementId;
    private Integer userId;
    private Long createdTime;
}
