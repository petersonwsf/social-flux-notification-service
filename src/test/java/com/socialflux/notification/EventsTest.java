package com.socialflux.notification;

import com.socialflux.notification.support.AbstractNotificationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Dos eventos às notificações: curtidas, respostas, seguir, solicitações, exclusões e a regra de privacidade (D7). */
class EventsTest extends AbstractNotificationTest {

    // ------------------------------------------------------------------ curtidas

    @Test
    void likeCreatesAGroupedNotificationForThePostAuthor() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        UUID post = UUID.randomUUID();

        liked(bia, ana, post);

        List<Map<String, Object>> list = notifications(ana);
        assertThat(list).hasSize(1);
        Map<String, Object> n = list.get(0);
        assertThat(n.get("tipo")).isEqualTo("LIKE");
        assertThat(n.get("postagem")).isEqualTo(post.toString());
        assertThat(n.get("totalAtores")).isEqualTo(1);
        assertThat(n.get("vista")).isEqualTo(false);
        assertThat(actorsOf(n)).extracting(a -> a.get("id")).containsExactly(bia.id.toString());
        assertThat(actorsOf(n).get(0).get("username")).isEqualTo(bia.username);
        assertThat(n).doesNotContainKeys("email", "telefone");
        assertThat(notifications(bia)).isEmpty();
    }

    @Test
    void likesOfTheSamePostAreGroupedAndShowTheTwoMostRecentActors() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        TestUser duda = newUser();
        UUID post = UUID.randomUUID();

        liked(bia, ana, post);
        liked(caio, ana, post);
        liked(duda, ana, post);

        List<Map<String, Object>> list = notifications(ana);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("totalAtores")).isEqualTo(3);
        assertThat(actorsOf(list.get(0))).extracting(a -> a.get("id")).containsExactly(duda.id.toString(), caio.id.toString());
    }

    @Test
    void theSameActorLikingAgainKeepsASingleEntry() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        UUID post = UUID.randomUUID();

        liked(bia, ana, post);
        long seq = seqOf(notifications(ana).get(0));
        liked(bia, ana, post);

        List<Map<String, Object>> list = notifications(ana);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("totalAtores")).isEqualTo(1);
        assertThat(seqOf(list.get(0))).isEqualTo(seq);   // nada mudou: nem sobe ao topo nem vira "não visto"
    }

    @Test
    void likingYourOwnPostDoesNotNotify() {
        TestUser ana = newUser();
        liked(ana, ana, UUID.randomUUID());
        assertThat(notifications(ana)).isEmpty();
    }

    @Test
    void unlikeRemovesOnlyThatActorAndTheGroupDisappearsWhenEmpty() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        UUID post = UUID.randomUUID();
        liked(bia, ana, post);
        liked(caio, ana, post);

        unliked(bia, ana, post);
        assertThat(notifications(ana).get(0).get("totalAtores")).isEqualTo(1);
        assertThat(actorsOf(notifications(ana).get(0))).extracting(a -> a.get("id")).containsExactly(caio.id.toString());

        unliked(caio, ana, post);
        assertThat(notifications(ana)).isEmpty();
        assertThat(count("select count(*) from notification_actor where actor_id = :id", caio.id)).isZero();
    }

    @Test
    void unlikeRemovesEvenAnAlreadySeenNotification() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        UUID post = UUID.randomUUID();
        liked(bia, ana, post);
        assertThat(markSeen(ana, seqOf(notifications(ana).get(0))).status()).isEqualTo(204);

        unliked(bia, ana, post);

        assertThat(notifications(ana)).isEmpty();
    }

    @Test
    void aNewActorBringsASeenGroupBackToUnseenAndToTheTop() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        UUID post = UUID.randomUUID();
        liked(bia, ana, post);
        markSeen(ana, seqOf(notifications(ana).get(0)));
        assertThat(unseen(ana)).isZero();

        liked(caio, ana, post);

        assertThat(unseen(ana)).isEqualTo(1);
        assertThat(notifications(ana).get(0).get("vista")).isEqualTo(false);
    }

    // ------------------------------------------------------------------ respostas

    @Test
    void replyNotifiesTheParentAuthorWithTheExcerptOfTheLatestReply() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        UUID post = UUID.randomUUID();

        replied(bia, ana, post, "primeira");
        UUID second = replied(caio, ana, post, "concordo demais");

        List<Map<String, Object>> list = notifications(ana);
        assertThat(list).hasSize(1);
        Map<String, Object> n = list.get(0);
        assertThat(n.get("tipo")).isEqualTo("REPLY");
        assertThat(n.get("totalAtores")).isEqualTo(2);
        assertThat(n.get("trecho")).isEqualTo("concordo demais");
        assertThat(n.get("resposta")).isEqualTo(second.toString());
        assertThat(n.get("postagem")).isEqualTo(post.toString());
    }

    @Test
    void rootPostsAndSelfRepliesDoNotNotify() {
        TestUser ana = newUser();
        rootPostCreated(ana, UUID.randomUUID());
        replied(ana, ana, UUID.randomUUID(), "eu mesma");
        assertThat(notifications(ana)).isEmpty();
    }

    @Test
    void deletingTheReplyRemovesItsAuthorFromTheGroup() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        UUID post = UUID.randomUUID();
        UUID biaReply = replied(bia, ana, post, "a");
        replied(caio, ana, post, "b");

        postDeleted(bia, biaReply);

        Map<String, Object> n = notifications(ana).get(0);
        assertThat(n.get("totalAtores")).isEqualTo(1);
        assertThat(actorsOf(n)).extracting(a -> a.get("id")).containsExactly(caio.id.toString());
    }

    @Test
    void deletingThePostRemovesItsLikeAndReplyNotifications() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        UUID post = UUID.randomUUID();
        liked(bia, ana, post);
        replied(bia, ana, post, "oi");
        assertThat(notifications(ana)).hasSize(2);

        postDeleted(ana, post);

        assertThat(notifications(ana)).isEmpty();
        assertThat(unseen(ana)).isZero();
    }

    @Test
    void replyFromAPrivateAccountTheRecipientDoesNotFollowDoesNotNotify() {
        TestUser ana = newUser();
        TestUser privada = newUser(true);
        UUID post = UUID.randomUUID();

        replied(privada, ana, post, "segredo");
        assertThat(notifications(ana)).isEmpty();

        follow(ana, privada);   // ana passa a seguir a conta privada
        replied(privada, ana, post, "agora pode");
        assertThat(notifications(ana)).hasSize(1);
        assertThat(notifications(ana).get(0).get("trecho")).isEqualTo("agora pode");
    }

    @Test
    void replyFromAnUnknownProfileIsNotNotifiedBecausePrivacyCannotBeChecked() {
        TestUser ana = newUser();
        TestUser desconhecido = unknownUser();   // o perfil dele ainda não chegou
        replied(desconhecido, ana, UUID.randomUUID(), "oi");
        assertThat(notifications(ana)).isEmpty();
    }

    // ------------------------------------------------------------------ seguir e solicitações

    @Test
    void newFollowersOfTheDayAreGroupedAndUnfollowingRemovesThem() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();

        follow(bia, ana);
        follow(caio, ana);
        List<Map<String, Object>> list = notifications(ana);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("tipo")).isEqualTo("FOLLOW");
        assertThat(list.get(0).get("totalAtores")).isEqualTo(2);
        assertThat(list.get(0).get("postagem")).isNull();

        unfollow(bia, ana);
        assertThat(notifications(ana).get(0).get("totalAtores")).isEqualTo(1);
        unfollow(caio, ana);
        assertThat(notifications(ana)).isEmpty();
    }

    @Test
    void followRequestShowsOneItemPerPersonAndGoesAwayWhenCancelledOrRejected() {
        TestUser ana = newUser(true);
        TestUser bia = newUser();
        TestUser caio = newUser();

        followRequested(bia, ana);
        followRequested(caio, ana);
        List<Map<String, Object>> list = notifications(ana);
        assertThat(list).hasSize(2);
        assertThat(list).allSatisfy(n -> {
            assertThat(n.get("tipo")).isEqualTo("FOLLOW_REQUEST");
            assertThat(n.get("totalAtores")).isEqualTo(1);
        });

        followRequestEvent("follow.request-cancelled.v1", bia, ana);
        followRequestEvent("follow.request-rejected.v1", caio, ana);
        assertThat(notifications(ana)).isEmpty();
    }

    @Test
    void acceptingARequestRemovesItAndNotifiesOnlyTheRequester() {
        TestUser ana = newUser(true);
        TestUser bia = newUser();
        followRequested(bia, ana);
        assertThat(notifications(ana)).hasSize(1);

        followCreated(bia, ana, "REQUEST_ACCEPTED");

        assertThat(notifications(ana)).isEmpty();   // quem aceitou não recebe nada (D3)
        List<Map<String, Object>> forBia = notifications(bia);
        assertThat(forBia).hasSize(1);
        assertThat(forBia.get(0).get("tipo")).isEqualTo("FOLLOW_ACCEPTED");
        assertThat(actorsOf(forBia.get(0))).extracting(a -> a.get("id")).containsExactly(ana.id.toString());
    }

    @Test
    void autoAcceptedRequestsWhenTheAccountBecomesPublicNotifyTheRequesters() {
        TestUser ana = newUser(true);
        TestUser bia = newUser();
        followRequested(bia, ana);

        followCreated(bia, ana, "AUTO_ON_PUBLIC");

        assertThat(notifications(ana)).isEmpty();
        assertThat(notifications(bia).get(0).get("tipo")).isEqualTo("FOLLOW_ACCEPTED");
    }

    @Test
    void unfollowingAlsoRemovesTheAcceptedNotification() {
        TestUser ana = newUser(true);
        TestUser bia = newUser();
        followRequested(bia, ana);
        followCreated(bia, ana, "REQUEST_ACCEPTED");
        assertThat(notifications(bia)).hasSize(1);

        unfollow(bia, ana);

        assertThat(notifications(bia)).isEmpty();
    }

    @Test
    void aLateFollowCreatedOfARemovedRelationDoesNotNotifyOrRecreateTheEdge() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        UUID relation = UUID.randomUUID();
        String removed = """
                {"id":"%s","type":"follow.removed.v1","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,
                 "data":{"relationId":"%s","followerId":"%s","followeeId":"%s","reason":"UNFOLLOW"}}
                """.formatted(UUID.randomUUID(), relation, relation, bia.id, ana.id);
        String created = """
                {"id":"%s","type":"follow.created.v1","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,
                 "data":{"relationId":"%s","followerId":"%s","followeeId":"%s","origin":"DIRECT","createdAt":"2026-01-01T00:00:00Z"}}
                """.formatted(UUID.randomUUID(), relation, relation, bia.id, ana.id);

        events.handleFollow(removed);
        events.handleFollow(created);

        assertThat(notifications(ana)).isEmpty();
        assertThat(count("select count(*) from follow_edge where followee_id = :id", ana.id)).isZero();
    }

    // ------------------------------------------------------------------ perfis e contas excluídas

    @Test
    void theShownNameFollowsTheLatestProfile() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        liked(bia, ana, UUID.randomUUID());

        bia.version++;
        events.handleProfile(profileEventJson(UUID.randomUUID(), "profile.updated.v1", bia)
                .replace("Pessoa " + bia.username, "Bia Nova"));

        assertThat(actorsOf(notifications(ana).get(0)).get(0).get("nome")).isEqualTo("Bia Nova");
    }

    @Test
    void deletingAnAccountRemovesItsNotificationsAsRecipientAndAsActor() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        UUID anaPost = UUID.randomUUID();
        UUID biaPost = UUID.randomUUID();
        liked(bia, ana, anaPost);
        liked(caio, ana, anaPost);
        liked(ana, bia, biaPost);   // ana é a atora de uma notificação da bia

        deleteProfile(ana);

        assertThat(notifications(bia)).isEmpty();                 // a curtida da ana sumiu
        assertThat(count("select count(*) from notification_group where recipient_id = :id", ana.id)).isZero();
        assertThat(count("select count(*) from notification_actor where actor_id = :id", ana.id)).isZero();
    }

    @Test
    void actorsOfDeletedAccountsDisappearFromTheGroupsOfOthers() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        UUID post = UUID.randomUUID();
        liked(bia, ana, post);
        liked(caio, ana, post);

        deleteProfile(bia);

        Map<String, Object> n = notifications(ana).get(0);
        assertThat(n.get("totalAtores")).isEqualTo(1);
        assertThat(actorsOf(n)).extracting(a -> a.get("id")).containsExactly(caio.id.toString());
    }

    @Test
    void anAccountDeletedBeforeItsCreatedEventStaysDeletedAndLateActionsAreIgnored() {
        TestUser ana = newUser();
        TestUser fantasma = unknownUser();
        fantasma.version = 5;
        deliverProfile("profile.deleted.v1", fantasma);
        fantasma.version = 1;
        deliverProfile("profile.created.v1", fantasma);   // atrasado: não revive

        liked(fantasma, ana, UUID.randomUUID());

        assertThat(notifications(ana)).isEmpty();
        assertThat(count("select count(*) from actor_snapshot where account_id = :id and status = 'DELETED'", fantasma.id))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ idempotência e robustez

    @Test
    void theSameMessageIsProcessedOnlyOnce() {
        TestUser ana = newUser();
        TestUser bia = newUser();
        TestUser caio = newUser();
        UUID post = UUID.randomUUID();
        String like = """
                {"id":"%s","type":"post.liked.v1","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,
                 "data":{"postId":"%s","postAuthorId":"%s","userId":"%s"}}
                """.formatted(UUID.randomUUID(), post, post, ana.id, bia.id);

        events.handlePost(like);
        events.handlePost(like);
        liked(caio, ana, post);

        assertThat(notifications(ana).get(0).get("totalAtores")).isEqualTo(2);
    }

    @Test
    void unknownEventTypesAreIgnored() {
        TestUser ana = newUser();
        events.handlePost("""
                {"id":"%s","type":"post.algo-novo.v7","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,
                 "data":{"foo":"bar"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()));
        assertThat(notifications(ana)).isEmpty();
    }

    @Test
    void anEventMissingARequiredFieldFailsSoTheBrokerRetriesAndDeadLetters() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> events.handlePost("""
                {"id":"%s","type":"post.liked.v1","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,
                 "data":{"postId":"%s"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
