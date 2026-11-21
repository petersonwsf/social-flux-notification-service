package com.socialflux.notification;

import com.socialflux.notification.service.Maintenance;
import com.socialflux.notification.support.AbstractNotificationTest;
import com.socialflux.notification.support.Http;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** API REST: leitura só do próprio usuário, paginação, contagem, marcar como vistas, segurança e retenção. */
class ApiTest extends AbstractNotificationTest {

    @Autowired
    private Maintenance maintenance;

    // ------------------------------------------------------------------ segurança

    @Test
    void everyRouteRequiresAToken() {
        assertThat(http().get("/api/notificacoes", null).status()).isEqualTo(401);
        assertThat(http().get("/api/notificacoes/nao-vistas", null).status()).isEqualTo(401);
        assertThat(http().send("POST", "/api/notificacoes/vistas", "{\"ate\":1}", null).status()).isEqualTo(401);
        assertThat(http().get("/api/notificacoes/stream", null).status()).isEqualTo(401);
        assertThat(http().get("/api/notificacoes", "token.invalido.aqui").status()).isEqualTo(401);
    }

    @Test
    void thereIsNoRouteToCreateOrForgeNotifications() {
        TestUser ana = newUser();
        assertThat(http().send("POST", "/api/notificacoes", "{\"receiver\":\"x\"}", ana.token).status()).isEqualTo(405);
        assertThat(http().send("DELETE", "/api/notificacoes", null, ana.token).status()).isEqualTo(405);
        assertThat(http().get("/api/notification?userId=1", ana.token).status()).isEqualTo(404);   // a rota do legado não existe aqui
    }

    @Test
    void aServiceTokenIsNotAUserToken() {
        String serviceToken = sign(new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(USER_AUDIENCE).subject("svc:profile")
                .expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000))
                .claim("roles", List.of("USER")).build());
        assertThat(http().get("/api/notificacoes", serviceToken).status()).isEqualTo(403);
    }

    @Test
    void crossSiteWritesAreRejected() {
        TestUser ana = newUser();
        Http.Response response = http().send("POST", "/api/notificacoes/vistas", "{\"ate\":1}", ana.token,
                Map.of("Sec-Fetch-Site", "cross-site"));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().get("codigo")).isEqualTo("origem_invalida");
    }

    @Test
    void healthIsPublic() {
        assertThat(http().get("/actuator/health", null).status()).isEqualTo(200);
    }

    @Test
    void usersOnlySeeTheirOwnNotifications() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        liked(caio, ana, UUID.randomUUID());
        liked(caio, bia, UUID.randomUUID());
        liked(caio, bia, UUID.randomUUID());

        assertThat(notifications(ana)).hasSize(1);
        assertThat(notifications(bia)).hasSize(2);
        assertThat(unseen(ana)).isEqualTo(1);
        assertThat(unseen(bia)).isEqualTo(2);
        assertThat(unseen(caio)).isZero();
    }

    // ------------------------------------------------------------------ paginação

    @Test
    void listsAreOrderedByLatestActivityAndPaginatedByCursor() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        List<UUID> posts = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        posts.forEach(post -> liked(bia, ana, post));

        Http.Response first = http().get("/api/notificacoes?limite=2", ana.token);
        List<Map<String, Object>> page1 = items(first);
        assertThat(page1).extracting(n -> n.get("postagem")).containsExactly(posts.get(4).toString(), posts.get(3).toString());
        String cursor = (String) first.json().get("proximoCursor");
        assertThat(cursor).isNotBlank();

        Http.Response second = http().get("/api/notificacoes?limite=2&cursor=" + cursor, ana.token);
        assertThat(items(second)).extracting(n -> n.get("postagem")).containsExactly(posts.get(2).toString(), posts.get(1).toString());

        Http.Response third = http().get("/api/notificacoes?limite=2&cursor=" + second.json().get("proximoCursor"), ana.token);
        assertThat(items(third)).extracting(n -> n.get("postagem")).containsExactly(posts.get(0).toString());
        assertThat(third.json().get("proximoCursor")).isNull();
    }

    @Test
    void invalidPaginationParametersAreRejected() {
        TestUser ana = newUser();
        assertThat(http().get("/api/notificacoes?limite=0", ana.token).status()).isEqualTo(400);
        assertThat(http().get("/api/notificacoes?limite=51", ana.token).status()).isEqualTo(400);
        assertThat(http().get("/api/notificacoes?limite=abc", ana.token).status()).isEqualTo(400);
        assertThat(http().get("/api/notificacoes?cursor=lixo", ana.token).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ vistas

    @Test
    void markSeenOnlyAffectsTheCallerAndOnlyUpToTheSeqTheScreenShowed() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        liked(caio, ana, UUID.randomUUID());
        liked(caio, bia, UUID.randomUUID());
        long shown = seqOf(notifications(ana).get(0));
        liked(caio, ana, UUID.randomUUID());   // chegou depois de a tela ser exibida

        assertThat(markSeen(ana, shown).status()).isEqualTo(204);

        assertThat(unseen(ana)).isEqualTo(1);   // só a que a tela não mostrava continua nova
        assertThat(unseen(bia)).isEqualTo(1);   // a de outro usuário não foi tocada
        List<Map<String, Object>> list = notifications(ana);
        assertThat(list.get(0).get("vista")).isEqualTo(false);
        assertThat(list.get(1).get("vista")).isEqualTo(true);
    }

    @Test
    void markSeenValidatesTheBody() {
        TestUser ana = newUser();
        assertThat(http().send("POST", "/api/notificacoes/vistas", "{}", ana.token).status()).isEqualTo(400);
        assertThat(http().send("POST", "/api/notificacoes/vistas", "{\"ate\":-1}", ana.token).status()).isEqualTo(400);
        assertThat(http().send("POST", "/api/notificacoes/vistas", "{\"ate\":\"x\"}", ana.token).status()).isEqualTo(400);
        assertThat(http().send("POST", "/api/notificacoes/vistas", "isto não é json", ana.token).status()).isEqualTo(400);
    }

    @Test
    void theUnseenCounterIsCappedAt100() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        for (int i = 0; i < 105; i++) {
            liked(bia, ana, UUID.randomUUID());
        }
        assertThat(unseen(ana)).isEqualTo(100);
    }

    // ------------------------------------------------------------------ retenção (D13)

    @Test
    void retentionRemovesOldGroupsButKeepsPendingFollowRequests() {
        TestUser ana = newUser(true);
        TestUser bia = newUser();
        liked(bia, ana, UUID.randomUUID());
        followRequested(bia, ana);
        jdbc.sql("update notification_group set last_activity = now() - interval '46 days' where recipient_id = :id")
                .param("id", ana.id).update();

        int removed = maintenance.expireOld();

        assertThat(removed).isEqualTo(1);
        List<Map<String, Object>> list = notifications(ana);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("tipo")).isEqualTo("FOLLOW_REQUEST");
    }

    @Test
    void retentionKeepsRecentGroups() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        liked(bia, ana, UUID.randomUUID());
        jdbc.sql("update notification_group set last_activity = now() - interval '44 days' where recipient_id = :id")
                .param("id", ana.id).update();

        assertThat(maintenance.expireOld()).isZero();
        assertThat(notifications(ana)).hasSize(1);
    }

    @Test
    void oldTombstonesArePruned() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        follow(bia, ana);
        unfollow(bia, ana);
        jdbc.sql("update follow_removed set removed_at = now() - interval '8 days'").update();

        assertThat(maintenance.pruneTombstones()).isGreaterThanOrEqualTo(1);
    }
}
