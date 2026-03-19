package pl.mindrush.backend.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
@ConditionalOnProperty(name = {"app.demo.enabled", "app.demo.reset.scheduled"}, havingValue = "true")
public class DemoResetScheduler {

    private final DemoDatasetService demoDatasetService;

    public DemoResetScheduler(DemoDatasetService demoDatasetService) {
        this.demoDatasetService = demoDatasetService;
    }

    @Scheduled(
            cron = "${app.demo.reset.cron:0 0 4 * * *}",
            zone = "${app.demo.reset.zone:Europe/Warsaw}"
    )
    public void reset() {
        demoDatasetService.scheduledReset();
    }
}
