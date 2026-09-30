package com.springaimcpservercommon.persistence.memory;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.persistence.config.support.DaiTestDatabase;
import com.springaimcpservercommon.persistence.config.support.MutableClock;
import com.springaimcpservercommon.persistence.memory.ChatMemoryMessage.Role;
import com.springaimcpservercommon.persistence.memory.ChatMemoryStore.Entry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMemoryStoreIT {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(24);

    private static DaiTestDatabase db;
    private static MutableClock clock;
    private static ChatMemoryStore store;

    @BeforeAll
    static void setUp() {
        db = DaiTestDatabase.create();
        clock = new MutableClock(T0);
        store = new ChatMemoryStore(db.store(), clock);
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    private static String key(String name) {
        return Sha256.of(name);
    }

    @Test
    void replaceThenLoadReturnsTheWindowInOrder() {
        String k = key("a");
        store.replace(k, List.of(new Entry(Role.USER, "hi"), new Entry(Role.ASSISTANT, "hello")), TTL);

        assertThat(store.load(k)).containsExactly(new Entry(Role.USER, "hi"), new Entry(Role.ASSISTANT, "hello"));
    }

    @Test
    void replaceSwapsTheWholeWindowAndAnEmptyListDeletes() {
        String k = key("b");
        store.replace(k, List.of(new Entry(Role.USER, "one"), new Entry(Role.ASSISTANT, "two")), TTL);
        store.replace(k, List.of(new Entry(Role.ASSISTANT, "two"), new Entry(Role.USER, "three")), TTL);
        assertThat(store.load(k)).extracting(Entry::content).containsExactly("two", "three");

        store.replace(k, List.of(), TTL);
        assertThat(store.load(k)).isEmpty();
    }

    @Test
    void memoriesAreIsolatedByKey() {
        store.replace(key("c1"), List.of(new Entry(Role.USER, "mine")), TTL);
        store.replace(key("c2"), List.of(new Entry(Role.USER, "yours")), TTL);

        assertThat(store.load(key("c1"))).extracting(Entry::content).containsExactly("mine");
        assertThat(store.load(key("c3"))).isEmpty();
    }

    @Test
    void anExpiredMemoryReadsAsEmptyAndIsPurged() {
        String k = key("d");
        store.replace(k, List.of(new Entry(Role.USER, "old")), Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(11));

        assertThat(store.load(k)).isEmpty();
        assertThat(store.purgeExpired(100)).isGreaterThanOrEqualTo(1);
        assertThat(store.purgeExpired(100)).isZero();
    }

    @Test
    void everyWriteSlidesTheExpiry() {
        String k = key("e");
        store.replace(k, List.of(new Entry(Role.USER, "x")), Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(8));
        store.replace(k, List.of(new Entry(Role.USER, "x"), new Entry(Role.ASSISTANT, "y")), Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(8));

        assertThat(store.load(k)).hasSize(2);
    }

    @Test
    void deleteRemovesTheMemory() {
        String k = key("f");
        store.replace(k, List.of(new Entry(Role.USER, "x")), TTL);
        store.delete(k);

        assertThat(store.load(k)).isEmpty();
    }

    @Test
    void keysMustBeSha256HexSoAConversationIdIsNeverStoredInTheClear() {
        assertThatThrownBy(() -> store.replace("conv-123", List.of(new Entry(Role.USER, "x")), TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
