package pl.mindrush.backend.quiz;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizGenerationSourceFileServiceTest {

    private final QuizGenerationSourceFileService service = new QuizGenerationSourceFileService();

    @Test
    void extractSourceMaterialReturnsNullForNullOrEmptyInput() {
        assertNull(service.extractSourceMaterial(null));
        assertNull(service.extractSourceMaterial(List.of()));
    }

    @Test
    void extractSourceMaterialRejectsTooManyFiles() {
        List<MockMultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            files.add(txt("f" + i + ".txt", "hello"));
        }

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.extractSourceMaterial(new ArrayList<>(files))
        );

        assertEquals(400, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("Too many source files"));
    }

    @Test
    void extractSourceMaterialRejectsSingleFileAbovePerFileLimit() {
        byte[] tooLarge = new byte[(10 * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "too-large.txt",
                "text/plain",
                tooLarge
        );

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.extractSourceMaterial(List.of(file))
        );

        assertEquals(400, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("Maximum per file is 10MB"));
    }

    @Test
    void extractSourceMaterialRejectsFilesAboveTotalLimit() {
        byte[] eightMb = new byte[8 * 1024 * 1024];
        List<MockMultipartFile> files = List.of(
                new MockMultipartFile("files", "a.txt", "text/plain", eightMb),
                new MockMultipartFile("files", "b.txt", "text/plain", eightMb),
                new MockMultipartFile("files", "c.txt", "text/plain", eightMb)
        );

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.extractSourceMaterial(new ArrayList<>(files))
        );

        assertEquals(400, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("Combined size must be <= 20MB"));
    }

    @Test
    void extractSourceMaterialAcceptsValidTxtFiles() {
        MockMultipartFile file = txt("notes.txt", "Java basics and OOP");

        String result = service.extractSourceMaterial(List.of(file));

        assertNotNull(result);
        assertTrue(result.contains("Source file: notes.txt"));
        assertTrue(result.contains("Java basics and OOP"));
    }

    private static MockMultipartFile txt(String fileName, String content) {
        return new MockMultipartFile(
                "files",
                fileName,
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
