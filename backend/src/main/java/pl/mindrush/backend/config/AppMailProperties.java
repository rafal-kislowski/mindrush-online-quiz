package pl.mindrush.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.mail")
public class AppMailProperties {

    private boolean enabled = false;
    private String from = "no-reply@mindrush.local";
    private String supportEmail = "support@mindrush.local";
    private String frontendBaseUrl = "http://localhost:4200";
    private String verifyPath = "/verify-email";
    private String resetPath = "/reset-password";
    private Duration verifyTtl = Duration.ofHours(24);
    private Duration resetTtl = Duration.ofMinutes(30);
    private Duration verifyResendCooldown = Duration.ofMinutes(2);
    private Duration resetRequestCooldown = Duration.ofMinutes(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getSupportEmail() {
        return supportEmail;
    }

    public void setSupportEmail(String supportEmail) {
        this.supportEmail = supportEmail;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public String getVerifyPath() {
        return verifyPath;
    }

    public void setVerifyPath(String verifyPath) {
        this.verifyPath = verifyPath;
    }

    public String getResetPath() {
        return resetPath;
    }

    public void setResetPath(String resetPath) {
        this.resetPath = resetPath;
    }

    public Duration getVerifyTtl() {
        return verifyTtl;
    }

    public void setVerifyTtl(Duration verifyTtl) {
        this.verifyTtl = verifyTtl;
    }

    public Duration getResetTtl() {
        return resetTtl;
    }

    public void setResetTtl(Duration resetTtl) {
        this.resetTtl = resetTtl;
    }

    public Duration getVerifyResendCooldown() {
        return verifyResendCooldown;
    }

    public void setVerifyResendCooldown(Duration verifyResendCooldown) {
        this.verifyResendCooldown = verifyResendCooldown;
    }

    public Duration getResetRequestCooldown() {
        return resetRequestCooldown;
    }

    public void setResetRequestCooldown(Duration resetRequestCooldown) {
        this.resetRequestCooldown = resetRequestCooldown;
    }
}
