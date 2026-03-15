# TODO

## Modo de Execução AI-First

Este plano prioriza execução por IA com passos determinísticos, artefatos verificáveis e gates automáticos.

### Regras de execução

- [ ] Executar tarefas na ordem dos IDs (`A00` a `A29`).
- [ ] Não iniciar próxima tarefa sem satisfazer o gate da tarefa atual.
- [ ] Se o gate falhar, abrir sub-tarefa `FIX-*` no mesmo bloco e resolver antes de prosseguir.
- [ ] Toda alteração deve indicar arquivo-alvo explícito.
- [ ] Toda fase deve terminar com comando de validação executável.

## Fase 0: Baseline e Contrato

### A00 - Confirmar baseline de build backend

- [x] Entradas: código atual do backend.
- [x] Ação: compilar módulo Java sem testes.
- [x] Arquivo-alvo: nenhum.
- [x] Comando-gate: `mvn -q -f services/queue-platform/pom.xml -DskipTests compile`.
- [x] Saída esperada: build concluído sem erro.

### A01 - Confirmar baseline de build frontend

- [x] Entradas: código atual do frontend.
- [x] Ação: build de produção do web console.
- [x] Arquivo-alvo: nenhum.
- [x] Comando-gate: `npm --prefix apps/web-console run build`.
- [x] Saída esperada: build concluído sem erro.

### A02 - Fixar contrato de evento v1 no código

- [x] Entradas: seção 17 de docs/proposta-projeto-demonstrativo.md.
- [x] Ação: criar tipos/DTOs do envelope `queue.events.v1` no backend.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/core (novo pacote de eventos).
- [x] Gate: classe de envelope compilando e serializando JSON com todos os campos obrigatórios.

### A03 - Teste unitário do envelope

- [x] Entradas: classe de envelope da tarefa A02.
- [x] Ação: criar teste com validação dos campos obrigatórios e versionamento.
- [x] Arquivo-alvo: services/queue-platform/src/test/java/com/wedocode/queuelab/core.
- [x] Comando-gate: `mvn -q -f services/queue-platform/pom.xml test`.
- [x] Saída esperada: teste do envelope verde.

## Fase 1: Publicação WebSocket pós-commit

### A10 - Infra de WebSocket no backend

- [x] Entradas: app API atual.
- [x] Ação: adicionar canal de broadcast `queue.events.v1`.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/api.
- [x] Gate: endpoint WebSocket inicializa sem quebrar endpoints HTTP existentes.

### A11 - Publicar `job.created` pós-commit

- [x] Entradas: fluxo de enqueue.
- [x] Ação: emitir evento somente após commit bem-sucedido.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/core/QueueService.java.
- [x] Gate: rollback não publica evento.

### A12 - Publicar `job.completed` e `job.failed` pós-commit

- [x] Entradas: fluxo do worker.
- [x] Ação: emitir eventos de finalização após persistência de status.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/worker.
- [x] Gate: cada mudança de estado gera exatamente um evento.

### A13 - Snapshot HTTP consolidado

- [x] Entradas: consultas operacionais existentes.
- [x] Ação: expor `GET /api/dashboard/snapshot` com estado oficial.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/api.
- [x] Gate: resposta consistente com o estado do banco.

### A14 - Validação fase 1 (integração)

- [x] Entradas: A10-A13 concluídas.
- [x] Ação: testar enqueue + processamento + evento + snapshot.
- [x] Comando-gate: `mvn -q -f services/queue-platform/pom.xml test`.
- [x] Saída esperada: testes de integração verdes para fase 1.

## Fase 2: Cliente em tempo real com reconciliação

### A20 - Cliente WebSocket no frontend

- [x] Entradas: dashboard atual.
- [x] Ação: conectar ao canal `queue.events.v1` com reconexão automática.
- [x] Arquivo-alvo: apps/web-console/src/lib (cliente de eventos).
- [x] Gate: estado visual de conexão (connected/reconnecting/disconnected).

### A21 - Aplicar eventos na UI

- [x] Entradas: cliente WebSocket.
- [x] Ação: refletir `job.created`, `job.completed`, `job.failed` em tela sem reload.
- [x] Arquivo-alvo: apps/web-console/src/components.
- [x] Gate: contadores e listas atualizam em tempo real.

### A22 - Deduplicação e ordenação por versão

- [x] Entradas: envelope com `eventId` e `jobVersion`.
- [x] Ação: ignorar duplicados e eventos antigos por job.
- [x] Arquivo-alvo: apps/web-console/src/lib.
- [x] Gate: evento duplicado não altera estado.

### A23 - Reconciliação por snapshot de job

- [x] Entradas: lacuna de versões detectada.
- [x] Ação: chamar `GET /api/jobs/{jobId}` e corrigir estado local.
- [x] Arquivo-alvo: apps/web-console/src/lib/api.ts e consumidor de eventos.
- [x] Gate: após lacuna simulada, estado converge para snapshot oficial.

### A24 - Fallback por polling

- [x] Entradas: indisponibilidade de WebSocket.
- [x] Ação: manter polling periódico de snapshot.
- [x] Arquivo-alvo: apps/web-console/src/App.tsx.
- [x] Gate: desligar WebSocket não paralisa atualização da UI.

### A25 - Validação fase 2 (frontend)

- [x] Entradas: A20-A24 concluídas.
- [x] Ação: executar build e testes do frontend.
- [x] Comando-gate: `npm --prefix apps/web-console run build`.
- [x] Saída esperada: build verde com fluxo real-time funcional.

## Fase 3: Confiabilidade (outbox)

### A30 - Migration de outbox

- [x] Entradas: esquema atual de banco.
- [x] Ação: criar migration da tabela de outbox com índices.
- [x] Arquivo-alvo: database/migrations (novo arquivo `V00X__event_outbox.sql`).
- [x] Gate: migration aplica sem erro.

### A31 - Persistir eventos na outbox

- [x] Entradas: fluxos de publicação da fase 1.
- [x] Ação: gravar evento no commit da transação (não enviar direto no ponto crítico).
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/core.
- [x] Gate: eventos pendentes aparecem na outbox após operações.

### A32 - Publisher assíncrono da outbox

- [x] Entradas: tabela outbox.
- [x] Ação: processo dedicado para enviar eventos e marcar status (`pending/sent/failed`).
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/worker.
- [x] Gate: falha temporária não perde evento definitivamente.

### A33 - Replay curto opcional

- [x] Entradas: cursor de evento.
- [x] Ação: expor `GET /api/events/since?cursor=...`.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/api.
- [x] Gate: replay retorna apenas eventos posteriores ao cursor.

### A34 - Validação fase 3

- [x] Entradas: A30-A33 concluídas.
- [x] Ação: simular indisponibilidade de WebSocket e validar recuperação.
- [x] Comando-gate: `mvn -q -f services/queue-platform/pom.xml test`.
- [x] Saída esperada: eventos entregues após retomada, sem inconsistência final.

## Gates Finais de Conclusão

- [x] G01 Backend: `mvn -q -f services/queue-platform/pom.xml test` verde.
- [x] G02 Frontend: `npm --prefix apps/web-console run build` verde.
- [x] G03 End-to-end: API saudável (`/api/health`) e dashboard refletindo atualização por evento.
- [x] G04 Documentação: contrato e plano sincronizados com implementação.

## Revisão da Última Execução

- [x] Preencher somente ao final da execução automática.
- [x] Registrar tarefas concluídas por ID.
- [x] Registrar falhas por gate e ações corretivas aplicadas.
- [x] Tarefas concluídas nesta execução: A10-A14, A20-A25, A30-A34, G01-G04.
- [x] Falhas encontradas e corrigidas: assinatura de handlers WS do Javalin, verificação de sessão WS e SQL de enqueue com `job_version`.
## Revisão da Correção de Lacunas (2026-03-15)

- [x] FIX-C01 - Corrigir replay curto por cursor no frontend com persistência local (`localStorage`) e reconexão.
- [x] FIX-C02 - Incluir `outboxId` no payload do canal WebSocket para permitir avanço de cursor em tempo real.
- [x] FIX-C03 - Expor métricas operacionais de eventos no backend (`/api/observability/events`).
- [x] FIX-C04 - Reforçar fallback por polling quando canal WebSocket não estiver conectado.
- [x] Validação backend: `mvn -q -f services/queue-platform/pom.xml test`.
- [x] Validação frontend: `npm --prefix apps/web-console run build`.
