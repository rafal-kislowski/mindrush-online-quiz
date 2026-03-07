package pl.mindrush.backend.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.quiz.QuizLibraryPolicyProperties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Service
public class MediaStorageService {

    private final Path rootDir;
    private final QuizLibraryPolicyProperties policyProperties;

    public MediaStorageService(
            @Value("${app.media.dir:uploads}") String mediaDir,
            QuizLibraryPolicyProperties policyProperties
    ) {
        this.rootDir = Paths.get(mediaDir).toAbsolutePath().normalize();
        this.policyProperties = policyProperties;
    }

    public StoredMedia storeImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "File is required");
        }

        String contentType = (file.getContentType() == null ? "" : file.getContentType()).trim().toLowerCase(Locale.ROOT);
        long maxBytes = policyProperties.getMedia().getMaxUploadBytes();
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(BAD_REQUEST, "File is too large (max " + bytesLabel(maxBytes) + ")");
        }
        if (!policyProperties.getMedia().getAllowedMimeTypes().contains(contentType)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported image type");
        }

        String ext = extensionFrom(contentType);
        String filename = UUID.randomUUID() + ext;

        try {
            Files.createDirectories(rootDir);
            Path target = rootDir.resolve(filename).normalize();
            if (!target.startsWith(rootDir)) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid file path");
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to store file");
        }

        String urlPath = "/media/" + filename;
        return new StoredMedia(urlPath, contentType, file.getOriginalFilename());
    }

    private static String extensionFrom(String contentType) {
        if ("image/png".equals(contentType)) return ".png";
        if ("image/jpeg".equals(contentType)) return ".jpg";
        if ("image/gif".equals(contentType)) return ".gif";
        if ("image/webp".equals(contentType)) return ".webp";
        throw new ResponseStatusException(BAD_REQUEST, "Unsupported image type");
    }

    private static String bytesLabel(long bytes) {
        long mb = Math.max(1L, Math.round((double) bytes / (1024d * 1024d)));
        return mb + "MB";
    }

    public record StoredMedia(String url, String contentType, String originalName) {}

    public boolean deleteIfStoredUrl(String url) {
        if (url == null) return false;
        String raw = url.trim();
        if (raw.isBlank()) return false;

        String path = raw;
        try {
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                path = URI.create(raw).getPath();
            }
        } catch (Exception ignored) {
            path = raw;
        }

        if (path == null) return false;
        if (!path.startsWith("/media/")) return false;

        String name = path.substring("/media/".length());
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) return false;

        try {
            Path target = rootDir.resolve(name).normalize();
            if (!target.startsWith(rootDir)) return false;
            return Files.deleteIfExists(target);
        } catch (IOException ignored) {
            return false;
        }
    }
}
