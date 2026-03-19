package pl.mindrush.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.demo")
public class AppDemoProperties {

    private boolean enabled = false;
    private final Banner banner = new Banner();
    private final Reset reset = new Reset();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Simulation simulation = new Simulation();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Banner getBanner() {
        return banner;
    }

    public Reset getReset() {
        return reset;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Simulation getSimulation() {
        return simulation;
    }

    public static final class Banner {
        private String label = "Public demo";
        private String message = "Demo data resets daily. Selected leaderboard entries and lobby activity are simulated.";

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static final class Reset {
        private boolean onStartup = true;
        private boolean scheduled = true;
        private String cron = "0 0 4 * * *";
        private String zone = "Europe/Warsaw";

        public boolean isOnStartup() {
            return onStartup;
        }

        public void setOnStartup(boolean onStartup) {
            this.onStartup = onStartup;
        }

        public boolean isScheduled() {
            return scheduled;
        }

        public void setScheduled(boolean scheduled) {
            this.scheduled = scheduled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }
    }

    public static final class Bootstrap {
        private int authenticatedUsers = 36;
        private int guestBots = 9;
        private int historicalMatches = 24;

        public int getAuthenticatedUsers() {
            return authenticatedUsers;
        }

        public void setAuthenticatedUsers(int authenticatedUsers) {
            this.authenticatedUsers = authenticatedUsers;
        }

        public int getGuestBots() {
            return guestBots;
        }

        public void setGuestBots(int guestBots) {
            this.guestBots = guestBots;
        }

        public int getHistoricalMatches() {
            return historicalMatches;
        }

        public void setHistoricalMatches(int historicalMatches) {
            this.historicalMatches = historicalMatches;
        }
    }

    public static final class Simulation {
        private boolean enabled = true;
        private long fixedDelayMs = 20_000L;
        private long initialDelayMs = 12_000L;
        private Duration sessionTtl = Duration.ofHours(12);
        private int openAuthenticatedLobbies = 6;
        private int openGuestLobbies = 4;
        private int inGameLobbies = 2;
        private int authenticatedLobbyPoolSize = 4;
        private int guestLobbyPoolSize = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public Duration getSessionTtl() {
            return sessionTtl;
        }

        public void setSessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
        }

        public int getOpenAuthenticatedLobbies() {
            return openAuthenticatedLobbies;
        }

        public void setOpenAuthenticatedLobbies(int openAuthenticatedLobbies) {
            this.openAuthenticatedLobbies = openAuthenticatedLobbies;
        }

        public int getOpenGuestLobbies() {
            return openGuestLobbies;
        }

        public void setOpenGuestLobbies(int openGuestLobbies) {
            this.openGuestLobbies = openGuestLobbies;
        }

        public int getInGameLobbies() {
            return inGameLobbies;
        }

        public void setInGameLobbies(int inGameLobbies) {
            this.inGameLobbies = inGameLobbies;
        }

        public int getAuthenticatedLobbyPoolSize() {
            return authenticatedLobbyPoolSize;
        }

        public void setAuthenticatedLobbyPoolSize(int authenticatedLobbyPoolSize) {
            this.authenticatedLobbyPoolSize = authenticatedLobbyPoolSize;
        }

        public int getGuestLobbyPoolSize() {
            return guestLobbyPoolSize;
        }

        public void setGuestLobbyPoolSize(int guestLobbyPoolSize) {
            this.guestLobbyPoolSize = guestLobbyPoolSize;
        }
    }
}
