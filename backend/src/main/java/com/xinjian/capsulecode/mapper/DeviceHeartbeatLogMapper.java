package com.xinjian.capsulecode.mapper;

import com.xinjian.capsulecode.model.DeviceHeartbeatLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DeviceHeartbeatLogMapper {
    void insert(DeviceHeartbeatLog log);

    List<DeviceHeartbeatLog> selectByTimestampRange(
            @Param("since") long since,
            @Param("until") long until
    );
}
