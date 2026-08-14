package com.admin.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class Announcement extends BaseEntity {
    private String title;
    private String content;
    private Long publishedTime;
}
