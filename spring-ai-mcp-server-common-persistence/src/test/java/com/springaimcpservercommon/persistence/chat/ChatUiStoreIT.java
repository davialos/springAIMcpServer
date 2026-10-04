package com.springaimcpservercommon.persistence.chat;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.persistence.chat.ChatUiStore.NewFeedback;
import com.springaimcpservercommon.persistence.chat.ChatUiStore.NewInteraction;
import com.springaimcpservercommon.persistence.config.support.DaiTestDatabase;
import com.springaimcpservercommon.persistence.config.support.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatUiStoreIT {

    private static final Instant T0 = Instant.parse("2026-10-04T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(30);
    private static final String PAYLOAD = "{\"componentId\":\"choice-1\",\"question\":\"Which?\","
            + "\"options\":[{\"value\":\"a\",\"label\":\"A\"},{\"value\":\"b\",\"label\":\"B\"}]}";

    private static DaiTestDatabase db;
    private static MutableClock clock;
    private static ChatUiStore store;

    private final UUID workspace = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID principal = UUID.randomUUID();

    @BeforeAll
    static void setUp() {
        db = DaiTestDatabase.create();
        clock = new MutableClock(T0);
        store = new ChatUiStore(db.store(), clock);
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    private static String key(String name) {
        return Sha256.of(name);
    }

    private NewInteraction shown(String key, UUID turn, String componentId) {
        return new NewInteraction(key, workspace, agent, principal, turn, componentId, "choice", PAYLOAD);
    }

    @Test
    void aShownComponentIsFoundOnceAndAnsweredOnce() {
        String k = key("conv-a");
        UUID turn = UUID.randomUUID();

        assertThat(store.recordShown(shown(k, turn, "choice-1"), TTL)).isTrue();
        assertThat(store.recordShown(shown(k, turn, "choice-1"), TTL)).isFalse();

        ChatInteraction found = store.find(k, turn, "choice-1").orElseThrow();
        assertThat(found.getAnswer()).isNull();
        assertThat(found.getPayload()).contains("Which?");

        assertThat(store.answer(k, turn, "choice-1", "{\"values\":[\"a\"],\"labels\":[\"A\"]}")).isTrue();
        assertThat(store.answer(k, turn, "choice-1", "{\"values\":[\"b\"],\"labels\":[\"B\"]}")).isFalse();
        assertThat(store.find(k, turn, "choice-1").orElseThrow().getAnswer()).contains("\"a\"");
    }

    @Test
    void anotherConversationCannotSeeOrAnswerAComponent() {
        UUID turn = UUID.randomUUID();
        store.recordShown(shown(key("conv-b"), turn, "choice-1"), TTL);

        assertThat(store.find(key("conv-other"), turn, "choice-1")).isEmpty();
        assertThat(store.answer(key("conv-other"), turn, "choice-1", "{\"values\":[\"a\"]}")).isFalse();
        assertThat(store.interactions(key("conv-other"))).isEmpty();
    }

    @Test
    void concurrentAnswersStoreExactlyOne() throws Exception {
        String k = key("conv-race");
        UUID turn = UUID.randomUUID();
        store.recordShown(shown(k, turn, "choice-1"), TTL);
        List<Callable<Boolean>> tasks = List.of(
                () -> store.answer(k, turn, "choice-1", "{\"values\":[\"a\"]}"),
                () -> store.answer(k, turn, "choice-1", "{\"values\":[\"b\"]}"),
                () -> store.answer(k, turn, "choice-1", "{\"values\":[\"a\"]}"),
                () -> store.answer(k, turn, "choice-1", "{\"values\":[\"b\"]}"));

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            long winners = pool.invokeAll(tasks).stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).count();
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void interactionsComeBackInOrderWithTheirAnswers() {
        String k = key("conv-c");
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        store.recordShown(shown(k, t1, "choice-1"), TTL);
        clock.advance(Duration.ofSeconds(5));
        store.recordShown(shown(k, t2, "choice-1"), TTL);
        store.answer(k, t1, "choice-1", "{\"values\":[\"b\"]}");

        assertThat(store.interactions(k)).extracting(ChatInteraction::getTurnId).containsExactly(t1, t2);
        assertThat(store.interactions(k).getFirst().getAnsweredAt()).isNotNull();
        assertThat(store.interactions(k).get(1).getAnswer()).isNull();
    }

    @Test
    void feedbackIsUpsertedAndCanBeWithdrawn() {
        String k = key("conv-d");
        UUID turn = UUID.randomUUID();
        store.putFeedback(new NewFeedback(k, workspace, agent, principal, turn, TurnFeedback.Rating.DOWN,
                "inaccurate", "wrong total"), TTL);
        clock.advance(Duration.ofMinutes(1));
        store.putFeedback(new NewFeedback(k, workspace, agent, principal, turn, TurnFeedback.Rating.UP, null, null),
                TTL);

        assertThat(store.feedback(k)).singleElement().satisfies(f -> {
            assertThat(f.getRating()).isEqualTo(TurnFeedback.Rating.UP);
            assertThat(f.getReason()).isNull();
            assertThat(f.getComment()).isNull();
            assertThat(f.getUpdatedAt()).isAfter(f.getCreatedAt());
        });
        assertThat(store.clearFeedback(k, turn)).isTrue();
        assertThat(store.clearFeedback(k, turn)).isFalse();
        assertThat(store.feedback(k)).isEmpty();
    }

    @Test
    void invalidInputIsRejectedBeforeTheDatabase() {
        assertThatThrownBy(() -> new NewFeedback(key("x"), workspace, agent, principal, UUID.randomUUID(),
                TurnFeedback.Rating.UP, "Not A Code", null)).hasMessageContaining("reason");
        assertThatThrownBy(() -> new NewInteraction("not-a-hash", workspace, agent, principal, UUID.randomUUID(),
                "c", "choice", PAYLOAD)).hasMessageContaining("conversationKey");
        assertThatThrownBy(() -> store.recordShown(new NewInteraction(key("x"), workspace, agent, principal,
                UUID.randomUUID(), "c", "choice", "[1,2]"), TTL)).hasMessageContaining("JSON object");
    }

    @Test
    void expiredStateIsHiddenAndPurged() {
        String k = key("conv-e");
        UUID turn = UUID.randomUUID();
        store.recordShown(shown(k, turn, "choice-1"), Duration.ofMinutes(10));
        store.putFeedback(new NewFeedback(k, workspace, agent, principal, turn, TurnFeedback.Rating.UP, null, null),
                Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(11));

        assertThat(store.find(k, turn, "choice-1")).isEmpty();
        assertThat(store.answer(k, turn, "choice-1", "{\"values\":[\"a\"]}")).isFalse();
        assertThat(store.feedback(k)).isEmpty();
        assertThat(store.purgeExpired(100)).isGreaterThanOrEqualTo(2);
        assertThat(store.purgeExpired(100)).isZero();
    }
}
