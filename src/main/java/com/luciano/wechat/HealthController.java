package com.luciano.wechat;

import com.luciano.rag.RagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查(监控/运维用,公开访问,不含敏感信息)。
 */
@RestController
public class HealthController {

    private final SessionManager sessionManager;
    private final RagService ragService;

    public HealthController(SessionManager sessionManager, RagService ragService) {
        this.sessionManager = sessionManager;
        this.ragService = ragService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", java.time.LocalDateTime.now().toString());
        body.put("sessions", sessionManager.all().size());
        body.put("ragEnabled", ragService.isEnabled());
        return ResponseEntity.ok(body);
    }
}
