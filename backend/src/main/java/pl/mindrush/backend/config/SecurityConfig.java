package pl.mindrush.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.JwtCookieAuthenticationFilter;
import pl.mindrush.backend.JwtService;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            AppUserRepository userRepository
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(forbiddenHandler())
                )
                .addFilterBefore(new JwtCookieAuthenticationFilter(jwtService, userRepository), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers("/api/health").permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/guest/session").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/guest/session/heartbeat").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/guest/session").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/guest/session").permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/quizzes/**").permitAll()

                        .requestMatchers(HttpMethod.GET, "/media/**").permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/leaderboard").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/leaderboard/stats").permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/lobbies/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/join").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/leave").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/close").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/max-players").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/selected-quiz").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/game/start").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/lobbies/*/game/state").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/game/answer").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/game/end").permitAll()

                        .requestMatchers("/ws/**").permitAll()

                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        .anyRequest().authenticated()
                )
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"UNAUTHORIZED\"}");
        };
    }

    private static AccessDeniedHandler forbiddenHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"FORBIDDEN\"}");
        };
    }
}
