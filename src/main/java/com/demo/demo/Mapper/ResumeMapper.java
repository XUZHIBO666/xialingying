package com.demo.demo.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.demo.model.Resume;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResumeMapper extends BaseMapper<Resume> {
    // BaseMapper 已经提供了常用的 CRUD 方法
    // 如需自定义方法，可在此添加
}