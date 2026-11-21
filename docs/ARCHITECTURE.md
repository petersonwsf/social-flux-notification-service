# Arquitetura — notification_service

Serviço de notificações. Camadas: `web` (controllers, DTOs, erros) → `service` (regras e escrita transacional) → `domain`
(repositórios JDBC e registros); `push` entrega em tempo real. Rotas, eventos e configuração: [`../README.md`](../README.md).

```
gateway (forward_auth) ─► SecurityConfig (JWT RS256, OriginCheckFilter; despachos ASYNC/ERROR liberados para o SSE)
  ├─► NotificationController ─► NotificationQueryService ─► GroupRepository (PostgreSQL `notification`)
  │                         └► NotificationWriter.markSeen
  └─► StreamController ─► StreamRegistry (open + fila por conexão) ◄── reenvio por seq (GroupRepository.since)
RabbitMQ ─► EventListeners ─► EventHandlers (Inbox) ─┬─► ProfileSyncService ─► actor_snapshot / follow_edge
                                                     ├─► FollowSyncService  ─┐
                                                     └─► PostSyncService    ─┴─► NotificationWriter ─► grupos e atores
                                                                                   └─► NotificationPush.afterCommit ─► StreamRegistry ─► streams SSE
@Scheduled ─► Maintenance (retenção de 45 dias, túmulos) · StreamRegistry.sweep (ping e fim do prazo)
```

## Árvore de diretórios

```
notification_service/
├── pom.xml                       parent spring-boot-starter-parent 4.1.2; socialflux-events; testes com embedded-postgres
├── Dockerfile                    build com contexto na raiz (instala a biblioteca de eventos), JRE 21 alpine, usuário sem privilégios
├── .gitignore                    exclui target/ e arquivos de IDE
├── README.md                     como as notificações nascem, regras, API, SSE, tabelas, configuração
├── docs/ARCHITECTURE.md          este arquivo
└── src/
    ├── main/java/com/socialflux/notification/
    │   ├── config/               propriedades e relógio
    │   ├── security/             resource server JWT e defesa CSRF
    │   ├── web/                  controllers, DTOs e erros
    │   ├── service/              escrita transacional dos grupos, leitura, sincronização por evento, rotinas
    │   ├── domain/               repositórios JDBC e registros
    │   ├── push/                 tempo real: porta de saída e registro dos streams SSE
    │   └── events/               filas, listeners e tratadores de eventos consumidos
    ├── main/resources/
    │   ├── application.yml       datasource, RabbitMQ, socialflux.events e bloco `notification.*`
    │   └── db/migration/V1__notification.sql
    └── test/java/com/socialflux/notification/   EventsTest, ApiTest, StreamTest + support/
```

## Arquivos (`com.socialflux.notification`)

| Arquivo | O que é |
| --- | --- |
| `NotificationServiceApplication` | Aplicação (`@ConfigurationPropertiesScan`, `@EnableScheduling`) |
| `config/NotificationProperties` | Todas as propriedades `notification.*` (segurança, SSE, manutenção), validadas na subida |
| `config/ClockConfig` | `Clock` UTC injetável |
| `security/SecurityConfig` | Cadeia de filtros: `/api/**` exige `ROLE_USER`, `/actuator/health` público, resto negado; stateless |
| `security/JwtDecoderConfig` | Decodificador RS256 pelo JWKS (emissor, audiência, relógio) |
| `security/JwtAuthorities` | Só token de usuário (`sub` UUID) ganha papéis |
| `security/OriginCheckFilter` | CSRF: recusa métodos não seguros de outra origem |
| `web/NotificationController` | Lista paginada, contagem e `POST /vistas` |
| `web/StreamController` | `GET /stream`: abre o stream, calcula o prazo, envia contagem e reenvio |
| `web/Dtos` | Corpos da API e dos eventos SSE |
| `web/ApiError`, `ApiException`, `GlobalExceptionHandler` | Erros `{ mensagem, sucesso, codigo }`; ignora o cliente do stream que foi embora |
| `service/NotificationWriter` | Toda mudança nos grupos (adicionar/remover ator, apagar grupo, excluir conta, marcar vistas) + aviso depois do commit |
| `service/NotificationQueryService` | Página por cursor, contagem e montagem de cada grupo com os 2 atores mais recentes |
| `service/PostSyncService` | `post.*` → LIKE e REPLY (regra D7) |
| `service/FollowSyncService` | `follow.*` → FOLLOW, FOLLOW_REQUEST, FOLLOW_ACCEPTED + cópia das arestas e túmulos |
| `service/ProfileSyncService` | `profile.*` → cópia dos perfis; exclusão apaga tudo da conta |
| `service/Maintenance` | Retenção (45 dias, exceto solicitações pendentes) e poda de túmulos |
| `domain/GroupRepository` | SQL de `notification_group` e `notification_actor` (upsert que trava a linha, remoções, páginas, reenvio) |
| `domain/ActorRepository`, `ActorSnapshot` | Cópia local dos perfis e consulta de arestas |
| `domain/FollowEdgeRepository` | Arestas aceitas e túmulos de vínculos removidos |
| `domain/Cursor` | Cursor opaco `(last_activity, id)` |
| `domain/GroupRow`, `ActorRow`, `GroupView` | Registros de leitura |
| `push/NotificationPush` | Porta de saída do tempo real (hoje, streams desta instância) |
| `push/PushEvent` | `notificacao`, `removida`, `contagem`, `recarregar` |
| `push/StreamRegistry` | Registro dos streams: fila por conexão, limites, ping, fim do prazo, entrega depois do commit |
| `events/Amqp`, `EventListeners`, `EventHandlers` | Filas `notification-service.{profile,follow,post}-events`, listeners e tratadores com inbox |

## `.github/workflows/`

| Arquivo | O que faz |
| --- | --- |
| `ci.yml` | CI em cada pull request: lint e, só se passar, testes |
| `cd.yml` | CD após o merge na `main`: repete os testes, publica a imagem no GHCR e atualiza o container na VPS por SSH (secrets listados no `docs/ARCHITECTURE.md` da raiz) |
