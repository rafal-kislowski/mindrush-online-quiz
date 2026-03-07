package pl.mindrush.backend.media;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/library/media")
public class LibraryMediaController {

    private final MediaStorageService storageService;

    public LibraryMediaController(MediaStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<MediaDto> upload(@RequestParam("file") MultipartFile file) {
        MediaStorageService.StoredMedia stored = storageService.storeImage(file);
        return ResponseEntity.ok(new MediaDto(stored.url()));
    }

    public record MediaDto(String url) {}
}

