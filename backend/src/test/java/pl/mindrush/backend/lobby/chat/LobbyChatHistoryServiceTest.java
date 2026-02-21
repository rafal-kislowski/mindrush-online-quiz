package pl.mindrush.backend.lobby.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LobbyChatHistoryServiceTest {

    @Test
    void historySince_returnsOnlyMessagesFromThreshold() {
        Clock clock = Clock.fixed(Instant.parse("2026-02-21T10:00:00Z"), ZoneOffset.UTC);
        LobbyChatHistoryService service = new LobbyChatHistoryService(clock);

        Instant t1 = Instant.parse("2026-02-21T10:00:01Z");
        Instant t2 = Instant.parse("2026-02-21T10:00:02Z");
        Instant t3 = Instant.parse("2026-02-21T10:00:03Z");

        service.append("ABC123", "Alice", "first", t1);
        service.append("ABC123", "Bob", "second", t2);
        service.append("ABC123", "Cara", "third", t3);

        List<LobbyChatMessageDto> fromT2 = service.historySince("ABC123", t2);
        assertThat(fromT2).hasSize(2);
        assertThat(fromT2.get(0).text()).isEqualTo("second");
        assertThat(fromT2.get(1).text()).isEqualTo("third");
    }

    @Test
    void append_keepsAtMostConfiguredLimitPerLobby() {
        Clock clock = Clock.fixed(Instant.parse("2026-02-21T10:00:00Z"), ZoneOffset.UTC);
        LobbyChatHistoryService service = new LobbyChatHistoryService(clock);

        Instant base = Instant.parse("2026-02-21T10:00:00Z");
        for (int i = 0; i < 2010; i++) {
            service.append("ROOM99", "User", "m" + i, base.plusSeconds(i));
        }

        List<LobbyChatMessageDto> all = service.historySince("ROOM99", Instant.EPOCH);
        assertThat(all).hasSize(2000);
        assertThat(all.get(0).text()).isEqualTo("m10");
        assertThat(all.get(all.size() - 1).text()).isEqualTo("m2009");
    }
}

