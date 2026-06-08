package com.local.documentconverter.controller;

import com.local.documentconverter.model.ConversionResult;
import com.local.documentconverter.service.ConversionService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class ConverterController {
    private final ConversionService conversionService;

    public ConverterController(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/api/formats")
    @ResponseBody
    public Map<String, ?> formats() {
        return conversionService.getSupportedFormats();
    }

    @GetMapping("/api/health")
    @ResponseBody
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("capabilities", conversionService.healthReport());
        return result;
    }

    @PostMapping(value = "/api/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public Map<String, Object> convert(@RequestParam("file") MultipartFile file,
                                       @RequestParam("targetFormat") String targetFormat) throws Exception {
        ConversionResult result = conversionService.convert(file, targetFormat);
        String source = ext(file.getOriginalFilename());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("sourceFormat", source);
        body.put("targetFormat", targetFormat.toLowerCase());
        body.put("method", result.getMethod());
        body.put("downloadUrl", "/downloads/" + result.getOutputFileName());
        body.put("outputFileName", result.getOutputFileName());
        body.put("message", result.getMessage());
        return body;
    }

    private static final int MAX_BATCH_FILES = 20;

    @PostMapping(value = "/api/convert/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public Map<String, Object> convertBatch(@RequestParam("files") MultipartFile[] files,
                                            @RequestParam("targetFormats") String[] targetFormats) {
        if (files.length > MAX_BATCH_FILES) {
            throw new IllegalArgumentException("单次最多转换 " + MAX_BATCH_FILES + " 个文件。");
        }
        var items = conversionService.convertBatch(files, targetFormats);
        long successCount = items.stream().filter(i -> Boolean.TRUE.equals(i.get("success"))).count();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", successCount > 0);
        body.put("total", items.size());
        body.put("successCount", successCount);
        body.put("failedCount", items.size() - successCount);
        body.put("items", items);
        return body;
    }

    @GetMapping("/api/history")
    @ResponseBody
    public Map<String, Object> history() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", conversionService.listHistory());
        return body;
    }

    @DeleteMapping("/api/history/{filename:.+}")
    @ResponseBody
    public Map<String, Object> deleteHistory(@PathVariable String filename) throws Exception {
        boolean deleted = conversionService.deleteHistoryFile(filename);
        return Map.of("success", deleted);
    }

    @DeleteMapping("/api/history")
    @ResponseBody
    public Map<String, Object> clearHistory() throws Exception {
        int deletedCount = conversionService.clearHistory();
        return Map.of("success", true, "deletedCount", deletedCount);
    }

    @GetMapping("/downloads/{filename:.+}")
    public ResponseEntity<Resource> download(@PathVariable String filename) throws MalformedURLException {
        Path file = conversionService.getOutputFile(filename);
        Resource resource = new UrlResource(file.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = detectContentType(file);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    @GetMapping("/previews/{filename:.+}")
    public ResponseEntity<Resource> preview(@PathVariable String filename) throws MalformedURLException {
        Path file = conversionService.getOutputFile(filename);
        Resource resource = new UrlResource(file.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = detectContentType(file);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private String detectContentType(Path file) {
        String ext = ext(file.getFileName().toString());
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc" -> "application/msword";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "txt", "md", "text" -> "text/plain; charset=UTF-8";
            case "html", "htm" -> "text/html; charset=UTF-8";
            case "odt" -> "application/vnd.oasis.opendocument.text";
            case "odp" -> "application/vnd.oasis.opendocument.presentation";
            case "rtf" -> "application/rtf; charset=UTF-8";
            case "xml" -> "application/xml; charset=UTF-8";
            default -> "application/octet-stream";
        };
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", messageOf(ex)));
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleServerError(Exception ex) {
        return ResponseEntity.internalServerError().body(Map.of("error", messageOf(ex)));
    }

    private String ext(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        return i >= 0 ? filename.substring(i + 1).toLowerCase() : "";
    }

    private String messageOf(Exception ex) {
        Throwable cause = ex.getCause();
        String msg = cause != null && cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
        return msg == null || msg.isBlank() ? "转换失败。" : msg.replaceAll("\\s+", " ").trim();
    }
}
