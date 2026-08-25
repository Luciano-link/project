package com.luciano.wechat;

import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.luciano.config.SecurityProperties;
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
    private final com.luciano.agent.ClarifyService clarifyService;
    private final com.luciano.agent.TaskStateManager taskStateManager;
    private final com.luciano.agent.PlannerService plannerService;
    private final com.luciano.agent.ExecutorService executorService;

    public WechatController(SessionManager sessionManager, SecurityProperties securityProperties,
                            SkillRouter skillRouter, RagService ragService,
                            com.luciano.agent.ClarifyService clarifyService,
                            com.luciano.agent.TaskStateManager taskStateManager,
                            com.luciano.agent.PlannerService plannerService,
                            com.luciano.agent.ExecutorService executorService) {
        this.sessionManager = sessionManager;
        this.securityProperties = securityProperties;
        this.skillRouter = skillRouter;
        this.ragService = ragService;
        this.clarifyService = clarifyService;
        this.taskStateManager = taskStateManager;
        this.plannerService = plannerService;
        this.executorService = executorService;
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

    /** Agent 澄清调试:启动任务并生成引导(需鉴权,userId 可选) */
    @PostMapping("/agent/start")
    public ResponseEntity<?> agentStart(@RequestParam String goal,
                                        @RequestParam(required = false) String userId,
                                        HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        String uid = (userId == null || userId.isBlank()) ? "debug-agent" : userId;
        String guide = clarifyService.start(uid, goal);
        return ResponseEntity.ok(Map.of("userId", uid, "guide", guide));
    }

    /** Agent 澄清调试:提交澄清回复,返回画像是否完整(需鉴权) */
    @PostMapping("/agent/reply")
    public ResponseEntity<?> agentReply(@RequestParam String text,
                                        @RequestParam(required = false) String userId,
                                        HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        String uid = (userId == null || userId.isBlank()) ? "debug-agent" : userId;
        boolean complete = clarifyService.parseReply(uid, text);
        com.luciano.agent.TaskState state = taskStateManager.get(uid);
        return ResponseEntity.ok(Map.of(
                "userId", uid,
                "complete", complete,
                "phase", state == null ? "NONE" : state.getPhase().name(),
                "missing", clarifyService.missingFields(uid),
                "profile", state == null ? java.util.Map.of() : state.getProfile()));
    }

    /** Agent 拆解调试:基于画像生成子任务清单(需鉴权,阶段须为 EXECUTING) */
    @PostMapping("/agent/plan")
    public ResponseEntity<?> agentPlan(@RequestParam(required = false) String userId,
                                       HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        String uid = (userId == null || userId.isBlank()) ? "debug-agent" : userId;
        com.luciano.agent.TaskState state = taskStateManager.get(uid);
        if (state == null || state.getPhase() != com.luciano.agent.TaskState.Phase.EXECUTING) {
            return ResponseEntity.status(400).body(Map.of("error", "任务不在执行阶段,请先 start+reply 完成澄清"));
        }
        String plan = plannerService.plan(state);
        return ResponseEntity.ok(Map.of("plan", plan));
    }

    /** Agent 执行调试:逐步执行子任务,返回最终方案(需鉴权) */
    @PostMapping("/agent/execute")
    public ResponseEntity<?> agentExecute(@RequestParam(required = false) String userId,
                                          HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        String uid = (userId == null || userId.isBlank()) ? "debug-agent" : userId;
        String finalPlan = executorService.execute(uid);
        if (finalPlan == null) {
            return ResponseEntity.status(400).body(Map.of("error", "无可执行任务,请先完成 start+reply+plan"));
        }
        return ResponseEntity.ok(Map.of("final", finalPlan));
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
