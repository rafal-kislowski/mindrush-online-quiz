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
        String orderId = asString(variables, "orderId");
        String orderReference = asString(variables, "orderReference");
        String productName = asString(variables, "productName");
        String planName = asString(variables, "planName");
        String amountLabel = asString(variables, "amountLabel");
        String paymentProvider = asString(variables, "paymentProvider");
        String premiumEndsAt = asString(variables, "premiumEndsAt");
        String premiumExpiredAt = asString(variables, "premiumExpiredAt");

        String lowerSubject = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        if (lowerSubject.contains("order")) {
            StringBuilder orderText = new StringBuilder();
            orderText.append(subject == null ? "MindRush order" : subject).append("\n\n");
            if (!displayName.isBlank()) {
                orderText.append("Hi ").append(displayName).append(",\n\n");
            }
            orderText.append("Your order has been registered.\n");
            if (!orderReference.isBlank()) {
                orderText.append("Reference: ").append(orderReference).append('\n');
            }
            if (!orderId.isBlank()) {
                orderText.append("Order ID: ").append(orderId).append('\n');
            }
            appendOrderLineItems(orderText, variables);
            if (!productName.isBlank()) {
                orderText.append("Product: ").append(productName).append('\n');
            }
            if (!planName.isBlank()) {
                orderText.append("Plan: ").append(planName).append('\n');
            }
            if (!amountLabel.isBlank()) {
                orderText.append("Amount: ").append(amountLabel).append('\n');
            }
            if (!paymentProvider.isBlank() && !isCoinsOnlyOrder(variables)) {
                orderText.append("Provider: ").append(paymentProvider).append('\n');
            }
            if (!actionUrl.isBlank()) {
                orderText.append("\nOpen: ").append(actionUrl).append('\n');
            }
            if (!supportEmail.isBlank()) {
                orderText.append("\nSupport: ").append(supportEmail).append('\n');
            }
            return orderText.toString();
        }
        if (lowerSubject.contains("premium")) {
            StringBuilder premiumText = new StringBuilder();
            premiumText.append(subject == null ? "MindRush premium" : subject).append("\n\n");
            if (!displayName.isBlank()) {
                premiumText.append("Hi ").append(displayName).append(",\n\n");
            }
            if (!premiumEndsAt.isBlank()) {
                premiumText.append("Your premium is active until ").append(premiumEndsAt).append(".\n");
            } else if (!premiumExpiredAt.isBlank()) {
                premiumText.append("Your premium expired at ").append(premiumExpiredAt).append(".\n");
            }
            if (!actionUrl.isBlank()) {
                premiumText.append("\nOpen: ").append(actionUrl).append('\n');
            }
            if (!supportEmail.isBlank()) {
                premiumText.append("\nSupport: ").append(supportEmail).append('\n');
            }
            return premiumText.toString();
        }

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

    private static void appendOrderLineItems(StringBuilder text, Map<String, Object> variables) {
        if (variables == null || text == null) return;
        Object lineItemsRaw = variables.get("lineItems");
        if (!(lineItemsRaw instanceof Iterable<?> lineItems)) {
            return;
        }
        boolean hasAny = false;
        StringBuilder linesText = new StringBuilder();
        for (Object item : lineItems) {
            String product = readProperty(item, "productName");
            String plan = readProperty(item, "planName");
            String quantity = readProperty(item, "quantity");
            String unit = readProperty(item, "unitPriceLabel");
            String total = readProperty(item, "lineTotalLabel");
            if (product.isBlank() && plan.isBlank() && total.isBlank()) {
                continue;
            }
            hasAny = true;
            linesText.append("- ").append(product.isBlank() ? "Product" : product);
            if (!plan.isBlank()) {
                linesText.append(" / ").append(plan);
            }
            if (!quantity.isBlank()) {
                linesText.append(" x").append(quantity);
            }
            if (!unit.isBlank()) {
                linesText.append(" @ ").append(unit);
            }
            if (!total.isBlank()) {
                linesText.append(" = ").append(total);
            }
            linesText.append('\n');
        }
        if (hasAny) {
            text.append("Items:\n").append(linesText);
            Object totalsRaw = variables.get("totals");
            if (totalsRaw instanceof Iterable<?> totals) {
                for (Object totalItem : totals) {
                    String currency = readProperty(totalItem, "currency");
                    String amount = readProperty(totalItem, "amountLabel");
                    if (!currency.isBlank() && !amount.isBlank()) {
                        text.append("Total (").append(currency).append("): ").append(amount).append('\n');
                    }
                }
            }
        }
    }

    private static boolean isCoinsOnlyOrder(Map<String, Object> variables) {
        if (variables == null) return false;
        Object totalsRaw = variables.get("totals");
        if (!(totalsRaw instanceof Iterable<?> totals)) return false;
        boolean hasAny = false;
        for (Object totalItem : totals) {
            String currency = readProperty(totalItem, "currency");
            if (currency.isBlank()) continue;
            hasAny = true;
            if (!"COINS".equalsIgnoreCase(currency)) {
                return false;
            }
        }
        return hasAny;
    }

    private static String readProperty(Object source, String propertyName) {
        if (source == null || propertyName == null || propertyName.isBlank()) return "";
        try {
            var method = source.getClass().getMethod(propertyName);
            Object value = method.invoke(source);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
