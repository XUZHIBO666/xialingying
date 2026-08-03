package com.demo.demo.Service.Resume;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.demo.Mapper.ResumeApplicationRecordMapper;
import com.demo.demo.model.ResumeApplicationRecord;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 猎聘投递记录服务 — 追踪每次投递的状态和结果。
 *
 * <p>注意：实际浏览器操作由 MCP Server (liepin-mcp-server) 完成，
 * 本服务只负责数据库层面的投递记录管理。
 * Agent 通过 MCP 工具完成投递后，由 ResumeTool 回调本服务记录结果。
 */
@Slf4j
@Service
public class LiepinApplyService {

    @Resource
    private ResumeApplicationRecordMapper recordMapper;

    /**
     * 记录一次投递。
     */
    public void recordApply(String userId, Long resumeId, String platform,
                            String jobTitle, String companyName, String jobUrl,
                            boolean success, String message) {
        ResumeApplicationRecord record = new ResumeApplicationRecord();
        record.setUserId(userId);
        record.setResumeId(resumeId);
        record.setPlatform(platform);
        record.setJobTitle(jobTitle);
        record.setCompanyName(companyName);
        record.setJobUrl(jobUrl);
        record.setApplicationStatus(success ? "success" : "failed");
        record.setResultMessage(message);
        record.setAppliedAt(LocalDateTime.now());
        recordMapper.insert(record);
        log.info("[投递记录] userId={} job={}@{} status={}",
                maskUserId(userId), jobTitle, companyName, record.getApplicationStatus());
    }

    /**
     * 批量记录投递结果（从 MCP batch_apply 返回结果中解析）。
     *
     * @param details MCP 返回的投递明细: [{title, company, success, message}, ...]
     */
    public int recordBatchResults(String userId, Long resumeId, String platform,
                                  List<java.util.Map<String, Object>> details) {
        int count = 0;
        for (var detail : details) {
            String title = String.valueOf(detail.getOrDefault("title", "未知"));
            String company = String.valueOf(detail.getOrDefault("company", "未知"));
            String url = String.valueOf(detail.getOrDefault("url", ""));
            boolean success = Boolean.TRUE.equals(detail.get("success"));
            String message = String.valueOf(detail.getOrDefault("message", ""));

            recordApply(userId, resumeId, platform, title, company, url, success, message);
            count++;
        }
        return count;
    }

    /** 检查是否已投过该岗位（去重） */
    public boolean alreadyApplied(String userId, String jobUrl) {
        if (jobUrl == null || jobUrl.isBlank()) return false;
        return recordMapper.selectCount(
                new LambdaQueryWrapper<ResumeApplicationRecord>()
                        .eq(ResumeApplicationRecord::getUserId, userId)
                        .eq(ResumeApplicationRecord::getJobUrl, jobUrl)
                        .eq(ResumeApplicationRecord::getApplicationStatus, "success")) > 0;
    }

    /** 今日投递统计 */
    public long getTodayCount(String userId) {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        return recordMapper.selectCount(
                new LambdaQueryWrapper<ResumeApplicationRecord>()
                        .eq(ResumeApplicationRecord::getUserId, userId)
                        .ge(ResumeApplicationRecord::getAppliedAt, todayStart));
    }

    /** 今日投递记录 */
    public List<ResumeApplicationRecord> getTodayRecords(String userId) {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        return recordMapper.selectList(
                new LambdaQueryWrapper<ResumeApplicationRecord>()
                        .eq(ResumeApplicationRecord::getUserId, userId)
                        .ge(ResumeApplicationRecord::getAppliedAt, todayStart)
                        .orderByDesc(ResumeApplicationRecord::getAppliedAt));
    }

    // ==================== 工具方法 ====================

    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) return "***";
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }
}
