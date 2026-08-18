package com.luciano.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 阿里云百炼 API Key */
    private String apiKey;

    /** 使用的通义千问模型,如 qwen-plus */
    private String model = "qwen-plus";

    /** 语音合成模型,如 cosyvoice-v2 */
    private String ttsModel = "cosyvoice-v2";

    /** 语音合成音色,如 longxiaochun */
    private String ttsVoice = "longxiaochun";

    /** 文生图模型,如 wanx2.1-t2i-turbo */
    private String imageModel = "wanx2.1-t2i-turbo";

    /** 多模态识图模型,如 qwen-vl-max */
    private String visionModel = "qwen-vl-max";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTtsModel() {
        return ttsModel;
    }

    public void setTtsModel(String ttsModel) {
        this.ttsModel = ttsModel;
    }

    public String getTtsVoice() {
        return ttsVoice;
    }

    public void setTtsVoice(String ttsVoice) {
        this.ttsVoice = ttsVoice;
    }

    public String getImageModel() {
        return imageModel;
    }

    public void setImageModel(String imageModel) {
        this.imageModel = imageModel;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }
}
