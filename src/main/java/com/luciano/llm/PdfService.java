package com.luciano.llm;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.io.font.PdfEncodings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/**
 * PDF 生成服务(基于 iText7)。
 * 把方案文本生成可打印的 PDF 文件字节,支持中文(加载系统中文字体)。
 */
@Component
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    /** 中文字体:Windows 微软雅黑(优先),失败时尝试宋体 */
    private static final String[] FONT_CANDIDATES = {
            "C:/Windows/Fonts/msyh.ttc,0",
            "C:/Windows/Fonts/simsun.ttc,0",
            "C:/Windows/Fonts/msyh.ttf,0"
    };

    /** 把标题 + 正文生成 PDF 字节,失败返回 null */
    public byte[] createTextPdf(String title, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);
            PdfFont font = loadChineseFont();
            if (font != null) {
                doc.setFont(font);
            }
            doc.add(new Paragraph(title).setBold().setFontSize(18));
            doc.add(new Paragraph("").setFontSize(6));
            for (String line : content.split("\n")) {
                doc.add(new Paragraph(line).setFontSize(10));
            }
            doc.close();
            log.info("PDF 生成成功: {} bytes", baos.size());
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("PDF 生成失败", e);
            return null;
        }
    }

    private PdfFont loadChineseFont() {
        for (String path : FONT_CANDIDATES) {
            try {
                return PdfFontFactory.createFont(path, PdfEncodings.IDENTITY_H);
            } catch (Exception ignored) {
                // 尝试下一个字体
            }
        }
        return null;
    }
}
