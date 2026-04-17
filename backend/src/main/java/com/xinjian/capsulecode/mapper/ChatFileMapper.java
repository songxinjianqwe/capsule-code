package com.xinjian.capsulecode.mapper;

import com.xinjian.capsulecode.model.ChatFile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatFileMapper {
    void insertFile(ChatFile file);
    ChatFile selectById(String fileUuid);
}
