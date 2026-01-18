package pl.mindrush.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AuthCookies {

    public static final String ACCESS_COOKIE = "accessToken";
    public static final String REFRESH_COOKIE = "refreshToken";

    private final boolean secure;

    public AuthCookies(@Value("${app.auth.cookie.secure:false}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie accessCookie(String token, Duration maxAge) {
        return baseCookie(ACCESS_COOKIE, token, maxAge);
    }

    public ResponseCookie refreshCookie(String token, Duration maxAge) {
        return baseCookie(REFRESH_COOKIE, token, maxAge);
    }

    public ResponseCookie clearAccessCookie() {
        return baseCookie(ACCESS_COOKIE, "", Duration.ZERO);
    }

    public ResponseCookie clearRefreshCookie() {
        return baseCookie(REFRESH_COOKIE, "", Duration.ZERO);
    }

    private ResponseCookie baseCookie(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
    }
}

