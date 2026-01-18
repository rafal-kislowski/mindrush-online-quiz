package pl.mindrush.backend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthService.AuthUserDto> register(@Valid @RequestBody RegisterRequest req) {
        AuthService.AuthResult res = authService.register(req.email(), req.password());
        return withCookies(ResponseEntity.status(CREATED).body(res.user()), res.cookies());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthService.AuthUserDto> login(@Valid @RequestBody LoginRequest req) {
        AuthService.AuthResult res = authService.login(req.email(), req.password());
        return withCookies(ResponseEntity.ok(res.user()), res.cookies());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthService.AuthUserDto> refresh(
            @CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String refreshToken
    ) {
        AuthService.AuthResult res = authService.refresh(refreshToken);
        return withCookies(ResponseEntity.ok(res.user()), res.cookies());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String refreshToken
    ) {
        authService.logout(refreshToken);
        AuthService.ResponseCookies cleared = authService.clearCookies();
        return withCookies(ResponseEntity.noContent().build(), cleared);
    }

    @GetMapping("/me")
    public ResponseEntity<AuthService.AuthUserDto> me(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return ResponseEntity.status(401).build();
        }
        Object p = authentication.getPrincipal();
        if (p instanceof JwtCookieAuthenticationFilter.AuthenticatedUser au) {
            return ResponseEntity.ok(new AuthService.AuthUserDto(au.id(), au.email(), au.roles()));
        }
        return ResponseEntity.status(401).build();
    }

    private static <T> ResponseEntity<T> withCookies(ResponseEntity<T> response, AuthService.ResponseCookies cookies) {
        return ResponseEntity.status(response.getStatusCode())
                .headers(h -> {
                    if (response.getHeaders().containsKey(HttpHeaders.CONTENT_TYPE)) {
                        h.setContentType(response.getHeaders().getContentType());
                    }
                    h.add(HttpHeaders.SET_COOKIE, cookies.access().toString());
                    h.add(HttpHeaders.SET_COOKIE, cookies.refresh().toString());
                })
                .body(response.getBody());
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 72) String password
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 72) String password
    ) {}
}

