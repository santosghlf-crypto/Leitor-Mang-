# Auditoria funcional e visual — 7.2
Base: 7.1 (671f266). Revisão estática do código e fluxos; não é um teste visual em dispositivo.
## Mantidos
GeckoView, uBlock, isolamento por fonte, estante, favoritos, filtros, histórico, capas locais, tela cheia, ajustes de brilho/rotação, rolagem automática que para no fim, progresso por URL, capítulos anterior/próximo quando reconhecidos. Perfis declarativos permanecem como opção avançada, sem promessa universal.
## Cortes
- Novos downloads offline: somente funcionavam com todas as imagens carregadas, de mesma origem e formatos restritos; custo de suporte e cópias extras alto. Retirados comando, ponte de transferência e fetch; downloads existentes permanecem acessíveis/excluíveis em Ajustes > Downloads antigos. Nenhum arquivo existente é apagado na migração.
- Avanço automático: não é seguro inferir fim de conteúdo dinâmico. Mantém botões manuais e auto-scroll que para.
- Dois toques: conflito com zoom e gestos dos sites. Voltar mostra controles. Não se sobrepõe botão central.
- Preparação extra de imagens: sem evidência de ganho em dispositivo, deixa Gecko gerir decodificação. Cache de capas e ajustes de largura permanecem.
- Catálogo/lista de capítulos aparecem só quando há perfil configurado e comunicação com o leitor; sem menus vazios previsíveis. Não há catálogo universal.
## Design e usabilidade
Quatro abas, menu de leitura contextual, ajustes técnicos agrupados, uma ação para salvar obra com reconhecimento de título e fallback manual, perfis prontos aplicáveis sem preencher formulário de seletores, fonte bloqueada no formulário de obra vinculada, primeiro acesso prioriza fonte. Barras de leitura fora da área da página quando visíveis; tela cheia esconde ambas. Indicador de uBlock não promete proteção absoluta. Excluir fonte com obras vinculadas é bloqueado para evitar perda em cascata.
## Limitações
Ainda precisa teste visual em Android, inclusive tamanho de fonte, rotação, navegação e zoom. O APK continua dependente de chave persistente não configurada. Sem backup completo; não desinstalar com dados importantes. Recursos removidos preservam os dados existentes, quando instalação puder ser atualizada. Testes unitários não validam o Gecko em execução.