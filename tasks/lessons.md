# Lessons

- Preferir plano AI-first quando o usuário pedir planejamento: tarefas atômicas, IDs sequenciais, entradas/saídas objetivas, arquivo-alvo explícito e gate automatizável por comando.
- Evitar plano orientado a leitura humana quando a solicitação for execução por IA.
- Em arquitetura híbrida WS + outbox, o cursor de replay precisa avançar também no fluxo em tempo real (não apenas no endpoint `events/since`), senão a reconexão perde capacidade de catch-up determinístico.
- Quando o canal de eventos estiver desconectado/reconectando, manter polling periódico explícito evita janelas longas de inconsistência visual no monitor.
- Ao gerar código Java, priorizar estilo e recursos modernos da plataforma conforme Java 21 (legibilidade, segurança de tipos, APIs atuais e práticas idiomáticas), evitando padrões legados quando houver alternativa moderna estável.
