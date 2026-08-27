package com.luciano.wechat;

import io.github.kasukusakura.silkcodec.SilkCoder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 音频转码工具(从 LiuHaoran 分支整合)。
 * 把 DashScope TTS 输出的 mp3 转成微信语音所需的 SILK 字节,用于发送真正的语音消息。
 * 链路:mp3 →(ffmpeg) PCM(s16le 单声道 24000)→(silk-codec) SILK。
 * 依赖外部 ffmpeg 与 silk-codec 库,采样率统一 24000。
 */
@Component
public class AudioCodec {

    private static final int SAMPLE_RATE = 24000;

    /** 把 mp3 音频字节转成微信语音所需的 SILK 字节 */
    public byte[] mp3ToSilk(byte[] mp3) throws Exception {
        byte[] pcm = mp3ToPcm(mp3);
        return pcmToSilk(pcm);
    }

    /** 用 ffmpeg 把 mp3 转成 s16le 单声道 PCM(采样率 24000) */
    private byte[] mp3ToPcm(byte[] mp3) throws Exception {
        Path inFile = Files.createTempFile("tts", ".mp3");
        Path outFile = Files.createTempFile("tts", ".pcm");
        try {
            Files.write(inFile, mp3);
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", inFile.toString(),
                    "-f", "s16le", "-acodec", "pcm_s16le",
                    "-ar", String.valueOf(SAMPLE_RATE), "-ac", "1",
                    outFile.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("ffmpeg 转码失败(exit=" + exit + "): " + output);
            }
            return Files.readAllBytes(outFile);
        } finally {
            Files.deleteIfExists(inFile);
            Files.deleteIfExists(outFile);
        }
    }

    /** 用 silk-codec 把 PCM 编码为 SILK */
    private byte[] pcmToSilk(byte[] pcm) throws Exception {
        try (InputStream in = new ByteArrayInputStream(pcm);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            SilkCoder.encode(in, out, SAMPLE_RATE);
            return out.toByteArray();
        }
    }
}
