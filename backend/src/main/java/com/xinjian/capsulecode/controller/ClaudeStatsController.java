package com.xinjian.capsulecode.controller;

import com.xinjian.capsulecode.mapper.ClaudeInvocationMapper;
import com.xinjian.capsulecode.mapper.ClaudeInvocationRoundMapper;
import com.xinjian.capsulecode.model.ClaudeInvocation;
import com.xinjian.capsulecode.model.ClaudeInvocationRound;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/claude")
public class ClaudeStatsController {

    @Autowired
    private ClaudeInvocationMapper invocationMapper;

    @Autowired
    private ClaudeInvocationRoundMapper roundMapper;

    // 注：capsule-code 移除了 AgentMemory 功能（属于主项目群聊 AI 子系统）。

    /** 调用列表（分页） */
    @GetMapping("/invocations")
    public Map<String, Object> listInvocations(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<ClaudeInvocation> list = invocationMapper.selectRecent(limit, offset);
        int total = invocationMapper.countAll();
        return Map.of("list", list, "total", total);
    }

    /** 单次调用详情（含所有轮次） */
    @GetMapping("/invocations/{id}")
    public Map<String, Object> getInvocationDetail(@PathVariable Long id) {
        ClaudeInvocation invocation = invocationMapper.selectById(id);
        List<ClaudeInvocationRound> rounds = roundMapper.selectByInvocationId(id);
        return Map.of("invocation", invocation, "rounds", rounds);
    }
}
