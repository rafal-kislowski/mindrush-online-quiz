package pl.mindrush.backend.quiz;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class QuizGenerationSourceFileService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "pdf", "doc", "docx");
    private static final int MAX_FILE_COUNT = 10;
    private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_TOTAL_CHARS = 60_000;
    private static final int MAX_CHARS_PER_FILE = 20_000;

    public String extractSourceMaterial(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }

        List<MultipartFile> nonEmptyFiles = files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        if (nonEmptyFiles.isEmpty()) {
            return null;
        }

        if (nonEmptyFiles.size() > MAX_FILE_COUNT) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Too many source files. Maximum is " + MAX_FILE_COUNT
            );
        }

        long totalBytes = 0L;
        StringBuilder merged = new StringBuilder(Math.min(MAX_TOTAL_CHARS, 8_000));
        for (int i = 0; i < nonEmptyFiles.size(); i++) {
            MultipartFile file = nonEmptyFiles.get(i);
            long fileSize = Math.max(0L, file.getSize());
            if (fileSize > MAX_FILE_BYTES) {
                throw new ResponseStatusException(
                        BAD_REQUEST,
                        "Source file is too large. Maximum per file is " + (MAX_FILE_BYTES / (1024 * 1024)) + "MB"
                );
            }
            totalBytes += fileSize;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new ResponseStatusException(
                        BAD_REQUEST,
                        "Source files are too large. Combined size must be <= " + (MAX_TOTAL_BYTES / (1024 * 1024)) + "MB"
                );
            }

            String safeName = normalizedFileName(file.getOriginalFilename(), i + 1);
            String extension = extensionOf(safeName);
            if (extension == null || !SUPPORTED_EXTENSIONS.contains(extension)) {
                throw new ResponseStatusException(
                        BAD_REQUEST,
                        "Unsupported source file type for " + safeName + ". Allowed: .txt, .pdf, .doc, .docx"
                );
            }

            String extracted = extractText(file, extension, safeName);
            String normalized = normalizeText(extracted);
            if (normalized == null) {
                continue;
            }

            if (normalized.length() > MAX_CHARS_PER_FILE) {
                normalized = normalized.substring(0, MAX_CHARS_PER_FILE).trim();
            }

            if (normalized.isBlank()) {
                continue;
            }

            if (merged.length() > 0) {
                merged.append("\n\n");
            }
            merged.append("Source file: ").append(safeName).append('\n').append(normalized);

            if (merged.length() >= MAX_TOTAL_CHARS) {
                merged.setLength(MAX_TOTAL_CHARS);
                break;
            }
        }

        String result = normalizeText(merged.toString());
        if (result == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Could not extract any readable text from source files");
        }

        return result;
    }

    private String extractText(MultipartFile file, String extension, String safeName) {
        try {
            return switch (extension) {
                case "txt" -> extractTextFromTxt(file);
                case "pdf" -> extractTextFromPdf(file);
                case "docx" -> extractTextFromDocx(file);
                case "doc" -> extractTextFromDoc(file);
                default -> throw new ResponseStatusException(BAD_REQUEST, "Unsupported source file: " + safeName);
            };
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Failed to read source file: " + safeName);
        }
    }

    private String extractTextFromTxt(MultipartFile file) throws IOException {
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream(); PDDocument document = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String extractTextFromDocx(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream();
             XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractTextFromDoc(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream();
             HWPFDocument document = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static String normalizedFileName(String originalName, int index) {
        String trimmed = trimToNull(originalName);
        if (trimmed == null) {
            return "source-" + index + ".txt";
        }
        return trimmed.replace('\\', '/');
    }

    private static String extensionOf(String fileName) {
        String candidate = trimToNull(fileName);
        if (candidate == null) return null;
        int slashIdx = candidate.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < candidate.length() - 1) {
            candidate = candidate.substring(slashIdx + 1);
        }
        int dotIdx = candidate.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx >= candidate.length() - 1) return null;
        return candidate.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(String value) {
        String text = trimToNull(value);
        if (text == null) return null;
        String normalized = text
                .replace('\u0000', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
