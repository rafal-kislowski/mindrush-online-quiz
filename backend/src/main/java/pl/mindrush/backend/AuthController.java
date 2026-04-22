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
import static org.springframework.http.HttpStatus.OK;

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
            return ResponseEntity.ok(authService.getCurrentUser(au.id()));
        }
        return ResponseEntity.status(401).build();
    }

    @PostMapping("/verification/resend")
    public ResponseEntity<ActionResponse> resendVerificationEmail(@Valid @RequestBody EmailRequest request) {
        authService.resendVerificationEmail(request.email());
        return ResponseEntity.status(OK).body(new ActionResponse(
                "If this account exists and still requires verification, an email has been sent."
        ));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ActionResponse> verifyEmail(@Valid @RequestBody TokenRequest request) {
        authService.verifyEmail(request.token());
        return ResponseEntity.ok(new ActionResponse("Your account email has been verified."));
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<ActionResponse> forgotPassword(@Valid @RequestBody EmailRequest request) {
        authService.requestPasswordReset(request.email());
        return ResponseEntity.ok(new ActionResponse(
                "If this email is registered, a password reset link has been sent."
        ));
    }

    @PostMapping("/password/reset")
    public ResponseEntity<ActionResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Passwords do not match"
            );
        }
        authService.resetPassword(request.token(), request.password());
        return ResponseEntity.ok(new ActionResponse("Password was reset successfully."));
    }

    @PostMapping("/profile/display-name")
    public ResponseEntity<AuthService.AuthUserDto> updateDisplayName(
            Authentication authentication,
            @Valid @RequestBody UpdateDisplayNameRequest request
    ) {
        AppUser user = requireAuthenticatedUser(authentication);
        AuthService.AuthUserDto updated = authService.updateDisplayName(user.getId(), request.displayName());
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/password/change")
    public ResponseEntity<AuthService.AuthUserDto> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Passwords do not match"
            );
        }
        AppUser user = requireAuthenticatedUser(authentication);
        AuthService.AuthResult res = authService.changePassword(user.getId(), request.currentPassword(), request.newPassword());
        return withCookies(ResponseEntity.ok(res.user()), res.cookies());
    }

    @PostMapping("/sessions/revoke-all")
    public ResponseEntity<ActionResponse> revokeAllSessions(Authentication authentication) {
        AppUser user = requireAuthenticatedUser(authentication);
        authService.revokeAllSessions(user.getId());
        AuthService.ResponseCookies cleared = authService.clearCookies();

        return ResponseEntity.ok()
                .headers(h -> {
                    h.add(HttpHeaders.SET_COOKIE, cleared.access().toString());
                    h.add(HttpHeaders.SET_COOKIE, cleared.refresh().toString());
                })
                .body(new ActionResponse("Signed out from all devices."));
    }

    private AppUser requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Authentication is required"
            );
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof JwtCookieAuthenticationFilter.AuthenticatedUser au)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Authentication is required"
            );
        }
        return userRepository.findById(au.id())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Authentication is required"
                ));
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

    public record EmailRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Email format is invalid")
            @Size(max = 320, message = "Email is too long")
            String email
    ) {}

    public record TokenRequest(
            @NotBlank(message = "Token is required")
            @Size(max = 400, message = "Token is too long")
            String token
    ) {}

    public record ResetPasswordRequest(
            @NotBlank(message = "Token is required")
            @Size(max = 400, message = "Token is too long")
            String token,
            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            String password,
            @NotBlank(message = "Confirm password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            String confirmPassword
    ) {}

    public record UpdateDisplayNameRequest(
            @NotBlank(message = "Nickname is required")
            @Size(min = 3, max = 32, message = "Nickname must be 3-32 characters")
            @Pattern(regexp = "^[A-Za-z0-9 _-]{3,32}$", message = "Nickname contains invalid characters")
            String displayName
    ) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            String currentPassword,
            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            String newPassword,
            @NotBlank(message = "Confirm password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            String confirmPassword
    ) {}

    public record ActionResponse(String message) {}
}
