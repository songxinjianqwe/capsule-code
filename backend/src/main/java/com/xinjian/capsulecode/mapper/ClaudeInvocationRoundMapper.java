package com.xinjian.capsulecode.mapper;

import com.xinjian.capsulecode.model.ClaudeInvocationRound;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ClaudeInvocationRoundMapper {
    void insert(ClaudeInvocationRound round);
    List<ClaudeInvocationRound> selectByInvocationId(@Param("invocationId") Long invocationId);
    void updateToolResult(@Param("id") Long id, @Param("toolResult") String toolResult);
}
