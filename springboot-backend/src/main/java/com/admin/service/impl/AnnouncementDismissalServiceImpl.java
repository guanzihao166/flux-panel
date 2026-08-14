package com.admin.service.impl;

import com.admin.entity.AnnouncementDismissal;
import com.admin.mapper.AnnouncementDismissalMapper;
import com.admin.service.AnnouncementDismissalService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class AnnouncementDismissalServiceImpl extends ServiceImpl<AnnouncementDismissalMapper, AnnouncementDismissal>
        implements AnnouncementDismissalService {
}
