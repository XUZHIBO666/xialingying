package com.demo.demo.Service.Resume;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Slf4j
@Service
public class ResumeFileParser {
    @PostConstruct
    public void init() {
        log.info("[简历] 解析服务初始化完成");
    }

    public String parse(byte[] fileBytes, String fileExtension) {
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("[简历解析] 文件内容为空");
            return null;
        }
        try {
            return switch (fileExtension.toLowerCase()) {
                case "pdf" -> parsePdf(fileBytes);
                case "docx" -> parseDocx(fileBytes);
                // .doc 老格式支持有限，建议让用户转成 docx 或 pdf
                default -> {
                    log.warn("[简历解析] 不支持的文件格式: {}", fileExtension);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("[简历解析] 解析失败 ext={}: {}", fileExtension, e.getMessage(), e);
            return null;
        }
    }
    private String parsePdf(byte[] fileBytes) throws IOException {
        try (var doc = Loader.loadPDF(fileBytes)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            log.info("[简历解析] PDF 解析成功，文本长度: {}", text.length());
            return text.trim();
        }
    }

    private String parseDocx(byte[] fileBytes) throws IOException {
        try (var is = new ByteArrayInputStream(fileBytes);
             var doc = new XWPFDocument(is)) {
            var sb = new StringBuilder();
            for (var para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
            // 也提取表格内容（简历经常用表格排版）
            for (var table : doc.getTables()) {
                for (var row : table.getRows()) {
                    for (var cell : row.getTableCells()) {
                        String text = cell.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append("  ");
                        }
                    }
                    sb.append("\n");
                }
            }
            log.info("[简历解析] DOCX 解析成功，文本长度: {}", sb.length());
            return sb.toString().trim();
        }
    }
}

