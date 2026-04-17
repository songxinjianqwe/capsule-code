package com.xinjian.capsulecode.controller;

import com.xinjian.capsulecode.mapper.ClaudeConversationMapper;
import com.xinjian.capsulecode.mapper.ClaudeMessageMapper;
import com.xinjian.capsulecode.model.ClaudeConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/claude")
public class ClaudeController {

    private static final Logger log = LoggerFactory.getLogger(ClaudeController.class);

    @Value("${claude.work-dir}")
    private String workDir;

    @Autowired
    private ClaudeConversationMapper claudeConversationMapper;

    @Autowired
    private ClaudeMessageMapper claudeMessageMapper;

    @GetMapping("/conversations")
    public List<ClaudeConversation> getConversations(@RequestParam String deviceId) {
        return claudeConversationMapper.findAllByDevice(deviceId);
    }

    /** 测试用：清除指定 deviceId 的所有会话（DB + tmux session）。仅供自动化测试调用。 */
    @DeleteMapping("/conversations/cleanup")
    public Map<String, Object> deleteConversationsByDevice(@RequestParam String deviceId) {
        List<ClaudeConversation> convs = claudeConversationMapper.findAllByDevice(deviceId);
        int count = convs.size();
        for (ClaudeConversation conv : convs) {
            try { claudeMessageMapper.deleteByConvId(conv.getConvId()); } catch (Exception ignored) {}
            try { claudeConversationMapper.deleteByConvId(conv.getConvId()); } catch (Exception ignored) {}
            // kill tmux session if exists
            String session = "claude_" + conv.getConvId().replace("-", "").substring(0, 8);
            try {
                new ProcessBuilder("tmux", "kill-session", "-t", session)
                    .redirectErrorStream(true).start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        log.info("Deleted {} conversations for deviceId={}", count, deviceId);
        return Map.of("deleted", count, "deviceId", deviceId);
    }

    @GetMapping("/messages")
    public List<Map<String, String>> getMessages(@RequestParam String convId) {
        return claudeMessageMapper.findByConvId(convId);
    }

    @GetMapping("/context")
    public Map<String, String> getContext() {
        String branch = runGit("git", "branch", "--show-current");
        String status = runGit("git", "status", "--short");
        int changedCount = status.isBlank() ? 0 : status.strip().split("\n").length;

        Map<String, String> result = new java.util.HashMap<>();
        result.put("workDir", workDir);
        result.put("branch", branch.isBlank() ? "unknown" : branch.strip());
        result.put("changedFiles", String.valueOf(changedCount));
        return result;
    }

    private String runGit(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .directory(new File(workDir))
                    .redirectErrorStream(true)
                    .start();
            String output = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            p.waitFor();
            return output;
        } catch (Exception e) {
            log.warn("git command failed: {}", e.getMessage());
            return "";
        }
    }
}
