# TODO

## Escopo do MVP

- [x] Consolidar o escopo do MVP a partir de `docs/proposta-projeto-demonstrativo.md`.
- [x] Estruturar o repositório com `apps`, `services`, `database`, `infra` e `tasks`.
- [x] Implementar backend Java com Javalin para enfileiramento, consultas operacionais e ações administrativas.
- [x] Implementar worker Java com claim concorrente, retry, DLQ e reconciliação.
- [x] Implementar frontend React para monitoramento e simulador.
- [x] Adicionar migrações SQL e infraestrutura local com Postgres 17.
- [x] Validar a estrutura e a execução básica do projeto.

## Critérios de aceite

- API permite enfileirar mensagens e consultar estado operacional.
- Worker processa jobs com backoff exponencial e DLQ lógica.
- Frontend expõe dashboard, lista de jobs, DLQ, workers e simulador.
- Projeto sobe localmente com Docker Compose para o banco.

## Revisão

- Backend compilado com `mvn -q -DskipTests compile`.
- Teste unitário de `BackoffPolicy` executado com sucesso.
- Frontend validado com `npm run build`.
- Fluxo fim a fim contra Postgres em execução não foi exercitado nesta sessão.