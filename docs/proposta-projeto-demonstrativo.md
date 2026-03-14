# Proposta de Projeto Demonstrativo: Plataforma de Filas com PostgreSQL

## 1. Visao Geral

Proposta de um projeto demonstrativo para validar e comunicar a arquitetura descrita em `filas-usando-postgres.md`, mostrando que o Postgres 17 pode operar como mecanismo de fila confiavel para cenarios assincronos de volume moderado.

O projeto teria carater de laboratorio operacional, com foco em visualizacao, simulacao de carga e demonstracao de comportamento em situacoes reais de processamento:

- enfileiramento transacional
- wake-up via `LISTEN/NOTIFY`
- consumo concorrente com `FOR UPDATE SKIP LOCKED`
- retry com backoff exponencial
- DLQ logica
- recuperacao de jobs presos
- observabilidade da fila em tempo real

## 2. Nome Sugerido

**QueueLab PG**

Um ambiente demonstrativo para operar, observar e testar filas implementadas sobre PostgreSQL.

## 3. Objetivo do Projeto

O software deve permitir que uma equipe tecnica consiga:

1. Visualizar o estado das filas e dos workers em tempo real.
2. Simular produtores enviando mensagens com perfis diferentes de carga.
3. Demonstrar retries, backoff, falhas permanentes e DLQ.
4. Validar concorrencia segura entre varios workers.
5. Evidenciar o papel do `LISTEN/NOTIFY` como acelerador de wake-up, sem depender dele para garantia de entrega.
6. Servir como base de prova de conceito para futuras implementacoes produtivas.

## 4. Escopo da Solucao

O projeto seria composto por quatro modulos principais.

### 4.1 Aplicativo Web de Monitoramento

Aplicacao web em React voltada a operacao e observabilidade da fila.

Responsabilidades:

- exibir filas cadastradas e seus volumes por status
- mostrar jobs em `PENDING`, `PROCESSING`, `RETRY`, `DONE` e `FAILED`
- mostrar tempo medio de espera e tempo medio de processamento
- exibir detalhes de cada job: payload, tentativas, erro mais recente, worker responsavel, datas importantes
- permitir filtros por fila, status, periodo, `dedup_key` e worker
- destacar jobs presos em `PROCESSING` acima do timeout configurado
- listar jobs em DLQ logica (`FAILED`)
- oferecer acoes operacionais controladas, como reprocessar job, reenfileirar DLQ e acionar reconciliacao manual

### 4.2 Aplicativo Simulador de Envio de Mensagens

Aplicacao em React voltada a testes e demonstracoes de producao de mensagens.

Responsabilidades:

- enviar mensagens para uma ou mais filas
- gerar payloads validos e payloads propositalmente invalidos
- definir taxa de envio por segundo
- simular cargas em lote e cargas continuas
- controlar percentual de mensagens que falham no processamento
- configurar `dedup_key` para demonstrar idempotencia e deduplicacao
- enviar mensagens com `available_at` futuro para demonstrar agendamento
- disparar cenarios prontos, como pico de carga, falha transitoria e falha permanente

### 4.3 Backend e Worker de Processamento

Servico em Java responsavel por expor a API de enfileiramento com Javalin e consumir a fila com o protocolo descrito na arquitetura.

Responsabilidades:

- expor endpoints HTTP para enfileiramento, consultas operacionais e acoes administrativas
- abrir conexao dedicada para `LISTEN job_queue_new`
- executar polling fallback periodico
- fazer claim atomico com `FOR UPDATE SKIP LOCKED`
- processar jobs de forma idempotente
- confirmar sucesso com `DONE`
- reagendar falhas transitorias com `RETRY`
- mover falhas definitivas para `FAILED`
- registrar metricas e eventos operacionais

### 4.4 Banco Postgres 17 e Objetos SQL

Camada central da demonstracao.

Responsabilidades:

- armazenar a tabela `job_queue`
- manter indices de picking, deduplicacao e recuperacao
- executar trigger de `pg_notify`
- armazenar DLQ historica opcional em `job_queue_dlq`
- armazenar trilha de execucao para enriquecer o monitoramento

## 5. Cenário Demonstrativo Recomendado

Sugestao de dominio: **plataforma de notificacoes**.

Cada mensagem representa uma solicitacao de envio de notificacao, por exemplo:

- email de confirmacao
- lembrete de consulta
- aviso de vencimento
- notificacao push
- mensagem WhatsApp simulada

Esse dominio funciona bem porque e simples de explicar, permite falhas realistas e evidencia a necessidade de retry, idempotencia e observabilidade.

## 6. Arquitetura Proposta

```text
[Simulador de Mensagens]
        |
        v
[API Javalin]
        |
        v
[Postgres 17: job_queue + NOTIFY]
        |
        +-----------------------> [Aplicativo Web de Monitoramento]
        |
        v
[Workers Concorrentes em Java]
        |
        v
[Processador Simulado de Notificacoes]
```

### 6.1 Papel de cada componente

- O simulador representa produtores de mensagens.
- A API Javalin encapsula validacao, insercao transacional, deduplicacao e endpoints operacionais.
- O Postgres 17 persiste a fila e emite sinais de wake-up.
- Os workers em Java fazem o consumo concorrente seguro.
- O monitor web consulta dados operacionais e exibe o estado do sistema.

## 7. Proposta Funcional por Aplicativo

## 7.1 Aplicativo Web de Monitoramento

### Telas principais

**Dashboard Geral**

- total de jobs por status
- backlog por fila
- taxa de processamento por minuto
- taxa de erro por minuto
- quantidade de retries
- quantidade de jobs em DLQ
- numero de workers ativos

**Fila em Tempo Real**

- lista de jobs ordenados por `available_at`
- atualizacao automatica por polling curto ou websocket
- filtros por status e fila

**Detalhe do Job**

- payload completo
- historico de tentativas
- datas de criacao, claim e ultima atualizacao
- ultimo erro
- botao de reenfileirar ou marcar para reprocessamento

**Painel de Workers**

- workers ativos
- ultimo heartbeat
- quantidade de jobs processados por worker
- tempo medio de processamento por worker

**Painel de DLQ**

- jobs com falha definitiva
- motivo da falha
- tentativas executadas
- acao de reenfileirar individualmente ou em lote

### Valor demonstrativo

O monitor web torna visiveis os efeitos da arquitetura. Sem esse modulo, a fila funciona, mas o aprendizado operacional fica limitado.

## 7.2 Aplicativo Simulador de Mensagens

### Modos de operacao

**Modo Manual**

- usuario preenche `queue_name`, payload, `dedup_key` e agenda envio

**Modo Carga Controlada**

- define mensagens por segundo
- define duracao do teste
- define distribuicao de tipos de payload

**Modo Cenario**

- cenario de sucesso total
- cenario com falhas transitorias
- cenario com falhas permanentes
- cenario com duplicidade de mensagens
- cenario com jobs agendados para o futuro

### Valor demonstrativo

O simulador permite reproduzir comportamentos de negocio e de falha sem depender de sistemas externos reais.

## 8. Requisitos Tecnicos do Projeto

## 8.1 Requisitos Minimos

1. Insercao transacional de mensagens na tabela de fila.
2. Trigger de `pg_notify` para acordar workers.
3. Claim concorrente com `FOR UPDATE SKIP LOCKED`.
4. Retry com backoff exponencial.
5. Marcacao de falha definitiva em `FAILED`.
6. Reconciliacao de jobs presos em `PROCESSING`.
7. Consultas de observabilidade para backlog, throughput e falhas.
8. Painel web com visao operacional minima.
9. Simulador com cargas parametrizaveis.

## 8.2 Requisitos Recomendados

1. Heartbeat de worker em tabela propria.
2. Historico de execucao por job.
3. Endpoint para reenfileiramento de DLQ.
4. Exportacao de metricas em formato Prometheus.
5. Script de demonstracao com cenarios reproduziveis.

## 9. Modelo de Dados Estendido

Além da tabela `job_queue` proposta na arquitetura base, vale incluir tabelas auxiliares para melhorar a experiencia demonstrativa.

### 9.1 Tabela de workers

```sql
CREATE TABLE worker_registry (
  worker_id TEXT PRIMARY KEY,
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_heartbeat_at TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL,
  processed_count BIGINT NOT NULL DEFAULT 0,
  failed_count BIGINT NOT NULL DEFAULT 0
);
```

### 9.2 Tabela de historico de execucao

```sql
CREATE TABLE job_execution_history (
  execution_id BIGSERIAL PRIMARY KEY,
  job_id BIGINT NOT NULL,
  worker_id TEXT NOT NULL,
  attempt_number INT NOT NULL,
  outcome TEXT NOT NULL,
  error_message TEXT,
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ NOT NULL
);
```

Essas tabelas nao sao obrigatorias para a arquitetura funcionar, mas sao muito uteis para o painel de monitoramento e para a narrativa demonstrativa.

## 10. Jornada de Demonstracao

O projeto deve permitir uma apresentacao pratica em etapas.

### Etapa 1. Fluxo feliz

1. Enviar 20 mensagens pelo simulador.
2. Mostrar o surgimento das mensagens em `PENDING`.
3. Observar workers acordando por `NOTIFY`.
4. Ver jobs transitando para `PROCESSING` e `DONE`.

### Etapa 2. Concorrencia segura

1. Subir 3 ou mais workers.
2. Enviar lote maior de mensagens.
3. Mostrar distribuicao dos jobs entre workers.
4. Evidenciar ausencia de processamento duplicado.

### Etapa 3. Retry e backoff

1. Configurar o simulador para introduzir falhas transitorias.
2. Mostrar jobs mudando para `RETRY`.
3. Exibir `available_at` sendo reagendado.
4. Confirmar que o job volta ao processamento no tempo esperado.

### Etapa 4. DLQ

1. Enviar mensagens com falha permanente.
2. Levar o job ao limite de tentativas.
3. Mostrar o status final `FAILED`.
4. Reenfileirar manualmente pelo monitor web.

### Etapa 5. Recuperacao

1. Derrubar um worker durante o processamento.
2. Mostrar jobs presos em `PROCESSING`.
3. Executar reconciliacao.
4. Confirmar retorno para `RETRY` e retomada do fluxo.

## 11. Stack Definida

Para este projeto, a stack fica definida da seguinte forma:

- Backend/API: Java com Javalin
- Worker: Java, compartilhando o mesmo modulo de dominio e acesso a dados do backend
- Frontend: React
- Banco: Postgres 17
- Observabilidade: Prometheus opcional, com possibilidade de dashboard em Grafana

### 11.1 Implicacoes praticas da stack

- Javalin e adequado para expor endpoints pequenos e diretos para enfileiramento, consulta de jobs, DLQ e acoes operacionais.
- O worker pode reutilizar o mesmo codigo Java de repositorio, modelo de fila e regras de retry, reduzindo duplicacao.
- React atende bem tanto o monitor operacional quanto o simulador, podendo inclusive reunir os dois modulos em uma unica aplicacao web com rotas separadas.
- Postgres 17 permanece como centro da arquitetura, com suporte aos mecanismos descritos no documento base.

### 11.2 Responsabilidades por camada

**Backend Java + Javalin**

- endpoints para criar mensagens
- endpoints para consultar filas, jobs e metricas
- endpoints administrativos para reenfileirar, reconciliar e inspecionar DLQ
- servicos de dominio para claim, ack, retry e recovery

**Frontend React**

- interface de monitoramento operacional
- interface de simulacao manual e de carga
- visualizacao em tempo real por polling curto

**Postgres 17**

- persistencia da fila
- `LISTEN/NOTIFY`
- indices e constraints de deduplicacao
- queries de observabilidade

## 12. Estrutura Sugerida do Repositorio

```text
docs/
apps/
  web-console/
services/
  queue-platform/
database/
  migrations/
  queries/
infra/
  docker-compose.yml
```

Nesta stack, a melhor decisao e manter monitor e simulador no mesmo frontend React, com duas areas distintas. Isso reduz custo de desenvolvimento sem perder valor demonstrativo.

## 13. Diferencial do Projeto

Este projeto nao seria apenas um exemplo de CRUD sobre tabela de fila. O diferencial esta em tornar explicitos os pontos que normalmente ficam ocultos em arquiteturas assincronas:

- quem produziu a mensagem
- quando ela entrou na fila
- quando foi capturada por um worker
- por que falhou
- quando sera tentada novamente
- quando virou DLQ
- qual worker processou cada tentativa

Esse carater demonstrativo faz o projeto ser util para:

- treinamento interno
- prova de conceito arquitetural
- onboarding tecnico
- validacao de capacidade operacional antes de producao

## 14. MVP Recomendado

Primeira entrega com escopo controlado:

1. Tabela `job_queue` e trigger `NOTIFY`.
2. API para enfileirar mensagens.
3. Um worker funcional com retry e DLQ.
4. Tela web com dashboard e lista de jobs.
5. Simulador capaz de gerar mensagens de sucesso e falha.

Isso ja basta para demonstrar o nucleo da arquitetura.

## 15. Evolucoes Posteriores

1. Multiplas filas com prioridades distintas.
2. Regras de retenção e expurgo de `DONE`.
3. Reprocessamento em lote de DLQ.
4. Autenticacao e perfis de acesso no monitor.
5. Testes de carga automatizados.
6. Exportacao de relatorios operacionais.
7. Chaos testing de workers e conexoes.

## 16. Conclusao

A melhor forma de demonstrar a arquitetura proposta e construir uma plataforma pequena, mas operacionalmente completa, com dois aplicativos visiveis para o usuario final:

- um aplicativo web de monitoramento para tornar o comportamento da fila observavel
- um aplicativo simulador para gerar trafego, falhas e cenarios reproduziveis

Completando esses dois aplicativos com uma API em Java + Javalin, workers concorrentes em Java e o modelo SQL sobre Postgres 17, o projeto passa a mostrar nao apenas que PostgreSQL pode sustentar filas com seguranca, mas tambem como operar esse modelo com previsibilidade.