package pl.mindrush.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/guest/session").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/guest/session").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/guest/session").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/lobbies/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/lobbies/*/join").permitAll()
                        .anyRequest().authenticated()
                )

                .httpBasic(basic -> {})
                .build();
    }
}
