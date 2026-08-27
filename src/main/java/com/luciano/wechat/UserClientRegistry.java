package com.luciano.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户 → 会话客户端映射。
 * 消息到达时登记(覆盖旧绑定),用于日程提醒等主动推送时定位发送客户端。
 * 会话移除后旧 client 引用仍残留,但推送失败会被吞掉,且用户下次发消息即覆盖为新 client。
 */
@Component
public class UserClientRegistry {

    private final Map<String, ILinkClient> bindings = new ConcurrentHashMap<>();

    /** 登记用户与其当前会话客户端 */
    public void bind(String fromUserId, ILinkClient client) {
        if (fromUserId != null && client != null) {
            bindings.put(fromUserId, client);
        }
    }

    /** 获取用户当前客户端,无绑定返回 null */
    public ILinkClient get(String fromUserId) {
        return fromUserId == null ? null : bindings.get(fromUserId);
    }
}
