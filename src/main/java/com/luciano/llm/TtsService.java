package com.luciano.llm;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.utils.Constants;
import com.luciano.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;

/**
 * 语音合成服务(TTS)。
 * 使用阿里云百炼 cosyvoice 系列模型,将文本合成为 MP3 音频字节。
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final LlmProperties properties;

    public TtsService(LlmProperties properties) {
        this.properties = properties;
    }

    /**
     * 将文本合成为 MP3 音频。
     *
     * @param text 要朗读的文本
     * @return MP3 音频字节;失败或未配置 Key 时返回 null
     */
    public byte[] synthesize(String text) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 llm.api-key,语音合成不可用");
            return null;
        }
        try {
            Constants.apiKey = apiKey;
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .model(properties.getTtsModel())
                    .voice(properties.getTtsVoice())
                    .format(SpeechSynthesisAudioFormat.MP3_24000HZ_MONO_256KBPS)
                    .build();
            SpeechSynthesizer synthesizer = new SpeechSynthesizer();
            synthesizer.updateParamAndCallback(param, null);
            ByteBuffer audio = synthesizer.call(text);
            if (audio == null) {
                log.warn("语音合成为空,text = {}", text);
                return null;
            }
            byte[] bytes = new byte[audio.remaining()];
            audio.get(bytes);
            log.info("语音合成成功,文本长度 = {}, 音频大小 = {} bytes", text.length(), bytes.length);
            return bytes;
        } catch (Exception e) {
            log.error("语音合成失败", e);
            return null;
        }
    }
}
