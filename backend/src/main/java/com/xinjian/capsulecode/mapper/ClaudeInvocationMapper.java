package com.xinjian.capsulecode.mapper;

import com.xinjian.capsulecode.model.ClaudeInvocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ClaudeInvocationMapper {
    void insert(ClaudeInvocation invocation);
    void updateFinished(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("replyMsgId") Long replyMsgId,
                        @Param("totalRounds") int totalRounds);
    List<ClaudeInvocation> selectRecent(@Param("limit") int limit, @Param("offset") int offset);
    ClaudeInvocation selectById(@Param("id") Long id);
    int countAll();
}
