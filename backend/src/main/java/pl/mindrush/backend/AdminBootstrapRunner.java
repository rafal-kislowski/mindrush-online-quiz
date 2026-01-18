package pl.mindrush.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private final Clock clock;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminBootstrapRunner(
            Clock clock,
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.admin.email:}") String adminEmail,
            @Value("${app.bootstrap.admin.password:}") String adminPassword
    ) {
        this.clock = clock;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = adminEmail == null ? "" : adminEmail.trim();
        String password = adminPassword == null ? "" : adminPassword.trim();
        if (email.isBlank() || password.isBlank()) return;

        AppUser user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            user = new AppUser(
                    email.toLowerCase(),
                    passwordEncoder.encode(password),
                    Set.of(AppRole.ADMIN),
                    clock.instant()
            );
            userRepository.save(user);
            return;
        }

        Set<AppRole> roles = new HashSet<>(user.getRoles());
        if (!roles.contains(AppRole.ADMIN)) {
            roles.add(AppRole.ADMIN);
            user.setRoles(roles);
            userRepository.save(user);
        }
    }
}

