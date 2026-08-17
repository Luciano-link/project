package com.luciano.llm;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.luciano.config.LlmProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 阿里云百炼(通义千问)文本生成服务。
 * API Key 来自配置 llm.api-key,只读取不落盘,避免密钥进入代码库。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final LlmProperties properties;

    public LlmService(LlmProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 llm.api-key,LLM 回复功能将不可用。请在 application-local.properties 中配置,或设置环境变量 LLM_API_KEY");
            return;
        }
        Constants.apiKey = apiKey;
        log.info("阿里云百炼初始化完成,模型 = {}", properties.getModel());
    }

    /**
     * 生成文本回复。
     *
     * @param userText 用户发送的文本
     * @return 大模型生成的回复;若未配置 API Key 返回提示语
     */
    public String chat(String userText) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "抱歉,我还没有配置大模型能力,请联系管理员配置 llm.api-key 后再试。";
        }
        try {
            GenerationParam param = GenerationParam.builder()
                    .model(properties.getModel())
                    .messages(List.of(
                            Message.builder().role(Role.SYSTEM.getValue())
                                    .content("你是一个友好、乐于助人的微信机器人助手,回答要简洁准确,使用中文。")
                                    .build(),
                            Message.builder().role(Role.USER.getValue())
                                    .content(userText)
                                    .build()))
                    .build();
            GenerationResult result = new Generation().call(param);
            String text = result.getOutput().getText();
            if (text == null || text.isBlank()) {
                log.warn("大模型返回为空,requestId = {}", result.getRequestId());
                return "抱歉,大模型没有返回内容,请稍后再试。";
            }
            return text;
        } catch (NoApiKeyException e) {
            log.error("未找到 API Key: {}", e.getMessage());
            return "大模型调用失败:未配置 API Key。";
        } catch (InputRequiredException e) {
            log.error("请求参数不合法: {}", e.getMessage());
            return "大模型调用失败:请求参数不合法。";
        } catch (Exception e) {
            log.error("调用阿里云百炼失败", e);
            return "大模型调用失败,请稍后再试。";
        }
    }
}
