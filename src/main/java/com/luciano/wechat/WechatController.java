package com.luciano.wechat;

import com.luciano.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信 Bot REST 调试接口。
 * 所有接口需携带鉴权 token(请求头 X-Auth-Token),防止未授权访问。
 */
@RestController
@RequestMapping("/wechat")
public class WechatController {

    private final WechatBotRunner botRunner;
    private final SecurityProperties securityProperties;

    public WechatController(WechatBotRunner botRunner, SecurityProperties securityProperties) {
        this.botRunner = botRunner;
        this.securityProperties = securityProperties;
    }

    /** 获取登录二维码内容(需鉴权) */
    @GetMapping("/qrcode")
    public ResponseEntity<?> qrcode(HttpServletRequest request) {
        if (!authorized(request)) {
            return ResponseEntity.status(401).body(Map.of("error", "未授权:缺少或错误的 X-Auth-Token"));
        }
        if (botRunner.isLoggedIn()) {
            return ResponseEntity.ok(Map.of("loggedIn", true, "message", "已登录,无需扫码"));
        }
        String content = botRunner.getQrcodeContent();
        if (content == null) {
            return ResponseEntity.status(500).body(Map.of("error", "二维码尚未生成"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(content);
    }

    /** 查询登录状态 */
    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        if (!authorized(request)) {
            return ResponseEntity.status(401).body(Map.of("error", "未授权:缺少或错误的 X-Auth-Token"));
        }
        return ResponseEntity.ok(Map.of(
                "loggedIn", botRunner.isLoggedIn(),
                "botId", botRunner.getBotId()));
    }

    /** 简单鉴权校验 */
    private boolean authorized(HttpServletRequest request) {
        String token = securityProperties.getApiToken();
        if (token == null || token.isBlank()) {
            // 未配置 token 时禁止访问,避免裸奔
            return false;
        }
        String provided = request.getHeader("X-Auth-Token");
        return token.equals(provided);
    }
}
