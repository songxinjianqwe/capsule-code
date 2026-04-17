package com.xinjian.capsulecode.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ClaudeSessionMapper {
    String findSessionId(@Param("deviceId") String deviceId);
    void upsert(@Param("deviceId") String deviceId,
                @Param("sessionId") String sessionId,
                @Param("updatedAt") long updatedAt);
    void delete(@Param("deviceId") String deviceId);
}
