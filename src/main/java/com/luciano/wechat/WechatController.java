package com.luciano.wechat;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 微信 iLink Bot REST 接口。
 */
@RestController
@RequestMapping("/wechat")
public class WechatController {

    private final WechatBotService botService;
    private final FunctionCallService functionCallService;

    public WechatController(WechatBotService botService, FunctionCallService functionCallService) {
        this.botService = botService;
        this.functionCallService = functionCallService;
    }

    /**
     * 获取二维码图片(浏览器扫码登录)。
     * 兼容 base64 / data URI / URL 三种格式。
     */
    @GetMapping("/qrcode")
    public ResponseEntity<?> qrcode() {
        if (botService.isLoggedIn()) {
            return ResponseEntity.ok(Map.of("loggedIn", true, "message", "已登录,无需扫码"));
        }
        String content = botService.login();
        if (content == null) {
            return ResponseEntity.ok(Map.of("loggedIn", true, "message", "已登录,无需扫码"));
        }

        // 1. URL 形式 → 重定向
        String trimmed = content.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, trimmed)
                    .build();
        }

        // 2. data URI 形式 → 剥离前缀
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

        // 3. 纯 base64 → 解码返回图片
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .body(imageBytes);
        } catch (IllegalArgumentException e) {
            // 解码失败,当作纯文本返回(便于排查)
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        }
    }

    /**
     * 查询登录状态。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loggedIn", botService.isLoggedIn());
        result.put("status", botService.getStatus().name());
        return result;
    }

    /**
     * 取出并清空积压消息。
     */
    @GetMapping("/messages")
    public List<Map<String, Object>> messages() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WeixinMessage msg : botService.pollMessages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", msg.getMessage_id());
            item.put("fromUserId", msg.getFrom_user_id());
            item.put("toUserId", msg.getTo_user_id());
            item.put("createTimeMs", msg.getCreate_time_ms());
            item.put("text", extractText(msg));
            result.add(item);
        }
        return result;
    }

    /**
     * 发送文本消息。body: {"toUserId": "xxx@im.wechat", "text": "..."}
     */
    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody SendRequest request) {
        if (!botService.isLoggedIn()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录,请先访问 /wechat/qrcode 扫码"));
        }
        if (request.toUserId() == null || request.toUserId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "toUserId 不能为空"));
        }
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        try {
            botService.sendText(request.toUserId(), request.text());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 语音发送调试接口:用不同参数发一条短语音,排查语音消息为何收不到。
     * 参数:toUserId(可选,默认最近发消息的人)、sampleRate(24000/16000)、
     * playtimeUnit(ms/s)、transcript(true/false)。
     */
    @GetMapping("/test-voice")
    public ResponseEntity<?> testVoice(
            @RequestParam(required = false) String toUserId,
            @RequestParam(required = false) Integer sampleRate,
            @RequestParam(required = false) String playtimeUnit,
            @RequestParam(required = false, defaultValue = "false") boolean transcript) {
        if (!botService.isLoggedIn()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录,请先访问 /wechat/qrcode 扫码"));
        }
        try {
            return ResponseEntity.ok(botService.sendTestVoice(toUserId, sampleRate, playtimeUnit, transcript));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Function Calling 演示:输入一个问题,返回「模型→工具→模型」完整调用链。
     * 例:GET /wechat/function-calling?q=北京明天天气怎么样
     *     GET /wechat/function-calling?q=现在几点了
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
