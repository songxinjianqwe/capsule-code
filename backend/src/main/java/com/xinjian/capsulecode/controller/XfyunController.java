package com.xinjian.capsulecode.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 下发讯飞 SDK 凭证给 Android 客户端（避免硬编码进 APK）。
 *
 * 客户端启动时拉一次，调 SparkChain.init() 的时候用这里返回的值。
 * 后续 rotate key 只改 application-local.yml 的 xfyun.* 段，不需要重新发 APK。
 *
 * 安全考量：
 * - 仍然是下发到客户端，没有彻底隔离。反编译 / 抓 HTTPS 仍可拿到 key
 * - 主要好处：不在 APK 里硬编码；rotate 方便；公开 APK 时也不会因为静态扫描被抓
 */
@Slf4j
@RestController
@RequestMapping("/xfyun")
public class XfyunController {

    @Value("${xfyun.appid:}")       private String appid;
    @Value("${xfyun.apikey:}")      private String apikey;
    @Value("${xfyun.apisecret:}")   private String apisecret;
    @Value("${xfyun.secretkey:}")   private String secretkey;

    @GetMapping("/credentials")
    public Map<String, String> credentials() {
        if (appid.isBlank()) {
            log.warn("[xfyun] credentials requested but xfyun.appid is empty (application-local.yml 未配置)");
        }
        return Map.of(
                "appid", appid,
                "apikey", apikey,
                "apisecret", apisecret,
                "secretkey", secretkey
        );
    }
}
