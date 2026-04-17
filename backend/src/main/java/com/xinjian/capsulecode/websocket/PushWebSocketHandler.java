package com.xinjian.capsulecode.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinjian.capsulecode.model.DeviceAliases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class PushWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PushWebSocketHandler.class);

    // sessionId -> session
    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    // deviceId -> session（同一设备最多一个连接）
    private final ConcurrentHashMap<String, WebSocketSession> deviceSessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // deviceId -> 未读消息队列（设备离线时暂存，重连后补发；最多保留 20 条）
    private final ConcurrentHashMap<String, Deque<String>> pendingMessages = new ConcurrentHashMap<>();
    private static final int MAX_PENDING = 20;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String deviceId = extractParam(session, "deviceId");
        if (deviceId != null) {
            // 关闭同一 deviceId 的旧连接，防止重复收消息
            WebSocketSession old = deviceSessions.put(deviceId, session);
            if (old != null && old.isOpen()) {
                try { old.close(CloseStatus.NORMAL); } catch (Exception ignored) {}
                sessions.remove(old);
                log.info("Push client reconnected, closed old session: {}", alias(deviceId));
            }
            sessions.add(session);
            log.info("Push client connected: {} ip={} total={}", alias(deviceId), session.getRemoteAddress(), sessions.size());
            // 补发离线期间积压的消息
            Deque<String> pending = pendingMessages.remove(deviceId);
            if (pending != null && !pending.isEmpty()) {
                log.info("Replaying {} pending messages to {}", pending.size(), alias(deviceId));
                for (String raw : pending) {
                    try { synchronized (session) { session.sendMessage(new TextMessage(raw)); } }
                    catch (Exception e) { log.warn("Failed to replay pending: {}", e.getMessage()); }
                }
            }
        } else {
            sessions.add(session);
            log.info("Push client connected: id={} ip={} (no deviceId) total={}", session.getId(), session.getRemoteAddress(), sessions.size());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        String deviceId = extractParam(session, "deviceId");
        if (deviceId != null) {
            deviceSessions.remove(deviceId);
            log.info("Push client disconnected: {} total={}", alias(deviceId), sessions.size());
        } else {
            log.info("Push client disconnected: id={} total={}", session.getId(), sessions.size());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String deviceId = extractParam(session, "deviceId");
        log.warn("Push transport error: {} error={}", deviceId != null ? alias(deviceId) : session.getId(), exception.getMessage());
    }

    /** 获取所有在线设备的 deviceId 集合 */
    public Set<String> getOnlineDeviceIds() {
        Set<String> result = new java.util.HashSet<>();
        for (Map.Entry<String, WebSocketSession> entry : deviceSessions.entrySet()) {
            if (entry.getValue().isOpen()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** 推送给指定设备 */
    public boolean pushToDevice(String deviceId, String title, String body) {
        return pushToDevice(deviceId, title, body, null);
    }

    /** 推送给指定设备（带可选 goodsLink） */
    public boolean pushToDevice(String deviceId, String title, String body, String goodsLink) {
        String json;
        try { json = buildJson(title, body, goodsLink); } catch (Exception e) { return false; }
        WebSocketSession s = deviceSessions.get(deviceId);
        if (s == null || !s.isOpen()) {
            log.warn("pushToDevice: device not connected, queuing: {}", alias(deviceId));
            enqueuePending(deviceId, json);
            return false;
        }
        try {
            synchronized (s) { s.sendMessage(new TextMessage(json)); }
            log.info("Pushed to {}: title={}", alias(deviceId), title);
            return true;
        } catch (Exception e) {
            log.warn("pushToDevice error, queuing: {}", e.getMessage());
            enqueuePending(deviceId, json);
            return false;
        }
    }

    /** 推送给所有在线设备 */
    public void pushToAll(String title, String body) {
        pushToAll(title, body, null);
    }

    /** 推送给所有在线设备（带可选 goodsLink） */
    public void pushToAll(String title, String body, String goodsLink) {
        String json;
        try { json = buildJson(title, body, goodsLink); } catch (Exception e) { log.error("pushToAll buildJson error", e); return; }
        TextMessage msg = new TextMessage(json);
        int count = 0;
        for (Map.Entry<String, WebSocketSession> entry : deviceSessions.entrySet()) {
            String deviceId = entry.getKey();
            WebSocketSession s = entry.getValue();
            if (s.isOpen()) {
                try { synchronized (s) { s.sendMessage(msg); } count++; }
                catch (Exception e) {
                    log.warn("Failed to send to {}, queuing: {}", alias(deviceId), e.getMessage());
                    enqueuePending(deviceId, json);
                }
            } else {
                enqueuePending(deviceId, json);
            }
        }
        log.info("pushToAll ({} sent)", count);
    }

    private void enqueuePending(String deviceId, String json) {
        Deque<String> queue = pendingMessages.computeIfAbsent(deviceId, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(json);
            while (queue.size() > MAX_PENDING) queue.removeFirst();
        }
        log.debug("Queued pending message for {}, queue size={}", alias(deviceId), queue.size());
    }

    private String buildJson(String title, String body, String goodsLink) throws Exception {
        Map<String, String> map = new HashMap<>();
        map.put("title", title);
        map.put("body", body);
        if (goodsLink != null && !goodsLink.isEmpty()) {
            map.put("goodsLink", goodsLink);
        }
        return objectMapper.writeValueAsString(map);
    }

    private static String alias(String deviceId) {
        return DeviceAliases.resolve(deviceId) + "(" + deviceId + ")";
    }

    private String extractParam(WebSocketSession session, String name) {
        try {
            String query = session.getUri().getQuery();
            if (query == null) return null;
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && name.equals(kv[0])) return kv[1];
            }
        } catch (Exception ignored) {}
        return null;
    }
}
