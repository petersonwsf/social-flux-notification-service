# notification_service

Serviço de **notificações** do Social Flux: gera as notificações a partir dos eventos dos outros serviços (curtidas, respostas,
novos seguidores e solicitações de seguir), agrupa, e as entrega em **tempo real por SSE**. Spring Boot 4.1 · Java 21 ·
PostgreSQL próprio (banco `notification`) · RabbitMQ (só consome; inbox via `libs/socialflux-events`) · resource server JWT
RS256 (JWKS do `authentication_service`). Porta **8085**.

Plano e decisões (D1–D16): [`../docs/plano-dominio-notificacoes-servico-proprio.md`](../docs/plano-dominio-notificacoes-servico-proprio.md).
Estrutura do código: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Como as notificações nascem

**Nenhuma rota cria notificação.** O frontend não notifica mais: o serviço reage aos eventos (exchange `socialflux.events`).

| Evento consumido | Efeito |
| --- | --- |
| `post.liked.v1` | ator no grupo `LIKE` da postagem, para o autor dela |
| `post.unliked.v1` | tira o ator do grupo (D4: sempre, visto ou não); grupo sem atores some |
| `post.created.v1` com `parentId` | ator no grupo `REPLY` da postagem respondida, com o trecho da resposta. **D7:** resposta de conta privada que o destinatário não segue não notifica |
| `post.deleted.v1` | apaga os grupos cujo alvo é a postagem e tira o ator cuja resposta era ela |
| `follow.created.v1` `DIRECT` | ator no grupo `FOLLOW` do dia (UTC), para quem foi seguido |
| `follow.created.v1` `REQUEST_ACCEPTED` / `AUTO_ON_PUBLIC` | apaga a `FOLLOW_REQUEST` e cria `FOLLOW_ACCEPTED` **só para quem pediu** (D3) |
| `follow.requested.v1` | `FOLLOW_REQUEST` (um item por pessoa; o frontend mostra Aceitar/Recusar) |
| `follow.request-cancelled.v1` / `follow.request-rejected.v1` | apaga a `FOLLOW_REQUEST` |
| `follow.removed.v1` | tira o ator do grupo `FOLLOW` e apaga o `FOLLOW_ACCEPTED` da relação |
| `profile.created/updated/privacy-changed.v1` | atualiza a cópia local do perfil (nome, foto, privacidade) |
| `profile.deleted.v1` | túmulo no perfil; apaga as notificações da conta (como destinatária e como atora) e fecha os streams dela |

Consumidores idempotentes (inbox), tolerantes a tipos desconhecidos; evento inválido falha → 5 tentativas → fila de mensagens mortas.
O serviço **não publica eventos** (D12).

## Regras

| Regra | Detalhe |
| --- | --- |
| Identidade | Sempre do `sub` do token. Nenhuma rota recebe usuário; ninguém lê nem marca como vistas as notificações de outra pessoa |
| Grupos (D6) | Uma notificação exibida é um grupo: `LIKE`/`REPLY` por postagem, `FOLLOW` por dia, `FOLLOW_REQUEST`/`FOLLOW_ACCEPTED` um por pessoa. Um ator **novo** sobe o grupo ao topo e o volta a "não visto" |
| Dedup (D5) | O mesmo ator não entra duas vezes num grupo; repetir a ação não muda nada |
| Auto-notificação | Nunca (curtir, responder ou seguir a si mesmo) |
| Contas excluídas | Quem age de uma conta excluída (ou para uma) não gera notificação; a exclusão apaga o que ela tinha |
| Retenção (D13) | Grupos sem atividade há mais de 45 dias são apagados a cada hora; `FOLLOW_REQUEST` pendente não expira |
| Datas | `timestamptz`; a API devolve ISO-8601 em UTC |

## API (`/api/notificacoes`, token de usuário)

| Rota | Sucesso | Erros |
| --- | --- | --- |
| `GET /api/notificacoes?cursor&limite` | 200 `{ itens, proximoCursor }`, mais recentes primeiro (`limite` 1–50, padrão 20; cursor opaco) | 400 `parametro_invalido` |
| `GET /api/notificacoes/nao-vistas` | 200 `{ total }` (grupos não vistos, limitado a 100: o frontend mostra "99+") | — |
| `POST /api/notificacoes/vistas` `{ "ate": <seq> }` | 204. Marca como vistos os grupos com `seq <= ate`; o que mudou depois continua novo. Avisa as outras abas pelo stream | 400 `parametro_invalido`, 403 `origem_invalida` |
| `GET /api/notificacoes/stream` | 200 `text/event-stream` (abaixo) | 401; 503 `servidor_ocupado` |

**Notificação (grupo):**
```json
{
  "id": "0193a1b2-…", "seq": 4182, "tipo": "REPLY",
  "atores": [ { "id": "<uuid>", "username": "ana", "nome": "Ana", "foto_perfil": "av_….jpg", "foto_url": "/media/avatars/av_….jpg" } ],
  "totalAtores": 5, "postagem": "0192f3c4-…", "resposta": "0193a1b0-…", "trecho": "concordo demais!",
  "atualizada_em": "2026-11-15T14:03:22.123Z", "vista": false
}
```
`tipo` ∈ `LIKE`, `REPLY`, `FOLLOW`, `FOLLOW_REQUEST`, `FOLLOW_ACCEPTED`. `atores` traz os **2 mais recentes**; `totalAtores` é o total.
`postagem`, `resposta` e `trecho` são `null` quando não se aplicam. `seq` cresce a cada mudança do grupo. A frase ("Ana, Bia e mais 3
curtiram…") é montada no frontend. Os botões Aceitar/Recusar de `FOLLOW_REQUEST` chamam o `social_graph_service`.

## Tempo real (SSE) — `GET /api/notificacoes/stream`

| Evento | `data` | Quando |
| --- | --- | --- |
| `contagem` | `{ "naoVistas": 3 }` | sempre logo ao conectar; depois a cada mudança (inclui "marcar como vistas" em outra aba) |
| `notificacao` | a notificação (grupo) inteira; `id:` do evento = `seq` | grupo novo **ou** alterado (o frontend substitui pelo `id` e sobe ao topo) |
| `removida` | `{ "id": "<grupo>" }` | curtida desfeita, postagem excluída, solicitação aceita/recusada… |
| `recarregar` | `{}` | o cliente perdeu mais do que o reenvio comporta: recarregue a lista |
| `encerrado` | `{ "motivo": "renovar" \| "substituido" \| "conta_excluida" }` | o servidor vai fechar. `renovar` = fim planejado: reconecte já |
| `: ping` | comentário | a cada 25 s sem tráfego |

- **Reenvio:** ao reconectar, `Last-Event-ID` (ou `?desde=<seq>`) devolve os grupos que mudaram depois (até 50; mais que isso → `recarregar`).
  Remoções perdidas não são reenviadas: a `contagem` inicial e a próxima listagem corrigem.
- **Entrega só depois do commit** do evento que causou a notificação.
- **Token e sessão (§7.3 do plano):** o stream fecha em `min(exp do token, agora + 15 min ± 1 min)` com `encerrado`; a reconexão passa
  de novo pelo `forward_auth` do gateway (é o que limita a exposição de uma sessão revogada). O stream **nunca renova a sessão**:
  ocioso, ele cai em 401 e o frontend só reconecta quando o usuário voltar a agir.
- **Limites:** `NOTIFICATION_SSE_MAX_PER_USER` (5; o seguinte fecha o mais antigo, D11) e `NOTIFICATION_SSE_MAX_TOTAL` (5.000 → 503).
- **Ordem e lentidão:** cada conexão tem uma fila própria drenada por uma thread virtual; um cliente lento não trava quem consome o RabbitMQ.
- **Uma instância:** o registro dos streams é em memória (interface `NotificationPush`); para várias instâncias, troque por fan-out via RabbitMQ.
- O gateway não deve comprimir (`encode`) nem bufferizar `/api/notificacoes/stream`.

## Tabelas (`V1__notification.sql`)

`actor_snapshot` (cópia pública dos perfis), `follow_edge`/`follow_removed` (arestas aceitas e túmulos, só para o D7),
`notification_group` (um grupo por destinatário e chave) + `notification_actor` (um ator por linha), a sequence
`notification_activity_seq`, `outbox_event` e `processed_message` (exigidos pela biblioteca de eventos).

## Configuração (variáveis de ambiente)

| Variável | Padrão | Descrição |
| --- | --- | --- |
| `NOTIFICATION_DB_URL` / `NOTIFICATION_DB_NAME` | `jdbc:postgresql://localhost:5432/notification` / `notification` | Banco do serviço |
| `NOTIFICATION_DB_USER` / `NOTIFICATION_DB_PASSWORD` | `IDENTITY_DB_USER` / `IDENTITY_DB_PASSWORD` | Credenciais (mesmo role do auth, por padrão) |
| `AUTH_JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_CLOCK_SKEW` | iguais aos outros serviços | Validação do token de usuário |
| `RABBITMQ_HOST/PORT/USER/PASSWORD`, `EVENTS_EXCHANGE`, `NOTIFICATION_EVENTS_ENABLED` | `localhost`… / `true` | Broker e eventos |
| `NOTIFICATION_ALLOWED_ORIGINS` | vazio (= própria origem) | `OriginCheckFilter` (CSRF) |
| `NOTIFICATION_SSE_MAX_DURATION` / `NOTIFICATION_SSE_JITTER` | `PT15M` / `PT1M` | Duração máxima do stream (D9) |
| `NOTIFICATION_SSE_HEARTBEAT` / `NOTIFICATION_SSE_SWEEP_INTERVAL` | `PT25S` / `PT5S` | Ping e varredura dos streams |
| `NOTIFICATION_SSE_MAX_PER_USER` / `NOTIFICATION_SSE_MAX_TOTAL` / `NOTIFICATION_SSE_BACKLOG` | `5` / `5000` / `50` | Limites |
| `NOTIFICATION_MAINTENANCE_ENABLED` / `NOTIFICATION_MAINTENANCE_INTERVAL` | `true` / `PT1H` | Rotinas agendadas |
| `NOTIFICATION_RETENTION` / `NOTIFICATION_TOMBSTONE_RETENTION` | `P45D` / `P7D` | Retenção das notificações (D13) e dos túmulos |
| `SERVER_PORT` | `8085` | Porta |

## Rodando e testando

```bash
scripts/dev/run-notification.sh          # ou .ps1, na raiz: usa o .env, instala a biblioteca e sobe na porta 8085
mvn -pl notification_service -am test    # na raiz: PostgreSQL embutido, JWKS local, cliente SSE real (sem Docker)
```

## Limites conhecidos

- A **revogação imediata** do token (logout geral, troca de senha) é aplicada pelo gateway (`forward_auth`) ao abrir o stream; um
  stream já aberto continua até 15 min (ou até o `exp`). Não exponha o serviço sem o gateway.
- Uma instância: os streams ficam em memória.
- Usuários e relações que existiam antes do serviço não têm cópia local (sem bootstrap): em desenvolvimento, recrie os volumes ou
  republique os eventos. Uma resposta cujo autor ainda não tem cópia do perfil não notifica (D7: na dúvida, não notifica).
- Só a primeira resposta de um mesmo ator a uma postagem entra no grupo `REPLY` (D5).
- A consumidora RabbitMQ só foi exercitada nos testes com os tratadores chamados direto, não contra um RabbitMQ real.
