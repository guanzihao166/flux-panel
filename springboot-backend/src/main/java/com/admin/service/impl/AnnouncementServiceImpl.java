package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Announcement;
import com.admin.entity.AnnouncementDismissal;
import com.admin.mapper.AnnouncementMapper;
import com.admin.service.AnnouncementDismissalService;
import com.admin.service.AnnouncementService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AnnouncementServiceImpl extends ServiceImpl<AnnouncementMapper, Announcement> implements AnnouncementService {
    @Resource
    private AnnouncementDismissalService dismissalService;

    @Override
    public R adminList() {
        return R.ok(list(new QueryWrapper<Announcement>().orderByDesc("created_time")));
    }

    @Override
    @Transactional
    public R saveAnnouncement(Map<String, Object> params) {
        String title = params.get("title") == null ? "" : params.get("title").toString().trim();
        String content = params.get("content") == null ? "" : params.get("content").toString().trim();
        if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) return R.err("公告标题和正文不能为空");

        Announcement item = null;
        if (params.get("id") != null) item = getById(Long.valueOf(params.get("id").toString()));
        long now = System.currentTimeMillis();
        if (item == null) {
            item = new Announcement();
            item.setCreatedTime(now);
        }
        int status = params.get("status") == null ? 1 : Integer.parseInt(params.get("status").toString());
        item.setTitle(title);
        item.setContent(content);
        item.setStatus(status);
        item.setUpdatedTime(now);
        if (status == 1 && item.getPublishedTime() == null) item.setPublishedTime(now);
        if (status != 1) item.setPublishedTime(null);
        saveOrUpdate(item);
        return R.ok(item);
    }

    @Override
    @Transactional
    public R deleteAnnouncement(Long id) {
        dismissalService.remove(new QueryWrapper<AnnouncementDismissal>().eq("announcement_id", id));
        return removeById(id) ? R.ok("公告已删除") : R.err("公告不存在");
    }

    @Override
    public R history() {
        return R.ok(list(new QueryWrapper<Announcement>().eq("status", 1).orderByDesc("published_time")));
    }

    @Override
    public R pending() {
        if (JwtUtil.getRoleIdFromToken() == 0) return R.ok(Collections.emptyList());
        Integer userId = JwtUtil.getUserIdFromToken();
        List<Long> dismissed = dismissalService.list(new QueryWrapper<AnnouncementDismissal>().eq("user_id", userId))
                .stream().map(AnnouncementDismissal::getAnnouncementId).collect(Collectors.toList());
        QueryWrapper<Announcement> query = new QueryWrapper<Announcement>().eq("status", 1).orderByDesc("published_time");
        if (!dismissed.isEmpty()) query.notIn("id", dismissed);
        return R.ok(list(query));
    }

    @Override
    public R dismiss(Long id) {
        Integer userId = JwtUtil.getUserIdFromToken();
        if (getById(id) == null) return R.err("公告不存在");
        AnnouncementDismissal existing = dismissalService.getOne(new QueryWrapper<AnnouncementDismissal>()
                .eq("announcement_id", id).eq("user_id", userId));
        if (existing == null) {
            AnnouncementDismissal dismissal = new AnnouncementDismissal();
            dismissal.setAnnouncementId(id);
            dismissal.setUserId(userId);
            dismissal.setCreatedTime(System.currentTimeMillis());
            dismissalService.save(dismissal);
        }
        return R.ok("已设置不再显示");
    }
}
