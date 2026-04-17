package com.xinjian.capsulecode.mapper;

import com.xinjian.capsulecode.model.ClaudeConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ClaudeConversationMapper {
    ClaudeConversation findLatestActive(@Param("deviceId") String deviceId);
    ClaudeConversation findByConvId(@Param("convId") String convId);
    List<ClaudeConversation> findAllByDevice(@Param("deviceId") String deviceId);
    List<ClaudeConversation> findAll();
    void insert(ClaudeConversation conv);
    void updateLastActive(@Param("convId") String convId, @Param("lastActiveAt") long lastActiveAt);
    void updateName(@Param("convId") String convId, @Param("name") String name);
    void updateSessionId(@Param("convId") String convId, @Param("claudeSessionId") String claudeSessionId);
    void updateProcessing(@Param("convId") String convId, @Param("processing") boolean processing);
    void updateEnterpriseMode(@Param("convId") String convId, @Param("enterpriseMode") boolean enterpriseMode);
    void updateAccountMode(@Param("convId") String convId, @Param("accountMode") String accountMode);
    void updateWorkDir(@Param("convId") String convId, @Param("workDir") String workDir);
    void deleteByConvId(@Param("convId") String convId);
}
