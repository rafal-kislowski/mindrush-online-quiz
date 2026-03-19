package pl.mindrush.backend.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
@ConditionalOnProperty(name = {"app.demo.enabled", "app.demo.simulation.enabled"}, havingValue = "true")
public class DemoSimulationScheduler {

    private final DemoDatasetService demoDatasetService;

    public DemoSimulationScheduler(DemoDatasetService demoDatasetService) {
        this.demoDatasetService = demoDatasetService;
    }

    @Scheduled(
            fixedDelayString = "${app.demo.simulation.fixed-delay-ms:20000}",
            initialDelayString = "${app.demo.simulation.initial-delay-ms:12000}"
    )
    public void tick() {
        demoDatasetService.simulateTick();
    }
}
