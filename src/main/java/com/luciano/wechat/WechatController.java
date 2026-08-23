package com.luciano.wechat;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多用户会话 REST 接口。
 *
 * <p>流程:创建会话 → 拿 sessionId → 浏览器打开二维码扫码 → 绑定专属 client → 聊天。
 * 每个 sessionId 对应一个微信账号,多用户可以同时在线、互不干扰。
 */
@RestController
@RequestMapping("/wechat")
public class WechatController {

    private final WechatSessionManager sessionManager;
    private final MessageDispatcher dispatcher;
    private final FunctionCallService functionCallService;

    public WechatController(WechatSessionManager sessionManager, MessageDispatcher dispatcher,
                            FunctionCallService functionCallService) {
        this.sessionManager = sessionManager;
        this.dispatcher = dispatcher;
        this.functionCallService = functionCallService;
    }

    // ==================== 会话管理 ====================

    /**
     * 创建会话,返回专属 sessionId。POST /wechat/sessions
     */
    @PostMapping("/sessions")
    public ResponseEntity<?> createSession() {
        String sessionId = sessionManager.createSession();
        return ResponseEntity.ok(Map.of("sessionId", sessionId,
                "message", "会话已创建,请访问 /wechat/sessions/{sessionId}/qrcode 扫码登录"));
    }

    /**
     * 会话列表。GET /wechat/sessions
     */
    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions() {
        return sessionManager.listSessions();
    }

    /**
     * 注销会话,释放连接。DELETE /wechat/sessions/{sessionId}
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> removeSession(@PathVariable String sessionId) {
        if (sessionManager.removeSession(sessionId)) {
            return ResponseEntity.ok(Map.of("success", true, "message", "会话已注销"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "会话不存在: " + sessionId));
    }

    /**
     * 获取二维码图片(浏览器扫码登录)。GET /wechat/sessions/{sessionId}/qrcode
     */
    @GetMapping("/sessions/{sessionId}/qrcode")
    public ResponseEntity<?> qrcode(@PathVariable String sessionId) {
        try {
            if (sessionManager.isLoggedIn(sessionId)) {
                return ResponseEntity.ok(Map.of("loggedIn", true, "message", "已登录,无需扫码"));
            }
            String content = sessionManager.login(sessionId);
            if (content == null) {
                return ResponseEntity.ok(Map.of("loggedIn", true, "message", "已登录,无需扫码"));
            }
            return renderQrcode(content);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查询会话登录状态。GET /wechat/sessions/{sessionId}/status
     */
    @GetMapping("/sessions/{sessionId}/status")
    public ResponseEntity<?> status(@PathVariable String sessionId) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("loggedIn", sessionManager.isLoggedIn(sessionId));
            result.put("status", sessionManager.getStatus(sessionId).name());
            result.put("userId", sessionManager.getUserId(sessionId));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 取出并清空该会话的积压消息。GET /wechat/sessions/{sessionId}/messages
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> messages(@PathVariable String sessionId) {
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (WeixinMessage msg : sessionManager.pollMessages(sessionId)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("messageId", msg.getMessage_id());
                item.put("fromUserId", msg.getFrom_user_id());
                item.put("toUserId", msg.getTo_user_id());
                item.put("createTimeMs", msg.getCreate_time_ms());
                item.put("text", extractText(msg));
                result.add(item);
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 发送文本消息。POST /wechat/sessions/{sessionId}/send
     * body: {"toUserId": "xxx@im.wechat", "text": "..."}
     */
    @PostMapping("/sessions/{sessionId}/send")
    public ResponseEntity<?> send(@PathVariable String sessionId, @RequestBody SendRequest request) {
        try {
            if (!sessionManager.isLoggedIn(sessionId)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "该会话未登录,请先访问 /wechat/sessions/{sessionId}/qrcode 扫码"));
            }
            if (request.toUserId() == null || request.toUserId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "toUserId 不能为空"));
            }
            if (request.text() == null || request.text().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
            }
            sessionManager.sendText(sessionId, request.toUserId(), request.text());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 语音发送调试接口(会话级)。GET /wechat/sessions/{sessionId}/test-voice
     */
    @GetMapping("/sessions/{sessionId}/test-voice")
    public ResponseEntity<?> testVoice(@PathVariable String sessionId,
                                       @RequestParam(required = false) String toUserId,
                                       @RequestParam(required = false) Integer sampleRate,
                                       @RequestParam(required = false) String playtimeUnit,
                                       @RequestParam(required = false, defaultValue = "false") boolean transcript) {
        try {
            if (!sessionManager.isLoggedIn(sessionId)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "该会话未登录"));
            }
            WechatSession session = sessionManager.get(sessionId);
            return ResponseEntity.ok(dispatcher.sendTestVoice(session, toUserId, sampleRate, playtimeUnit, transcript));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Function Calling 演示(不依赖微信会话,纯 LLM)。GET /wechat/function-calling?q=...
     */
    @GetMapping("/function-calling")
    public ResponseEntity<?> functionCalling(@RequestParam String q) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "q 不能为空"));
        }
        try {
            FunctionCallService.RunResult result = functionCallService.run(q);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("question", result.question());
            out.put("steps", result.steps());
            out.put("finalAnswer", result.finalAnswer());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 把二维码内容渲染成响应:URL → 重定向;data URI/base64 → 图片;否则纯文本。
     */
    private ResponseEntity<?> renderQrcode(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, trimmed)
                    .build();
        }
        String base64 = trimmed;
        String mimeType = "image/png";
        int commaIdx = trimmed.indexOf(',');
        if (trimmed.startsWith("data:") && commaIdx > 0) {
            String header = trimmed.substring(0, commaIdx);
            base64 = trimmed.substring(commaIdx + 1);
            int semicolonIdx = header.indexOf(';');
            String typePart = header.substring("data:".length(), semicolonIdx > 0 ? semicolonIdx : header.length());
            if (!typePart.isEmpty()) {
                mimeType = typePart;
            }
        }
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .body(imageBytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        }
    }

    private String extractText(WeixinMessage msg) {
        List<MessageItem> items = msg.getItem_list();
        if (items == null) {
            return null;
        }
        for (MessageItem item : items) {
            if (item.getText_item() != null) {
                TextItem text = item.getText_item();
                return text.getText();
            }
        }
        return null;
    }

    public record SendRequest(String toUserId, String text) {
    }
}
