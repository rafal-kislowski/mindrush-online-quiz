package pl.mindrush.backend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
import pl.mindrush.backend.guest.GuestSessionService;
import pl.mindrush.backend.lobby.LobbyService;

import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AppUserRepository userRepository;
    private final GuestSessionService guestSessionService;
    private final LobbyService lobbyService;

    public AuthController(
            AuthService authService,
            AppUserRepository userRepository,
            GuestSessionService guestSessionService,
            LobbyService lobbyService
    ) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.guestSessionService = guestSessionService;
        this.lobbyService = lobbyService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthService.AuthUserDto> register(@Valid @RequestBody RegisterRequest req) {
        AuthService.AuthResult res = authService.register(req.email(), req.password(), req.displayName());
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
            HttpServletRequest request,
            @CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String refreshToken
    ) {
        authService.logout(refreshToken);
        guestSessionService.revokeSessionIfPresent(request).ifPresent(lobbyService::removeParticipantFromOpenLobbies);

        AuthService.ResponseCookies cleared = authService.clearCookies();
        return ResponseEntity.noContent()
                .headers(h -> {
                    h.add(HttpHeaders.SET_COOKIE, cleared.access().toString());
                    h.add(HttpHeaders.SET_COOKIE, cleared.refresh().toString());
                    h.add(HttpHeaders.SET_COOKIE, guestSessionService.clearCookieHeader());
                })
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthService.AuthUserDto> me(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return ResponseEntity.status(401).build();
        }
        Object p = authentication.getPrincipal();
        if (p instanceof JwtCookieAuthenticationFilter.AuthenticatedUser au) {
            return userRepository.findById(au.id())
                    .map(u -> ResponseEntity.ok(new AuthService.AuthUserDto(
                            u.getId(),
                            u.getEmail(),
                            u.getDisplayName(),
                            u.getRoles().stream().map(Enum::name).sorted().toList(),
                            u.getRankPoints(),
                            u.getXp(),
                            u.getCoins()
                    )))
                    .orElseGet(() -> ResponseEntity.status(401).build());
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
            @NotBlank(message = "Email is required")
            @Email(message = "Email format is invalid")
            @Size(max = 320, message = "Email is too long")
            String email,
            @NotBlank(message = "Nickname is required")
            @Size(min = 3, max = 32, message = "Nickname must be 3-32 characters")
            @Pattern(regexp = "^[A-Za-z0-9 _-]{3,32}$", message = "Nickname contains invalid characters")
            String displayName,
            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            String password
    ) {}

    public record LoginRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Email format is invalid")
            @Size(max = 320, message = "Email is too long")
            String email,
            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            String password
    ) {}
}
