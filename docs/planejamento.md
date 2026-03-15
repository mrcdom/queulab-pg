
## Orquestração do Fluxo de Trabalho
### 1. Modo de Planejamento por Padrão
- Entre em modo de planejamento para QUALQUER tarefa não trivial (3 ou mais etapas ou decisões arquiteturais). Se algo sair do rumo, PARE e replaneje imediatamente; não continue insistindo. Use o modo de planejamento também para etapas de verificação, não apenas de implementação.
- Escreva especificações detalhadas logo no início para reduzir ambiguidades.
### 2. Estratégia de Subagentes
- Use subagentes com frequência para manter a janela principal de contexto limpa.
- Delegue pesquisas, exploração e análises paralelas para subagentes.
Para problemas complexos, use mais capacidade de processamento por meio de subagentes.
- Uma tarefa por subagente para garantir execução focada.
### 3. Ciclo de Autoaperfeiçoamento
- Após QUALQUER correção do usuário: atualize `tasks/lessons.md` com o padrão observado.
- Escreva regras para si mesmo que impeçam o mesmo erro.
- Reitere essas lições com rigor até a taxa de erro cair.
- Revise as lições no início da sessão para o projeto relevante.
### 4. Verificação Antes de Concluir
- Nunca marque uma tarefa como concluída sem provar que ela funciona.
Compare o comportamento entre a main e suas alterações quando isso for relevante.
Pergunte a si mesmo: "Um engenheiro sênior aprovaria isso?"
- Execute testes, verifique logs e demonstre a correção.
### 5. Exigir Elegância com Equilíbrio
Para mudanças não triviais, pare e pergunte: "Existe uma forma mais elegante de fazer isso?"
- Se uma correção parecer gambiarra: "Sabendo tudo o que sei agora, implemente a solução elegante."
- Ignore isso em correções simples e óbvias; não faça engenharia em excesso.
- Questione seu próprio trabalho antes de apresentá-lo.
### 6. Correção Autônoma de Bugs
- Ao receber um relato de bug: apenas corrija. Não peça que o usuário conduza o processo.
Observe logs, erros e testes falhando, então resolva o problema.
- Nenhuma troca de contexto deve ser exigida do usuário.
- Corrija testes quebrados no CI sem precisar que digam como.
## Gerenciamento de Tarefas
1. **Planeje primeiro**: escreva o plano em `tasks/todo.md` com itens verificáveis.
2. **Verifique o plano**: alinhe antes de iniciar a implementação.
3. **Acompanhe o progresso**: marque os itens como concluídos conforme avança.
4. **Explique as mudanças**: forneça um resumo de alto nível a cada etapa.
5. **Documente os resultados**: adicione uma seção de revisão em `tasks/todo.md`.
6. **Registre aprendizados**: atualize `tasks/lessons.md` após correções.
## Princípios Centrais
**Simplicidade em primeiro lugar**: faça cada mudança da forma mais simples possível. Afete o mínimo de código. **Sem preguiça**: encontre as causas-raiz. Nada de correções temporárias. Padrão de desenvolvedor sênior. **Impacto mínimo**: as mudanças devem tocar apenas o necessário. Evite introduzir bugs.

## Plano Técnico Executável: Eventos Híbridos (Postgres + WebSocket)

### Objetivo

Implementar atualização em tempo real para monitor e simulador com WebSocket, mantendo Postgres como fonte de verdade do estado da fila e LISTEN/NOTIFY como wake-up de workers.

### Escopo de Entrega

1. Publicação de eventos de fila após commit de transação.
2. Consumo WebSocket no frontend com fallback por polling.
3. Regras de deduplicação e ordenação por versão do job.
4. Endpoints de reconciliação para corrigir divergências no cliente.
5. Métricas básicas de qualidade do canal de eventos.

### Fase 1: Base do Contrato e Fluxo Feliz

#### Backlog Backend (services/queue-platform)

1. Criar modelo de envelope `queue.events.v1` com campos obrigatórios:
	`eventId`, `eventType`, `eventVersion`, `occurredAt`, `source`, `correlationId`, `queueName`, `jobId`, `jobVersion`, `payload`.
2. Adicionar publisher WebSocket no backend com broadcast para clientes conectados.
3. Publicar somente os eventos:
	`job.created`, `job.completed`, `job.failed`.
4. Garantir publicação de evento somente após commit da transação.
5. Expor endpoint `GET /api/dashboard/snapshot` para estado consolidado.

#### Backlog Frontend (apps/web-console)

1. Criar cliente WebSocket para canal `queue.events.v1`.
2. Atualizar dashboard em tempo real para os eventos da fase 1.
3. Manter polling periódico como fallback se WebSocket cair.
4. Exibir estado de conexão do canal em UI (connected, reconnecting, disconnected).

#### Backlog Banco

1. Validar incremento de `jobVersion` a cada transição de estado.
2. Confirmar que transições existentes continuam consistentes com status da fila.

#### Checklist de Execução Fase 1

1. Envelope documentado e usado em todos os eventos da fase.
2. Eventos emitidos apenas após commit.
3. Dashboard atualizado por evento sem refresh manual.
4. Fallback por polling ativo quando WebSocket indisponível.
5. Testes de integração cobrindo criação e finalização de job.

#### Critérios de Aceite Fase 1

1. Ao criar job, UI reflete novo item em até 2 segundos.
2. Ao concluir job, UI atualiza status sem reload.
3. Em desconexão WebSocket, polling mantém dados consistentes.
4. Não existem eventos sem `jobId` ou `jobVersion`.

### Fase 2: Operacional, Ordenação e Reconciliação

#### Backlog Backend (services/queue-platform)

1. Publicar eventos adicionais:
	`job.claimed`, `job.retry_scheduled`, `job.recovered`, `job.requeued`.
2. Incluir `correlationId` em todo fluxo API -> worker -> evento.
3. Expor endpoint `GET /api/jobs/{jobId}` para snapshot oficial por job.
4. Registrar métricas básicas:
	taxa de eventos publicados, falhas de publicação, conexões WebSocket ativas.

#### Backlog Frontend (apps/web-console)

1. Implementar deduplicação por `eventId`.
2. Implementar ordenação por job com `jobVersion` (ignorar versões antigas).
3. Detectar lacuna de versão e disparar reconciliação por `GET /api/jobs/{jobId}`.
4. Exibir indicadores de reconciliação no monitor.

#### Backlog Banco

1. Confirmar transições válidas de estado:
	`PENDING -> PROCESSING -> DONE/RETRY/FAILED`, `FAILED -> PENDING`.
2. Revisar índices de consulta para snapshot por job e dashboard.

#### Checklist de Execução Fase 2

1. Eventos de retry e recovery publicados com payload mínimo operacional.
2. Cliente ignora eventos duplicados.
3. Cliente ignora eventos fora de ordem com versão menor.
4. Reconciliação corrige divergência detectada pelo cliente.
5. Métricas de publicação e conexão expostas em endpoint de observabilidade.

#### Critérios de Aceite Fase 2

1. Em carga concorrente, UI não regressa estado de job por evento atrasado.
2. Em evento duplicado, contadores não são incrementados em duplicidade.
3. Em perda simulada de evento, reconciliação restaura consistência em até 5 segundos.
4. Logs permitem rastrear fluxo completo por `correlationId`.

### Fase 3: Confiabilidade de Entrega de Eventos

#### Backlog Backend (services/queue-platform)

1. Introduzir tabela de outbox para eventos após commit.
2. Criar publisher dedicado lendo outbox e enviando ao WebSocket.
3. Marcar status de envio no outbox (pending, sent, failed) com retries.
4. Expor endpoint opcional `GET /api/events/since?cursor=...` para replay curto.

#### Backlog Frontend (apps/web-console)

1. Implementar cursor local de eventos para recuperação rápida.
2. Consumir replay curto em reconexão, antes de snapshot completo.

#### Backlog Banco

1. Criar migration da tabela outbox e índices por cursor/tempo.
2. Definir política de retenção da outbox.

#### Checklist de Execução Fase 3

1. Outbox ativa e publisher desacoplado do fluxo síncrono da API.
2. Falhas temporárias de broadcast não perdem evento definitivamente.
3. Replay curto funcional e validado em reconexão.
4. Políticas de limpeza da outbox documentadas e testadas.

#### Critérios de Aceite Fase 3

1. Em indisponibilidade temporária de WebSocket, eventos são entregues após retomada.
2. Taxa de perda percebida no cliente tende a zero em cenário nominal.
3. Sistema mantém consistência de estado com reconciliação determinística.

### Plano de Testes por Fase

1. Testes unitários do envelope e validação de campos obrigatórios.
2. Testes de integração API + banco + publicação de evento.
3. Testes de frontend para deduplicação, ordenação e fallback.
4. Testes de caos controlado:
	queda de conexão WebSocket, atraso de evento, duplicidade e perda simulada.

### Riscos e Mitigações

1. Risco: UI tratar evento como verdade absoluta.
	Mitigação: snapshot HTTP sempre prevalece.
2. Risco: publicar evento antes de commit.
	Mitigação: política obrigatória de publish pos-commit e testes de rollback.
3. Risco: vazamento de dados sensíveis em payload.
	Mitigação: payload mínimo e mascaramento no publisher.
4. Risco: reconciliação gerar carga excessiva.
	Mitigação: backoff e limitação por job/canal.

### Definition of Done Global

1. Todas as fases têm checklist atendido e critérios de aceite comprovados.
2. Documentação de contrato de evento e operação atualizada.
3. Testes automatizados verdes no backend e frontend.
4. Demonstração funcional executada com fluxo feliz, retry, falha e reconciliação.