package pl.mindrush.backend.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
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

    private static final long MAX_BYTES = 5L * 1024L * 1024L;

    private final Path rootDir;

    public MediaStorageService(@Value("${app.media.dir:uploads}") String mediaDir) {
        this.rootDir = Paths.get(mediaDir).toAbsolutePath().normalize();
    }

    public StoredMedia storeImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "File is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(BAD_REQUEST, "File is too large (max 5MB)");
        }

        String contentType = (file.getContentType() == null ? "" : file.getContentType()).toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("image/")) {
            throw new ResponseStatusException(BAD_REQUEST, "Only image uploads are supported");
        }

        String ext = extensionFrom(file.getOriginalFilename(), contentType);
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

    private static String extensionFrom(String originalName, String contentType) {
        String ext = "";
        if (originalName != null) {
            int idx = originalName.lastIndexOf('.');
            if (idx >= 0 && idx < originalName.length() - 1) {
                String candidate = originalName.substring(idx + 1).toLowerCase(Locale.ROOT);
                if (candidate.matches("[a-z0-9]{1,6}")) {
                    ext = "." + candidate;
                }
            }
        }

        if (!ext.isBlank()) return ext;

        if (MediaType.IMAGE_PNG_VALUE.equals(contentType)) return ".png";
        if (MediaType.IMAGE_JPEG_VALUE.equals(contentType)) return ".jpg";
        if (MediaType.IMAGE_GIF_VALUE.equals(contentType)) return ".gif";
        if ("image/webp".equals(contentType)) return ".webp";
        if ("image/svg+xml".equals(contentType)) return ".svg";
        return "";
    }

    public record StoredMedia(String url, String contentType, String originalName) {}
}

