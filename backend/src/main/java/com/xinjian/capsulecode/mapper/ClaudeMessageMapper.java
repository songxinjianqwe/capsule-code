package com.xinjian.capsulecode.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface ClaudeMessageMapper {
    void insert(@Param("convId") String convId,
                @Param("role") String role,
                @Param("text") String text,
                @Param("createdAt") long createdAt);

    void insertWithSubtype(@Param("convId") String convId,
                           @Param("role") String role,
                           @Param("subtype") String subtype,
                           @Param("text") String text,
                           @Param("createdAt") long createdAt);

    void insertWithTokens(@Param("convId") String convId,
                          @Param("role") String role,
                          @Param("subtype") String subtype,
                          @Param("text") String text,
                          @Param("createdAt") long createdAt,
                          @Param("inputTokens") int inputTokens,
                          @Param("outputTokens") int outputTokens,
                          @Param("cacheReadTokens") int cacheReadTokens,
                          @Param("cacheCreateTokens") int cacheCreateTokens);

    /** 返回该会话累计 token 用量：input_tokens, output_tokens, cache_read_tokens, cache_create_tokens 之和 */
    Map<String, Object> sumTokensByConvId(@Param("convId") String convId);

    List<Map<String, String>> findByConvId(@Param("convId") String convId);

    /** 返回该会话最后一条消息，用于判断是否有未完成的任务 */
    Map<String, String> findLastByConvId(@Param("convId") String convId);

    void deleteByConvId(@Param("convId") String convId);
}
