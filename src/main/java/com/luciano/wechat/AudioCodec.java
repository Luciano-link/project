package com.luciano.wechat;

import io.github.kasukusakura.silkcodec.SilkCoder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 音频转码工具:
 * <ul>
 *   <li>{@link #mp3ToSilk}:把 DashScope TTS 输出的 mp3 转成微信语音所需的 SILK 字节(用于发语音);</li>
 *   <li>{@link #silkToWav}:把微信语音(SILK)解码成 WAV PCM(用于上传语音识别)。</li>
 * </ul>
 *
 * <p>TTS 链路:mp3 → (ffmpeg) PCM(s16le 单声道 24000) → (silk-codec) SILK。
 * 采样率统一 24000,必须与 {@code sendVoice} 传入的 sampleRate 一致。
 */
@Component
public class AudioCodec {

    private static final int SAMPLE_RATE = 24000;

    /**
     * 把 mp3 音频字节转成微信语音所需的 SILK 字节。
     */
    public byte[] mp3ToSilk(byte[] mp3) throws Exception {
        byte[] pcm = mp3ToPcm(mp3);
        return pcmToSilk(pcm);
    }

    /**
     * 把微信语音(SILK)解码成 WAV(PCM s16le 单声道),供语音识别(ASR)上传。
     *
     * @param sampleRate 语音原始采样率(来自消息元数据;缺失时用默认值,仅影响 WAV 头声明)
     */
    public byte[] silkToWav(byte[] silk, int sampleRate) throws Exception {
        byte[] pcm;
        try (InputStream in = new ByteArrayInputStream(silk);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 按 SILK 流内嵌的采样率解码为 s16le PCM
            SilkCoder.decode(in, out);
            pcm = out.toByteArray();
        }
        return pcmToWav(pcm, sampleRate > 0 ? sampleRate : SAMPLE_RATE);
    }

    /**
     * 给 PCM(s16le 单声道)补上标准 44 字节 RIFF/WAVE 头,得到 WAV 字节。
     */
    private byte[] pcmToWav(byte[] pcm, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int blockAlign = channels * bitsPerSample / 8;
        int byteRate = sampleRate * blockAlign;
        int dataSize = pcm.length;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataSize);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) bitsPerSample);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataSize);
        buf.put(pcm);
        return buf.array();
    }

    /**
     * 用 ffmpeg 把 mp3 转成 s16le 单声道 PCM(采样率 {@link #SAMPLE_RATE})。
     */
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
            // 先读合并输出(阻塞到进程结束),避免管道写满死锁
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

    /**
     * 用 silk-codec 把 PCM 编码成 SILK。
     */
    private byte[] pcmToSilk(byte[] pcm) throws Exception {
        try (InputStream in = new ByteArrayInputStream(pcm);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            SilkCoder.encode(in, out, SAMPLE_RATE);
            return out.toByteArray();
        }
    }
}
