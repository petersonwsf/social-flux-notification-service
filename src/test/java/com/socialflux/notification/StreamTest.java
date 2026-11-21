package com.socialflux.notification;

import com.socialflux.notification.support.AbstractNotificationTest;
import com.socialflux.notification.support.SseClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Tempo real (SSE): entrega depois do commit, isolamento entre usuários, reenvio, limites e fim do stream. */
class StreamTest extends AbstractNotificationTest {

    private static final Duration SHORT = Duration.ofMillis(600);

    @Test
    void connectingSendsTheUnseenCountFirst() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        liked(bia, ana, UUID.randomUUID());

        try (SseClient sse = stream(ana, null)) {
            SseClient.Event count = sse.await("contagem");
            assertThat(sse.status).isEqualTo(200);
            assertThat(count.json().path("naoVistas").asInt()).isEqualTo(1);
            assertThat(sse.events.get(0).name()).isEqualTo("contagem");
        }
    }

    @Test
    void aNewNotificationArrivesInRealTimeWithItsSeqAsTheEventId() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        try (SseClient sse = stream(ana, null)) {
            sse.await("contagem");

            liked(bia, ana, UUID.randomUUID());

            SseClient.Event event = sse.await("notificacao");
            assertThat(event.json().path("tipo").asString()).isEqualTo("LIKE");
            assertThat(event.json().path("atores").get(0).path("id").asString()).isEqualTo(bia.id.toString());
            assertThat(event.id()).isEqualTo(String.valueOf(event.json().path("seq").asLong()));
            assertThat(sse.await("contagem", e -> e.json().path("naoVistas").asInt() == 1, Duration.ofSeconds(5))).isNotNull();
        }
    }

    @Test
    void aGroupUpdateReplacesTheSameGroupId() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        UUID post = UUID.randomUUID();
        try (SseClient sse = stream(ana, null)) {
            sse.await("contagem");
            liked(bia, ana, post);
            SseClient.Event first = sse.await("notificacao");
            liked(caio, ana, post);
            SseClient.Event second = sse.await("notificacao", e -> e.json().path("totalAtores").asInt() == 2, Duration.ofSeconds(5));

            assertThat(second.json().path("id").asString()).isEqualTo(first.json().path("id").asString());
            assertThat(Long.parseLong(second.id())).isGreaterThan(Long.parseLong(first.id()));
        }
    }

    @Test
    void undoingTheActionPushesRemovedAndTheNewCount() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        UUID post = UUID.randomUUID();
        liked(bia, ana, post);
        try (SseClient sse = stream(ana, null)) {
            sse.await("contagem");
            String groupId = notifications(ana).get(0).get("id").toString();

            unliked(bia, ana, post);

            assertThat(sse.await("removida").json().path("id").asString()).isEqualTo(groupId);
            assertThat(sse.await("contagem", e -> e.json().path("naoVistas").asInt() == 0, Duration.ofSeconds(5))).isNotNull();
        }
    }

    @Test
    void markingAsSeenInAnotherTabUpdatesTheBadgeOfTheOthers() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        liked(bia, ana, UUID.randomUUID());
        try (SseClient sse = stream(ana, null)) {
            sse.await("contagem", e -> e.json().path("naoVistas").asInt() == 1, Duration.ofSeconds(5));

            markSeen(ana, seqOf(notifications(ana).get(0)));

            sse.await("contagem", e -> e.json().path("naoVistas").asInt() == 0, Duration.ofSeconds(5));
        }
    }

    @Test
    void otherUsersNeverReceiveSomeoneElsesNotifications() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        try (SseClient anaStream = stream(ana, null); SseClient caioStream = stream(caio, null)) {
            anaStream.await("contagem");
            caioStream.await("contagem");

            liked(bia, ana, UUID.randomUUID());

            anaStream.await("notificacao");
            assertThat(caioStream.noneAfter("notificacao", e -> true, SHORT)).isTrue();
        }
    }

    @Test
    void nothingIsPushedWhenTheEventFails() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        try (SseClient sse = stream(ana, null)) {
            sse.await("contagem");

            // evento inválido (falta o userId): a transação desfaz e nada é empurrado
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> events.handlePost("""
                    {"id":"%s","type":"post.liked.v1","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,
                     "data":{"postId":"%s","postAuthorId":"%s"}}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ana.id)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(sse.noneAfter("notificacao", e -> true, SHORT)).isTrue();
        }
    }

    @Test
    void reconnectingWithLastEventIdResendsOnlyWhatChangedAfterIt() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        liked(bia, ana, p1);
        long seenSeq = seqOf(notifications(ana).get(0));
        liked(bia, ana, p2);
        liked(bia, ana, p3);

        try (SseClient sse = stream(ana, String.valueOf(seenSeq))) {
            sse.await("notificacao", e -> p3.toString().equals(e.json().path("postagem").asString()), Duration.ofSeconds(5));
            sse.await("notificacao", e -> p2.toString().equals(e.json().path("postagem").asString()), Duration.ofSeconds(5));
            assertThat(sse.events.stream().filter(e -> e.name().equals("notificacao")))
                    .noneMatch(e -> p1.toString().equals(e.json().path("postagem").asString()));
            // reenvio em ordem de mudança (do mais antigo para o mais novo)
            List<Long> ids = new ArrayList<>();
            sse.events.stream().filter(e -> e.name().equals("notificacao")).forEach(e -> ids.add(Long.parseLong(e.id())));
            assertThat(ids).isSorted();
        }
    }

    @Test
    void whenTooMuchWasMissedTheClientIsToldToReload() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        for (int i = 0; i < 8; i++) {   // o limite de reenvio nos testes é 5
            liked(bia, ana, UUID.randomUUID());
        }
        try (SseClient sse = stream(ana, "0")) {
            sse.await("recarregar");
            assertThat(sse.events.stream().filter(e -> e.name().equals("notificacao"))).isEmpty();
        }
    }

    @Test
    void openingAStreamBeyondTheLimitClosesTheOldestOne() {
        TestUser ana = newUser();
        try (SseClient first = stream(ana, null)) {
            first.await("contagem");
            try (SseClient second = stream(ana, null); SseClient third = stream(ana, null)) {
                second.await("contagem");
                third.await("contagem");
                assertThat(first.ended).isFalse();

                try (SseClient fourth = stream(ana, null)) {   // o limite de teste é 3
                    fourth.await("contagem");
                    assertThat(first.await("encerrado").json().path("motivo").asString()).isEqualTo("substituido");
                    assertThat(first.awaitEnd(Duration.ofSeconds(5))).isTrue();
                    assertThat(second.ended).isFalse();
                }
            }
        }
    }

    @Test
    void theStreamEndsWhenTheTokenExpiresAndTellsTheClientToRenew() {
        TestUser ana = newUser();
        String shortToken = userToken(ana.id, Duration.ofSeconds(3));
        try (SseClient sse = new SseClient(http().baseUrl(), "/api/notificacoes/stream", shortToken, null)) {
            sse.await("contagem");
            assertThat(sse.await("encerrado", e -> true, Duration.ofSeconds(10)).json().path("motivo").asString())
                    .isEqualTo("renovar");
            assertThat(sse.awaitEnd(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void anIdleStreamReceivesPingsToStayOpen() {
        TestUser ana = newUser();
        try (SseClient sse = stream(ana, null)) {
            sse.await("contagem");
            long limit = System.nanoTime() + Duration.ofSeconds(6).toNanos();
            while (sse.comments.isEmpty() && System.nanoTime() < limit) {
                sleep(50);
            }
            assertThat(sse.comments).contains("ping");
        }
    }

    @Test
    void deletingTheAccountClosesItsStreams() {
        TestUser ana = newUser();
        try (SseClient sse = stream(ana, null)) {
            sse.await("contagem");

            deleteProfile(ana);

            assertThat(sse.await("encerrado").json().path("motivo").asString()).isEqualTo("conta_excluida");
            assertThat(sse.awaitEnd(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void closedStreamsFreeTheirSlot() {
        TestUser ana = newUser();
        for (int i = 0; i < 6; i++) {   // seria impossível manter 6 abertos com o limite de 3 sem liberar as vagas
            try (SseClient sse = stream(ana, null)) {
                sse.await("contagem");
            }
        }
        try (SseClient last = stream(ana, null)) {
            last.await("contagem");
            assertThat(last.status).isEqualTo(200);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
