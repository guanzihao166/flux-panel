package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.Announcement;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Map;

public interface AnnouncementService extends IService<Announcement> {
    R adminList();
    R saveAnnouncement(Map<String, Object> params);
    R deleteAnnouncement(Long id);
    R history();
    R pending();
    R dismiss(Long id);
}
