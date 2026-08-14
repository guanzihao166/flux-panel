package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.lang.R;
import com.admin.service.AnnouncementService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/announcement")
public class AnnouncementController {
    @Resource
    private AnnouncementService announcementService;

    @RequireRole
    @PostMapping("/admin/list")
    public R adminList() { return announcementService.adminList(); }

    @RequireRole
    @PostMapping("/admin/save")
    public R save(@RequestBody Map<String, Object> params) { return announcementService.saveAnnouncement(params); }

    @RequireRole
    @PostMapping("/admin/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        return announcementService.deleteAnnouncement(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/history")
    public R history() { return announcementService.history(); }

    @PostMapping("/pending")
    public R pending() { return announcementService.pending(); }

    @PostMapping("/dismiss")
    public R dismiss(@RequestBody Map<String, Object> params) {
        return announcementService.dismiss(Long.valueOf(params.get("id").toString()));
    }
}
