package pl.mindrush.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import pl.mindrush.backend.shop.ShopPaymentProvider;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.shop")
public class AppShopProperties {

    private ShopPaymentProvider paymentProvider = ShopPaymentProvider.SIMULATED;
    private long premiumExpirationCheckMs = 60_000L;
    private List<PricingCurrency> pricingCurrencies = new ArrayList<>(List.of(
            new PricingCurrency("PLN", "Polish zloty", "FIAT", 2),
            new PricingCurrency("EUR", "Euro", "FIAT", 2),
            new PricingCurrency("USD", "US dollar", "FIAT", 2),
            new PricingCurrency("COINS", "Coins", "GAME", 0)
    ));
    private Payu payu = new Payu();

    public ShopPaymentProvider getPaymentProvider() {
        return paymentProvider;
    }

    public void setPaymentProvider(ShopPaymentProvider paymentProvider) {
        this.paymentProvider = paymentProvider == null ? ShopPaymentProvider.SIMULATED : paymentProvider;
    }

    public long getPremiumExpirationCheckMs() {
        return premiumExpirationCheckMs;
    }

    public void setPremiumExpirationCheckMs(long premiumExpirationCheckMs) {
        this.premiumExpirationCheckMs = premiumExpirationCheckMs <= 0 ? 60_000L : premiumExpirationCheckMs;
    }

    public List<PricingCurrency> getPricingCurrencies() {
        return pricingCurrencies;
    }

    public void setPricingCurrencies(List<PricingCurrency> pricingCurrencies) {
        this.pricingCurrencies = pricingCurrencies == null ? new ArrayList<>() : new ArrayList<>(pricingCurrencies);
    }

    public Payu getPayu() {
        return payu;
    }

    public void setPayu(Payu payu) {
        this.payu = payu == null ? new Payu() : payu;
    }

    public static class Payu {
        private boolean sandboxEnabled;
        private String clientId;
        private String clientSecret;
        private String merchantPosId;
        private String notifyUrl;
        private String continueUrl;

        public boolean isSandboxEnabled() {
            return sandboxEnabled;
        }

        public void setSandboxEnabled(boolean sandboxEnabled) {
            this.sandboxEnabled = sandboxEnabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getMerchantPosId() {
            return merchantPosId;
        }

        public void setMerchantPosId(String merchantPosId) {
            this.merchantPosId = merchantPosId;
        }

        public String getNotifyUrl() {
            return notifyUrl;
        }

        public void setNotifyUrl(String notifyUrl) {
            this.notifyUrl = notifyUrl;
        }

        public String getContinueUrl() {
            return continueUrl;
        }

        public void setContinueUrl(String continueUrl) {
            this.continueUrl = continueUrl;
        }
    }

    public static class PricingCurrency {
        private String code;
        private String label;
        private String type = "FIAT";
        private int fractionDigits = 2;

        public PricingCurrency() {
        }

        public PricingCurrency(String code, String label, String type, int fractionDigits) {
            this.code = code;
            this.label = label;
            this.type = type;
            this.fractionDigits = fractionDigits;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getFractionDigits() {
            return fractionDigits;
        }

        public void setFractionDigits(int fractionDigits) {
            this.fractionDigits = fractionDigits;
        }
    }
}
