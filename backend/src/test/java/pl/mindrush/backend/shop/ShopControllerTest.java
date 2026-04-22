package pl.mindrush.backend.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.RefreshTokenRepository;
import pl.mindrush.backend.notification.UserNotificationRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=true",
        "app.jwt.secret=test-secret-please-change",
        "app.shop.payment-provider=SIMULATED"
})
@AutoConfigureMockMvc
class ShopControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private ShopOrderRepository shopOrderRepository;

    @Autowired
    private UserNotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        shopOrderRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void catalog_isPubliclyAvailable() throws Exception {
        mockMvc.perform(get("/api/shop/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].slug").value("premium"))
                .andExpect(jsonPath("$.items[0].pricingPlans[0].code").value("DAY_1"));
    }

    @Test
    void simulateSuccessfulPayment_activatesPremiumAndStoresOrderFulfillment() throws Exception {
        Cookie accessCookie = loginAsUser("shop-user@example.com");

        MvcResult createOrderResult = mockMvc.perform(post("/api/shop/orders")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productSlug": "premium",
                                  "planCode": "DAY_7"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andReturn();

        String publicId = objectMapper.readTree(createOrderResult.getResponse().getContentAsString()).get("publicId").asText();

        mockMvc.perform(post("/api/shop/orders/" + publicId + "/simulate-payment")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "outcome": "SUCCESS"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.premiumExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.simulationActionsEnabled").value(false));

        AppUser user = userRepository.findByEmailIgnoreCase("shop-user@example.com").orElseThrow();
        assertThat(user.getRoles()).contains(AppRole.PREMIUM);
        assertThat(user.getPremiumExpiresAt()).isAfter(clock.instant().plusSeconds(6L * 24L * 60L * 60L));

        ShopOrder order = shopOrderRepository.findByPublicIdAndUserId(publicId, user.getId()).orElseThrow();
        assertThat(order.getPaidAt()).isNotNull();
        assertThat(order.getFulfilledAt()).isNotNull();
        assertThat(order.getPremiumStartsAt()).isNotNull();
        assertThat(order.getPremiumExpiresAt()).isNotNull();
    }

    @Test
    void loginAndMe_removeExpiredPremiumRoleFromLegacyState() throws Exception {
        AppUser user = new AppUser(
                "expired-premium@example.com",
                passwordEncoder.encode("Password123"),
                "ExpiredPremium",
                Set.of(AppRole.USER, AppRole.PREMIUM),
                clock.instant()
        );
        user.setPremiumActivatedAt(clock.instant().minusSeconds(10 * 24 * 60 * 60L));
        user.setPremiumExpiresAt(clock.instant().minusSeconds(60));
        userRepository.save(user);

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("expired-premium@example.com", "Password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premiumExpiresAt").isNotEmpty())
                .andReturn();

        String setCookie = String.join("\n", loginResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        String access = cookieValueFromSetCookie(setCookie, "accessToken").orElseThrow();

        MvcResult meResult = mockMvc.perform(get("/api/auth/me").cookie(new Cookie("accessToken", access)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode meJson = objectMapper.readTree(meResult.getResponse().getContentAsString());
        assertThat(meJson.get("roles").toString()).doesNotContain("PREMIUM");

        AppUser refreshed = userRepository.findByEmailIgnoreCase("expired-premium@example.com").orElseThrow();
        assertThat(refreshed.getRoles()).doesNotContain(AppRole.PREMIUM);
    }

    private Cookie loginAsUser(String email) throws Exception {
        AppUser user = new AppUser(
                email,
                passwordEncoder.encode("Password123"),
                "ShopUser",
                Set.of(AppRole.USER),
                clock.instant()
        );
        userRepository.save(user);

        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "Password123"))))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = String.join("\n", loginRes.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        String access = cookieValueFromSetCookie(setCookie, "accessToken").orElseThrow();
        return new Cookie("accessToken", access);
    }

    private static Optional<String> cookieValueFromSetCookie(String setCookieHeader, String name) {
        if (setCookieHeader == null || setCookieHeader.isBlank()) return Optional.empty();
        String[] headers = setCookieHeader.split("\n");
        String prefix = name + "=";
        for (String header : headers) {
            String normalized = header == null ? "" : header.trim();
            if (!normalized.startsWith(prefix)) continue;
            int end = normalized.indexOf(';');
            String value = end >= 0 ? normalized.substring(prefix.length(), end) : normalized.substring(prefix.length());
            if (!value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private record LoginRequest(String email, String password) {}
}
