package pl.mindrush.backend.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoBootstrapRunner implements ApplicationRunner {

    private final DemoDatasetService demoDatasetService;

    public DemoBootstrapRunner(DemoDatasetService demoDatasetService) {
        this.demoDatasetService = demoDatasetService;
    }

    @Override
    public void run(ApplicationArguments args) {
        demoDatasetService.bootstrap();
    }
}
