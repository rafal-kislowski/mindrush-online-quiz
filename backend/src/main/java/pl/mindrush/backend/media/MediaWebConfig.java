package pl.mindrush.backend.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class MediaWebConfig implements WebMvcConfigurer {

    private final Path rootDir;

    public MediaWebConfig(@Value("${app.media.dir:uploads}") String mediaDir) {
        this.rootDir = Paths.get(mediaDir).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = rootDir.toUri().toString();
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}

