package pl.mindrush.backend.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import pl.mindrush.backend.config.AppMailProperties;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

@Service
public class TransactionalMailService {

    private static final Logger log = LoggerFactory.getLogger(TransactionalMailService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final SpringTemplateEngine templateEngine;
    private final AppMailProperties mailProperties;

    public TransactionalMailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            SpringTemplateEngine templateEngine,
            AppMailProperties mailProperties
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.templateEngine = templateEngine;
        this.mailProperties = mailProperties;
    }

    public void sendTemplate(String to, String subject, String templateName, Map<String, Object> variables) {
        if (to == null || to.isBlank() || subject == null || subject.isBlank() || templateName == null || templateName.isBlank()) {
            return;
        }

        Context context = new Context();
        if (variables != null && !variables.isEmpty()) {
            context.setVariables(variables);
        }
        String html = templateEngine.process(templateName, context);
        String plainText = buildPlainTextFallback(subject, variables);

        if (!mailProperties.isEnabled()) {
            log.info("Mail disabled. Skipping SMTP send. to={}, subject={}, template={}", to, subject, templateName);
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("JavaMailSender bean is not configured");
        }

        try {
            var mimeMessage = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(mimeMessage, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(mailProperties.getFrom());
            helper.setSubject(subject);
            helper.setText(plainText, html);
            mailSender.send(mimeMessage);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to send email", ex);
        }
    }

    private static String buildPlainTextFallback(String subject, Map<String, Object> variables) {
        String displayName = asString(variables, "displayName");
        String actionUrl = asString(variables, "actionUrl");
        String ttlMinutes = asString(variables, "ttlMinutes");
        String supportEmail = asString(variables, "supportEmail");

        String lowerSubject = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        String actionLine = "Open this secure link:";
        if (lowerSubject.contains("verify")) {
            actionLine = "Verify your account using this link:";
        } else if (lowerSubject.contains("reset")) {
            actionLine = "Reset your password using this link:";
        }

        StringBuilder text = new StringBuilder();
        text.append(subject == null ? "MindRush message" : subject).append("\n\n");
        if (!displayName.isBlank()) {
            text.append("Hi ").append(displayName).append(",\n\n");
        }
        text.append(actionLine).append('\n');
        text.append(actionUrl.isBlank() ? "(link unavailable)" : actionUrl).append('\n');

        if (!ttlMinutes.isBlank()) {
            text.append("\nThis link expires in ").append(ttlMinutes).append(" minutes.\n");
        }
        if (!supportEmail.isBlank()) {
            text.append("\nSupport: ").append(supportEmail).append('\n');
        }
        return text.toString();
    }

    private static String asString(Map<String, Object> variables, String key) {
        if (variables == null || key == null || key.isBlank()) return "";
        Object value = variables.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
