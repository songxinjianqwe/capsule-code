package com.xinjian.capsulecode.mapper;

import com.xinjian.capsulecode.model.DeviceInfo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DeviceInfoMapper {
    void upsert(DeviceInfo deviceInfo);
    List<DeviceInfo> findAll();
    void updateHeartbeat(@org.apache.ibatis.annotations.Param("deviceId") String deviceId,
                         @org.apache.ibatis.annotations.Param("lastHeartbeatAt") long lastHeartbeatAt);
}
