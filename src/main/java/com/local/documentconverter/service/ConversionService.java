package com.local.documentconverter.service;

import com.local.documentconverter.model.ConversionResult;
import com.local.documentconverter.model.FormatOption;
import com.local.documentconverter.model.HealthStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConversionService {
    private final Path uploadDir;
    private final Path outputDir;
    private final Map<String, FormatOption> formats = new LinkedHashMap<>();
    private static final Set<String> OFFICE = Set.of("doc", "docx", "odt", "rtf", "txt", "html", "ppt", "pptx", "odp");
    private static final Set<String> PANDOC = Set.of("md");
    private static final Set<String> PDF_TARGETS = Set.of("txt", "html", "docx");

    public ConversionService(@Value("${app.storage.root:storage}") String root) throws IOException {
        Path storageRoot = Paths.get(root).toAbsolutePath().normalize();
        uploadDir = storageRoot.resolve("uploads");
        outputDir = storageRoot.resolve("outputs");
        Files.createDirectories(uploadDir);
        Files.createDirectories(outputDir);
        initFormats();
    }

    public Map<String, FormatOption> getSupportedFormats() {
        return formats;
    }

    public HealthStatus healthStatus() {
        return new HealthStatus(isAvailable(resolve("soffice")), isAvailable(resolve("pandoc")));
    }

    public Map<String, Object> healthReport() {
        HealthStatus status = healthStatus();
        List<String> missing = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (!status.isSoffice()) {
            missing.add("LibreOffice");
            suggestions.add("未检测到 LibreOffice，Word / PPT / ODT / HTML / TXT 相关转换将不可用。请安装 LibreOffice。");
        }
        if (!status.isPandoc()) {
            missing.add("Pandoc");
            if (status.isSoffice()) {
                suggestions.add("未检测到 Pandoc，Markdown 转换将回退到 LibreOffice。部分格式可能受限，建议安装 Pandoc。");
            } else {
                suggestions.add("未检测到 Pandoc，且 LibreOffice 也不可用，Markdown 相关转换将不可用。请安装 Pandoc 或 LibreOffice。");
            }
        }
        if (missing.isEmpty()) {
            suggestions.add("环境正常，可使用全部当前支持的转换能力。");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("soffice", status.isSoffice());
        result.put("pandoc", status.isPandoc());
        result.put("missing", missing);
        result.put("suggestions", suggestions);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    public List<Map<String, Object>> listHistory() throws IOException {
        if (!Files.exists(outputDir)) {
            return List.of();
        }

        try (var stream = Files.list(outputDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(this::lastModifiedSafe).reversed())
                    .map(this::historyItem)
                    .toList();
        }
    }

    public List<Map<String, Object>> convertBatch(MultipartFile[] files, String target) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请先选择至少一个文件。");
        }
        String[] targets = new String[files.length];
        Arrays.fill(targets, target);
        return convertBatch(files, targets);
    }

    public List<Map<String, Object>> convertBatch(MultipartFile[] files, String[] targets) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请先选择至少一个文件。");
        }
        if (targets == null || targets.length != files.length) {
            throw new IllegalArgumentException("每个文件都必须指定一个目标格式。");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            String target = targets[i];
            try {
                ConversionResult result = convert(file, target);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("success", true);
                item.put("sourceFormat", ext(Objects.requireNonNullElse(file.getOriginalFilename(), "")));
                item.put("targetFormat", target.toLowerCase(Locale.ROOT));
                item.put("method", result.getMethod());
                item.put("outputFileName", result.getOutputFileName());
                item.put("downloadUrl", "/downloads/" + result.getOutputFileName());
                item.put("previewUrl", "/previews/" + result.getOutputFileName());
                item.put("previewable", isPreviewable(result.getOutputFileName()));
                item.put("message", result.getMessage());
                item.put("originalName", file.getOriginalFilename());
                results.add(item);
            } catch (Exception ex) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("success", false);
                item.put("originalName", file.getOriginalFilename());
                item.put("targetFormat", target.toLowerCase(Locale.ROOT));
                item.put("message", cleanMessage(ex));
                results.add(item);
            }
        }
        return results;
    }

    public ConversionResult convert(MultipartFile file, String target) throws IOException, InterruptedException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请先上传文件。");
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        String source = ext(name);
        String to = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        if (source.isBlank()) throw new IllegalArgumentException("无法识别源文件格式。请保留文件扩展名。");
        if (!formats.containsKey(source)) throw new IllegalArgumentException("暂不支持 ." + source + " 文件。");
        if (!formats.get(source).getOutputs().contains(to)) throw new IllegalArgumentException("当前不支持 " + source + " -> " + to + " 转换。");
        validateEnvironmentFor(source, to);

        String base = safeBase(base(name));
        Path input = uploadDir.resolve(Instant.now().toEpochMilli() + "-" + base + "." + source);
        try {
            Files.copy(file.getInputStream(), input, StandardCopyOption.REPLACE_EXISTING);
            return convertFile(input, source, to, base);
        } finally {
            cleanup(input);
        }
    }

    public Path getOutputFile(String fileName) {
        Path normalized = outputDir.resolve(fileName).normalize();
        if (!normalized.startsWith(outputDir)) {
            throw new IllegalArgumentException("非法文件路径。");
        }
        return normalized;
    }

    private ConversionResult convertFile(Path input, String source, String target, String base) throws IOException, InterruptedException {
        if ("pdf".equals(source) && PDF_TARGETS.contains(target)) return convertPdf(input, target, base);
        if (PANDOC.contains(source)) {
            try {
                return convertWithPandoc(input, target, base);
            } catch (RuntimeException ex) {
                if (!OFFICE.contains(target)) throw ex;
                // Pandoc 未安装或执行失败，回退到 LibreOffice
            }
        }
        if (OFFICE.contains(source) || PANDOC.contains(source)) return convertWithOffice(input, target, base, source);
        if ("pdf".equals(source)) throw new IllegalArgumentException("PDF 当前只支持提取为 txt/html/docx。若需尽量保留版式，请接入 OCR 或专业 PDF SDK。");
        throw new IllegalArgumentException("没有可用的转换引擎处理 " + source + " -> " + target + "。");
    }

    private ConversionResult convertPdf(Path input, String target, String base) throws IOException, InterruptedException {
        String text;
        try (PDDocument doc = Loader.loadPDF(input.toFile())) {
            text = normalize(new PDFTextStripper().getText(doc));
        }
        if (text.isBlank()) throw new IllegalArgumentException("PDF 中未提取到可用文本。该文件可能是扫描件，需先进行 OCR。");
        if ("txt".equals(target)) {
            Path out = outputDir.resolve(ts(base, "txt"));
            Files.writeString(out, text, StandardCharsets.UTF_8);
            return new ConversionResult(out.getFileName().toString(), "pdf-text-extraction", "已从 PDF 提取文本并导出为 TXT。");
        }
        String html = htmlWrap(text);
        if ("html".equals(target)) {
            Path out = outputDir.resolve(ts(base, "html"));
            Files.writeString(out, html, StandardCharsets.UTF_8);
            return new ConversionResult(out.getFileName().toString(), "pdf-text-extraction", "已从 PDF 提取文本并导出为 HTML。");
        }
        Path temp = Files.createTempFile("pdf-to-docx-", ".html");
        Files.writeString(temp, html, StandardCharsets.UTF_8);
        try {
            ConversionResult r = convertWithOffice(temp, "docx", base, "html");
            return new ConversionResult(r.getOutputFileName(), "pdf-text-extraction + libreoffice", "已从 PDF 提取文本并导出为 DOCX。");
        } finally {
            cleanup(temp);
        }
    }

    private ConversionResult convertWithPandoc(Path input, String target, String base) throws IOException, InterruptedException {
        Path out = outputDir.resolve(ts(base, target));
        run(List.of(resolve("pandoc"), input.toString(), "-o", out.toString()));
        if (!Files.exists(out)) throw new IllegalStateException("Pandoc 未生成输出文件。");
        return new ConversionResult(out.getFileName().toString(), "pandoc", "已通过 Pandoc 完成 " + ext(input.getFileName().toString()) + " -> " + target + " 转换。");
    }

    private ConversionResult convertWithOffice(Path input, String target, String base, String source) throws IOException, InterruptedException {
        Path tempOutDir = Files.createTempDirectory("soffice-out-");
        try {
            run(List.of(resolve("soffice"), "--headless", "--convert-to", target, "--outdir", tempOutDir.toString(), input.toString()));
            Path out = tempOutDir.resolve(base(input.getFileName().toString()) + "." + target);
            if (!Files.exists(out)) {
                try (var stream = Files.list(tempOutDir)) {
                    out = stream.filter(Files::isRegularFile)
                            .filter(p -> ext(p.getFileName().toString()).equals(target))
                            .findFirst().orElse(null);
                }
            }
            if (out == null || !Files.exists(out)) throw new IllegalStateException("LibreOffice 未生成 " + source + " -> " + target + " 输出文件。请确认系统已正确安装 LibreOffice。");
            Path finalOut = outputDir.resolve(ts(base, target));
            Files.move(out, finalOut, StandardCopyOption.REPLACE_EXISTING);
            return new ConversionResult(finalOut.getFileName().toString(), "libreoffice", "已通过 LibreOffice 完成 " + source + " -> " + target + " 转换。");
        } finally {
            try (var stream = Files.list(tempOutDir)) {
                for (Path p : stream.toList()) Files.deleteIfExists(p);
            } catch (IOException ignored) {}
            Files.deleteIfExists(tempOutDir);
        }
    }

    private void run(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        Charset processCharset = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? Charset.defaultCharset()
                : StandardCharsets.UTF_8;
        String output;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), processCharset))) {
            output = r.lines().collect(Collectors.joining(System.lineSeparator()));
        }
        if (p.waitFor() != 0) throw new IllegalStateException(output.isBlank() ? "命令执行失败" : output.trim());
    }

    private boolean isAvailable(String command) {
        try {
            Path candidate = Paths.get(command);
            if (candidate.isAbsolute()) {
                return Files.exists(candidate);
            }
            return findOnPath(command).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<Path> findOnPath(String command) {
        String pathValue = Optional.ofNullable(System.getenv("PATH")).orElse("");
        if (pathValue.isBlank()) {
            return Optional.empty();
        }
        String[] dirs = pathValue.split(java.io.File.pathSeparator);
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path hit = Paths.get(dir.trim(), command);
            if (Files.exists(hit)) {
                return Optional.of(hit);
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win") && !command.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                Path exeHit = Paths.get(dir.trim(), command + ".exe");
                if (Files.exists(exeHit)) {
                    return Optional.of(exeHit);
                }
            }
        }
        return Optional.empty();
    }

    public boolean deleteHistoryFile(String fileName) throws IOException {
        Path file = getOutputFile(fileName);
        if (!Files.exists(file)) {
            return true;
        }
        return Files.deleteIfExists(file);
    }

    public int clearHistory() throws IOException {
        if (!Files.exists(outputDir)) {
            return 0;
        }
        int count = 0;
        try (var stream = Files.list(outputDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (Files.deleteIfExists(file)) {
                    count++;
                }
            }
        }
        return count;
    }

    private String resolve(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) return command;
        if ("soffice".equals(command)) return pick(List.of("C:\\Program Files\\LibreOffice\\program\\soffice.exe", "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe", "soffice.exe"));
        if ("pandoc".equals(command)) {
            String local = Optional.ofNullable(System.getenv("LOCALAPPDATA")).orElse("");
            return pick(List.of("C:\\Program Files\\Pandoc\\pandoc.exe", Paths.get(local, "Pandoc", "pandoc.exe").toString(), "pandoc.exe"));
        }
        return command;
    }

    private String pick(List<String> candidates) {
        for (String c : candidates) if (c.contains("\\") && Files.exists(Paths.get(c))) return c;
        return candidates.get(candidates.size() - 1);
    }

    private void initFormats() {
        formats.put("pdf", new FormatOption("PDF", List.of("txt", "docx", "html")));
        formats.put("doc", new FormatOption("Word 97-2003", List.of("docx", "pdf", "txt", "html", "odt")));
        formats.put("docx", new FormatOption("Word DOCX", List.of("pdf", "txt", "html", "odt", "doc")));
        formats.put("odt", new FormatOption("OpenDocument Text", List.of("pdf", "txt", "html", "docx", "doc")));
        formats.put("rtf", new FormatOption("Rich Text Format", List.of("pdf", "txt", "html", "docx", "odt")));
        formats.put("txt", new FormatOption("Text", List.of("pdf", "html", "docx", "odt", "rtf")));
        formats.put("md", new FormatOption("Markdown", List.of("html", "pdf", "docx", "odt", "txt", "rtf")));
        formats.put("html", new FormatOption("HTML", List.of("pdf", "docx", "odt", "txt")));
        formats.put("ppt", new FormatOption("PowerPoint 97-2003", List.of("pptx", "pdf", "odp")));
        formats.put("pptx", new FormatOption("PowerPoint PPTX", List.of("pdf", "odp", "ppt")));
        formats.put("odp", new FormatOption("OpenDocument Presentation", List.of("pdf", "pptx", "ppt")));
    }

    private Map<String, Object> historyItem(Path file) {
        Map<String, Object> item = new LinkedHashMap<>();
        String fileName = file.getFileName().toString();
        item.put("fileName", fileName);
        item.put("downloadUrl", "/downloads/" + fileName);
        item.put("previewUrl", "/previews/" + fileName);
        item.put("previewable", isPreviewable(fileName));
        item.put("format", ext(fileName));
        try {
            item.put("size", Files.size(file));
        } catch (IOException e) {
            item.put("size", 0L);
        }
        item.put("lastModified", lastModifiedSafe(file));
        return item;
    }

    private Instant lastModifiedSafe(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private String cleanMessage(Exception ex) {
        Throwable cause = ex.getCause();
        String msg = cause != null && cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return "转换失败。";
        }
        return msg.replaceAll("\\s+", " ").trim();
    }

    private void validateEnvironmentFor(String source, String target) {
        HealthStatus status = healthStatus();
        if ("md".equals(source) && !status.isPandoc() && !status.isSoffice()) {
            throw new IllegalStateException("Markdown 转换需要 Pandoc 或 LibreOffice，请至少安装其中一个。");
        }
        if (OFFICE.contains(source) && !status.isSoffice()) {
            throw new IllegalStateException("当前转换依赖 LibreOffice，请先安装 LibreOffice。");
        }
        if (!"pdf".equals(source) && OFFICE.contains(target) && !status.isSoffice()) {
            throw new IllegalStateException("目标格式需要 LibreOffice，请先安装 LibreOffice。");
        }
        if ("pdf".equals(source) && "docx".equals(target) && !status.isSoffice()) {
            throw new IllegalStateException("PDF 转 DOCX 需要 LibreOffice，请先安装 LibreOffice。");
        }
    }

    private boolean isPreviewable(String fileName) {
        String format = ext(fileName);
        return "pdf".equals(format) || "txt".equals(format) || "html".equals(format);
    }

    private String ext(String n) { int i = n.lastIndexOf('.'); return i >= 0 ? n.substring(i + 1).toLowerCase(Locale.ROOT) : ""; }
    private String base(String n) { int i = n.lastIndexOf('.'); return i >= 0 ? n.substring(0, i) : n; }
    private String ts(String base, String ext) { return Instant.now().toEpochMilli() + "-" + base + "." + ext; }
    private String safeBase(String v) { String s = v.replaceAll("[^a-zA-Z0-9-_\\u4e00-\\u9fa5]+", "-").replaceAll("^-+|-+$", ""); return s.isBlank() ? "converted" : s.substring(0, Math.min(60, s.length())); }
    private String normalize(String t) { return t.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").trim() + System.lineSeparator(); }
    private String htmlWrap(String t) { return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/><title>Converted Document</title><style>body{font-family:'Segoe UI',Arial,sans-serif;max-width:960px;margin:40px auto;padding:0 24px;line-height:1.75;color:#111827;white-space:pre-wrap;}</style></head><body>" + esc(t) + "</body></html>"; }
    private String esc(String v) { return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private void cleanup(Path p) { try { if (p != null) Files.deleteIfExists(p); } catch (IOException ignored) {} }
}
