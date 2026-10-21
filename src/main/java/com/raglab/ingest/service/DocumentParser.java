package com.raglab.ingest.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 文档解析：把各种格式统一成纯文本。
 *
 * 每一条分支都会在日志里交代清楚用了哪个解析器、产出多少字符，
 * 因为「解析出 0 字符」是 RAG 最常见的静默失败 —— 不报错，但后面全空。
 */
@Component
public class DocumentParser {

    /**
     * @param text       正文
     * @param format     识别出的格式
     * @param parser     实际使用的解析器
     * @param note       额外说明（页数、编码猜测结果等）
     */
    public record ParseResult(String text, String format, String parser, String note) {
    }

    public ParseResult parse(String fileName, byte[] bytes) throws IOException {
        String ext = extensionOf(fileName);
        return switch (ext) {
            case "pdf" -> parsePdf(bytes);
            case "docx" -> parseDocx(bytes);
            case "doc" -> throw new IllegalArgumentException(
                    "不支持旧版 .doc（二进制格式），请另存为 .docx 再上传");
            case "txt", "md", "markdown", "text", "" -> parsePlainText(bytes, ext);
            default -> throw new IllegalArgumentException(
                    "暂不支持的文件类型 ." + ext + "，当前支持：pdf / docx / txt / md");
        };
    }

    private ParseResult parsePdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return new ParseResult(text, "pdf", "Apache PDFBox " + doc.getClass().getPackage().getImplementationVersion(),
                    "共 " + doc.getNumberOfPages() + " 页");
        }
    }

    private ParseResult parseDocx(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            int paragraphs = 0;
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append('\n');
                    paragraphs++;
                }
            }
            // 表格单独抽出来，单元格之间用 | 分隔，尽量保住结构
            int tables = doc.getTables().size();
            for (XWPFTable table : doc.getTables()) {
                sb.append('\n');
                for (XWPFTableRow row : table.getRows()) {
                    List<XWPFTableCell> cells = row.getTableCells();
                    StringBuilder line = new StringBuilder();
                    for (int i = 0; i < cells.size(); i++) {
                        if (i > 0) {
                            line.append(" | ");
                        }
                        line.append(cells.get(i).getText().strip());
                    }
                    sb.append(line).append('\n');
                }
            }
            return new ParseResult(sb.toString(), "docx", "Apache POI XWPF",
                    "段落 " + paragraphs + " 个，表格 " + tables + " 张");
        }
    }

    private ParseResult parsePlainText(byte[] bytes, String ext) {
        // 先按 UTF-8 解，出现大量替换字符说明是 GBK 编码（Windows 记事本常见）
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        long bad = utf8.chars().filter(c -> c == 0xFFFD).count();
        if (bad > 0 && bad * 100 > utf8.length()) {
            String gbk = new String(bytes, Charset.forName("GBK"));
            return new ParseResult(gbk, ext.isEmpty() ? "txt" : ext, "纯文本读取",
                    "UTF-8 解码出现 " + bad + " 个乱码字符，已回退 GBK");
        }
        String text = utf8;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return new ParseResult(text, ext.isEmpty() ? "txt" : ext, "纯文本读取", "UTF-8");
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
