package pl.mindrush.backend.demo;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.mindrush.backend.config.AppDemoProperties;

import java.time.ZoneId;
import java.time.ZonedDateTime;

@RestController
public class AppInfoController {

    private final AppDemoProperties demoProperties;

    public AppInfoController(AppDemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    @GetMapping("/api/app/info")
    public AppInfoDto info() {
        return new AppInfoDto(
                demoProperties.isEnabled(),
                normalize(demoProperties.getBanner().getLabel()),
                normalize(demoProperties.getBanner().getMessage()),
                nextResetAt()
        );
    }

    private String nextResetAt() {
        if (!demoProperties.isEnabled() || !demoProperties.getReset().isScheduled()) {
            return null;
        }
        String cron = normalize(demoProperties.getReset().getCron());
        String zoneId = normalize(demoProperties.getReset().getZone());
        if (cron == null || zoneId == null) {
            return null;
        }
        try {
            CronExpression expression = CronExpression.parse(cron);
            ZoneId zone = ZoneId.of(zoneId);
            ZonedDateTime next = expression.next(ZonedDateTime.now(zone));
            return next == null ? null : next.toInstant().toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record AppInfoDto(
            boolean demo,
            String bannerLabel,
            String bannerMessage,
            String nextResetAt
    ) {
    }
}
