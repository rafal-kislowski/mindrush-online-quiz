package pl.mindrush.backend.casual;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.mindrush.backend.casual.dto.CasualThreeLivesBestDto;

@RestController
@RequestMapping("/api/casual")
public class CasualController {

    private final CasualThreeLivesRecordService recordService;

    public CasualController(CasualThreeLivesRecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping("/three-lives/best")
    public ResponseEntity<CasualThreeLivesBestDto> threeLivesBest(HttpServletRequest request) {
        return ResponseEntity.ok(recordService.findBestForRequest(request).orElse(null));
    }
}

