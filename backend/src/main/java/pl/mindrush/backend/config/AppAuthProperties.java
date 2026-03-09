package pl.mindrush.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth")
public class AppAuthProperties {

    private boolean requireVerifiedEmail = false;

    public boolean isRequireVerifiedEmail() {
        return requireVerifiedEmail;
    }

    public void setRequireVerifiedEmail(boolean requireVerifiedEmail) {
        this.requireVerifiedEmail = requireVerifiedEmail;
    }
}

