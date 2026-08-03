package com.demo.demo.Service.Resume;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.demo.Mapper.ResumeMapper;
import com.demo.demo.model.Resume;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ResumeService {
    @Resource
    private ResumeMapper resumeMapper;

    /** 用户上传新简历，保存原始内容并标记为"待分析" */
    public Resume saveResume(String userId, String resumeName, String resumeContent,
                             String fileType, long fileSize) {
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setResumeName(resumeName);
        resume.setResumeContent(resumeContent);
        resume.setFileType(fileType);
        resume.setFileSize(fileSize);
        resume.setStatus("PENDING_ANALYSIS");   // 待 AI 分析
        resumeMapper.insert(resume);
        log.info("[简历] 已保存 id={} userId={} name={}", resume.getId(), userId, resumeName);
        return resume;
    }

    /** 保存 AI 分析结果 */
    public void saveAnalysisResult(Long resumeId, String analysisResult) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) return;
        resume.setAnalysisResult(analysisResult);
        resume.setStatus("ANALYZED");   // 已分析
        resume.setUpdatedAt(LocalDateTime.now());
        resumeMapper.updateById(resume);
        log.info("[简历] 分析结果已保存 id={}", resumeId);
    }
    /** 保存优化后的简历内容 */
    public void saveOptimizedContent(Long resumeId, String optimizedContent) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) return;
        resume.setOptimizedContent(optimizedContent);
        resume.setStatus("OPTIMIZED");
        resume.setUpdatedAt(LocalDateTime.now());
        resumeMapper.updateById(resume);
        log.info("[简历] 优化内容已保存 id={}", resumeId);
    }
    /** 更新简历状态 */
    public void updateStatus(Long resumeId, String status) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) return;
        resume.setStatus(status);
        resume.setUpdatedAt(LocalDateTime.now());
        resumeMapper.updateById(resume);
    }

    /** 获取用户最新的简历 */
    public Resume getLatestResume(String userId) {
        return resumeMapper.selectOne(
                new LambdaQueryWrapper<Resume>()
                        .eq(Resume::getUserId, userId)
                        .orderByDesc(Resume::getCreatedAt)
                        .last("LIMIT 1"));
    }

    public List<Resume> listResumes(String userId) {
        return resumeMapper.selectList(
                new LambdaQueryWrapper<Resume>()
                        .eq(Resume::getUserId, userId)
                        .orderByDesc(Resume::getCreatedAt));
    }

    /** 获取简历内容（默认取优化后的，否则取原始） */
    public String getEffectiveContent(Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) return null;
        if (resume.getOptimizedContent() != null && !resume.getOptimizedContent().isBlank()) {
            return resume.getOptimizedContent();
        }
        return resume.getResumeContent();
    }


}
