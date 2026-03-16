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

## Fase VT: Planejamento para Virtual Threads (Java 21)

### VT00 - Levantar baseline de concorrência atual

- [x] Entradas: runtime de workers, relay da outbox, processor de jobs e hooks de shutdown.
- [x] Ação: mapear uso atual de threads de plataforma e pontos de bloqueio.
- [x] Arquivo-alvo: documentação deste plano.
- [x] Gate: inventário completo com decisões por componente.

### VT01 - Definir estratégia de migração por componente

- [x] Entradas: inventário VT00 e restrições de arquitetura da fila com PostgreSQL.
- [x] Ação: decidir o que migrar para virtual thread imediatamente e o que manter em thread dedicada.
- [x] Arquivo-alvo: documentação deste plano.
- [x] Gate: decisão explícita por componente com justificativa técnica.

### VT10 - Migrar execução dos workers para virtual threads

- [x] Entradas: `WorkerRuntime` e configuração de quantidade de workers.
- [x] Ação: substituir criação manual de `Thread` por fábrica/executor de virtual threads para loops de workers.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/worker/WorkerRuntime.java.
- [x] Gate: workers inicializam e processam jobs mantendo semântica de claim e retry.

### VT11 - Revisar listener de LISTEN/NOTIFY

- [x] Entradas: loop de notificação PostgreSQL no `WorkerRuntime`.
- [x] Ação: manter listener em execução dedicada previsível ou migrar para virtual thread isolada com estratégia de reconexão.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/worker/WorkerRuntime.java.
- [x] Gate: wake-up de workers continua funcional após reconexões e interrupções.

### VT12 - Migrar scheduler do relay da outbox

- [x] Entradas: `OutboxRelay` com `ScheduledExecutorService` single-thread.
- [x] Ação: adotar scheduler compatível com virtual threads para execução de lotes sem regressão.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/api/OutboxRelay.java.
- [x] Gate: relay continua publicando, marcando `sent/failed` e atualizando métricas.

### VT13 - Ajustar hooks de shutdown e ciclo de vida

- [x] Entradas: hooks em `ApiApplication` e `WorkerApplication`.
- [x] Ação: garantir desligamento ordenado de executores virtuais e término limpo da aplicação.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/api/ApiApplication.java, services/queue-platform/src/main/java/com/wedocode/queuelab/worker/WorkerApplication.java.
- [x] Gate: stop gracioso sem threads órfãs e sem perda de consistência de status.

### VT14 - Revisar processor para interrupção e bloqueios

- [x] Entradas: `NotificationJobProcessor` e uso de sleeps/bloqueios.
- [x] Ação: validar que interrupções e falhas são tratadas sem mascarar cancelamento em virtual threads.
- [x] Arquivo-alvo: services/queue-platform/src/main/java/com/wedocode/queuelab/worker/NotificationJobProcessor.java.
- [x] Gate: interrupção preservada e comportamento determinístico sob cancelamento.

### VT20 - Adicionar testes de regressão de concorrência

- [x] Entradas: cenários de claim concorrente, retry e reconciliação.
- [x] Ação: criar/ajustar testes cobrindo processamento concorrente com virtual threads.
- [x] Arquivo-alvo: services/queue-platform/src/test/java/com/wedocode/queuelab/worker (novos testes) e testes de integração relevantes.
- [x] Gate: testes capturam regressão de duplicidade de processamento e ordem de transições.

### VT21 - Validar benchmark mínimo comparativo

- [x] Entradas: script de benchmark existente.
- [x] Ação: executar cenário comparando baseline atual versus versão com virtual threads.
- [x] Arquivo-alvo: artefatos gerados em `.tmp/capacity-benchmark-*`.
- [x] Comando-gate: `WORKER_THREADS_LIST='2 4 8' TARGET_RATES='5 10 15' WARMUP_SECONDS='5' MEASURE_SECONDS='20' SAMPLE_INTERVAL_SECONDS='5' DB_RESET_MODE='required' PSQL_PATH='/Users/mrcdom/Works/services/pgsql17/bin/psql' API_PORT='7080' QUEUE_TRANSPORT='postgres' ./scripts/run-capacity-benchmark-ai.sh`.
- [x] Saída esperada: relatório comparativo com throughput, latência e taxa de falhas.

### VT22 - Validação final e documentação

- [x] Entradas: tarefas VT10-VT21 concluídas.
- [x] Ação: validar build/test e registrar impacto técnico da migração.
- [x] Arquivo-alvo: docs/planejamento.md (se necessário), tasks/todo.md (revisão final).
- [x] Comando-gate: `mvn -q -f services/queue-platform/pom.xml test`.
- [x] Saída esperada: suíte verde e critérios de sucesso documentados.

## Critérios de Sucesso da Fase VT

- [x] Não há regressão funcional no fluxo de enqueue, claim, processamento, retry, DLQ e outbox.
- [x] Desligamento da aplicação permanece previsível e sem vazamento de execução.
- [ ] Ganho de eficiência observado em pelo menos um cenário de benchmark sem perda de estabilidade.

## Revisão da Execução VT (2026-03-16)

- [x] Tarefas concluídas nesta execução: VT00, VT01, VT10, VT11, VT12, VT13, VT14, VT20, VT21, VT22.
- [x] Validação executada: `mvn -q -f services/queue-platform/pom.xml test`.
- [x] Benchmark executado: `.tmp/capacity-benchmark-20260316-122254`.
- [x] Resultado benchmark: capacidade segura em workers=8, rate=15, throughput=15.6500, failed_rate=0.
- [x] Comparação com baseline equivalente (`.tmp/capacity-benchmark-20260316-090314`): resultado estável, sem ganho mensurável adicional.
- [ ] Pendência de produto: investigar tuning para ganho de throughput (batch, latência simulada, DB pool e configuração de claim).
