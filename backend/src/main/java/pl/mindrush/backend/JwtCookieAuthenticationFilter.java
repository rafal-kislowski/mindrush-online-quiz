package pl.mindrush.backend;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppUserRepository userRepository;

    public JwtCookieAuthenticationFilter(JwtService jwtService, AppUserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            cookieValue(request, AuthCookies.ACCESS_COOKIE).ifPresent(token -> {
                try {
                    JwtService.JwtPayload payload = jwtService.parseAndValidateAccessToken(token);
                    AppUser user = userRepository.findById(payload.userId()).orElse(null);
                    if (user != null) {
                        if (user.getRoles().contains(AppRole.BANNED)) {
                            return;
                        }
                        AuthenticatedUser principal = new AuthenticatedUser(
                                user.getId(),
                                user.getEmail(),
                                user.getDisplayName(),
                                user.getRoles().stream().map(Enum::name).toList()
                        );
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                toAuthorities(principal.roles())
                        );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (JwtService.UnauthorizedException ignored) {
                    // ignore invalid tokens (treat as anonymous)
                }
            });
        }

        filterChain.doFilter(request, response);
    }

    private static Collection<? extends GrantedAuthority> toAuthorities(List<String> roles) {
        if (roles == null) return List.of();
        return roles.stream()
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    private static Optional<String> cookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie c : cookies) {
            if (cookieName.equals(c.getName())) {
                String v = c.getValue();
                return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
            }
        }
        return Optional.empty();
    }

    public record AuthenticatedUser(Long id, String email, String displayName, List<String> roles) {}
}
