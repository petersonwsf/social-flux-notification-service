-- Esquema do notification_service (PostgreSQL). Banco próprio do serviço; nada aqui referencia outro banco.

-- Cópia local, somente leitura, do perfil PÚBLICO de quem age (alimentada pelos eventos profile.*). Nenhum dado pessoal.
create table actor_snapshot (
    account_id       uuid         primary key,              -- = id do perfil = `sub` do JWT
    username         varchar(60)  not null,
    display_name     varchar(120) not null,
    avatar_key       varchar(80),
    is_private       boolean      not null default false,
    status           varchar(10)  not null check (status in ('ACTIVE', 'DELETED')),
    profile_version  bigint       not null,                 -- aggregateVersion aplicado (ignora eventos antigos/duplicados)
    updated_at       timestamp with time zone not null
);

-- Quem segue quem (follow.*): só para a regra de que a resposta de uma conta privada que o destinatário não segue não
-- notifica (D7). O dono desta informação é o grafo.
create table follow_edge (
    follower_id  uuid not null,
    followee_id  uuid not null,
    relation_id  uuid not null,                             -- vínculo que gerou a aresta (protege contra evento antigo)
    created_at   timestamp with time zone not null,
    primary key (follower_id, followee_id)
);
create index idx_follow_edge_followee on follow_edge (followee_id);

-- "Túmulos" de vínculos removidos: um follow.created atrasado de um vínculo já removido não recria a aresta (podados após 7 dias).
create table follow_removed (
    relation_id  uuid primary key,
    removed_at   timestamp with time zone not null
);

-- Ordem global das mudanças: é o id do evento SSE e a base do reenvio e do "marcar como vistas".
create sequence notification_activity_seq;

-- Uma notificação exibida é um GRUPO (D6); cada pessoa que agiu é um ator do grupo.
create table notification_group (
    id             uuid         primary key,                -- UUIDv7
    recipient_id   uuid         not null,
    type           varchar(20)  not null check (type in ('LIKE', 'REPLY', 'FOLLOW', 'FOLLOW_REQUEST', 'FOLLOW_ACCEPTED')),
    -- LIKE:<post> · REPLY:<post> · FOLLOW:<dia UTC> · FOLLOW_REQUEST:<ator> · FOLLOW_ACCEPTED:<ator>
    group_key      varchar(120) not null,
    post_id        uuid,                                    -- postagem alvo (LIKE/REPLY)
    actor_count    integer      not null default 0 check (actor_count >= 0),
    last_activity  timestamp with time zone not null,       -- entrada do ator mais recente (ordena a lista)
    activity_seq   bigint       not null,                   -- nextval a cada mudança do grupo
    seen_at        timestamp with time zone,                -- null = não visto; volta a null quando entra um ator NOVO
    created_at     timestamp with time zone not null,
    unique (recipient_id, group_key)
);
create index idx_group_feed   on notification_group (recipient_id, last_activity desc, id desc);
create index idx_group_unseen on notification_group (recipient_id) where seen_at is null;
create index idx_group_seq    on notification_group (recipient_id, activity_seq);
create index idx_group_post   on notification_group (post_id) where post_id is not null;
create index idx_group_retention on notification_group (last_activity) where type <> 'FOLLOW_REQUEST';

create table notification_actor (
    group_id   uuid not null references notification_group (id) on delete cascade,
    actor_id   uuid not null,
    reply_id   uuid,                                        -- a resposta (REPLY)
    excerpt    varchar(120),                                -- trecho da resposta (REPLY)
    acted_at   timestamp with time zone not null,
    primary key (group_id, actor_id)                        -- D5: o mesmo ator não entra duas vezes no grupo
);
create index idx_actor_actor on notification_actor (actor_id);
create index idx_actor_reply on notification_actor (reply_id) where reply_id is not null;
create index idx_actor_recent on notification_actor (group_id, acted_at desc);

-- A biblioteca de eventos exige o outbox (este serviço não publica, D12) e o inbox (idempotência dos consumidores).
create table outbox_event (
    seq                bigint generated always as identity primary key,
    id                 uuid        not null unique,
    type               varchar(80) not null,
    aggregate_id       uuid        not null,
    aggregate_version  bigint      not null,
    payload            text        not null,
    created_at         timestamp with time zone not null,
    published_at       timestamp with time zone
);
create index idx_outbox_unpublished on outbox_event (seq) where published_at is null;

create table processed_message (
    id            uuid primary key,
    processed_at  timestamp with time zone not null
);
