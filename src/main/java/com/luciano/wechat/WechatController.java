package com.luciano.wechat;

import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.luciano.config.SecurityProperties;
import com.luciano.agent.AgentTaskDetector;
import com.luciano.rag.RagService;
import com.luciano.skill.Skill;
import com.luciano.skill.SkillRouter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 微信 Bot 多会话 REST 接口。
 * 每个用户通过创建会话获得专属登录二维码,扫码绑定成功后即建立独立连接。
 * 所有接口需携带鉴权 token(请求头 X-Auth-Token)。
 */
@RestController
@RequestMapping("/wechat")
public class WechatController {

    private static final Logger log = LoggerFactory.getLogger(WechatController.class);

    private final SessionManager sessionManager;
    private final SecurityProperties securityProperties;
    private final SkillRouter skillRouter;
    private final RagService ragService;

    public WechatController(SessionManager sessionManager, SecurityProperties securityProperties,
                            SkillRouter skillRouter, RagService ragService) {
        this.sessionManager = sessionManager;
        this.securityProperties = securityProperties;
        this.skillRouter = skillRouter;
        this.ragService = ragService;
    }

    /** 创建会话:分配专属 client 并返回登录二维码(需鉴权) */
    @PostMapping("/session")
    public ResponseEntity<?> createSession(HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        String sessionId = UUID.randomUUID().toString();
        try {
            String qrcode = sessionManager.create(sessionId).get(15, TimeUnit.SECONDS);
            log.info("已创建会话 {},二维码: {}", sessionId, qrcode);
            return ResponseEntity.ok(Map.of("sessionId", sessionId, "qrcode", qrcode));
        } catch (Exception e) {
            sessionManager.remove(sessionId);
            log.error("创建会话失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "创建会话失败: " + e.getMessage()));
        }
    }

    /** 查询全部会话及登录状态(需鉴权) */
    @GetMapping("/session")
    public ResponseEntity<?> listSessions(HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        return ResponseEntity.ok(sessionManager.all());
    }

    /** 查询单会话登录状态(需鉴权) */
    @GetMapping("/session/{sessionId}/status")
    public ResponseEntity<?> sessionStatus(@PathVariable String sessionId, HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        LoginStatus status = sessionManager.status(sessionId);
        if (status == null) {
            return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
        }
        String botId = null;
        if (status.isLoggedIn()) {
            LoginContext ctx = sessionManager.get(sessionId).getLoginContext();
            botId = ctx == null ? null : ctx.getBotId();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("status", status.getStatus() == null ? "UNKNOWN" : status.getStatus().name());
        body.put("loggedIn", status.isLoggedIn());
        body.put("botId", botId);
        return ResponseEntity.ok(body);
    }

    /** 移除会话,关闭连接并清除登录态(需鉴权) */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<?> removeSession(@PathVariable String sessionId, HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        sessionManager.remove(sessionId);
        return ResponseEntity.ok(Map.of("removed", true));
    }

    /** 消息路由调试:测试 Skill / RAG / LLM 兜底三层命中(需鉴权,userId 可选用于备忘录隔离) */
    @GetMapping("/route")
    public ResponseEntity<?> route(@RequestParam String text,
                                   @RequestParam(required = false) String userId,
                                   HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        String uid = (userId == null || userId.isBlank()) ? "debug" : userId;
        if (AgentTaskDetector.isAgentTask(text)) {
            return ResponseEntity.ok(Map.of("hit", "agent", "message", "将走自主规划 Agent(Planner→Executor→汇总)"));
        }
        Skill skill = skillRouter.match(text);
        if (skill != null) {
            log.info("[route-debug] 命中 skill = {}, text = {}", skill.name(), text);
            return ResponseEntity.ok(Map.of("hit", "skill", "skill", skill.name(),
                    "reply", skill.execute(uid, text)));
        }
        String knowledge = ragService.retrieve(text);
        if (knowledge != null) {
            log.info("[route-debug] 命中 rag, text = {}", text);
            return ResponseEntity.ok(Map.of("hit", "rag", "knowledge", knowledge));
        }
        return ResponseEntity.ok(Map.of("hit", "llm", "message", "未命中 Skill/RAG,将走 LLM 兜底闲聊"));
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

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "未授权:缺少或错误的 X-Auth-Token"));
    }
}
