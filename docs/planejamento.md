
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