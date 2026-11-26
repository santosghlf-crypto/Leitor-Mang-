# Koma 5.0 — GUT 4: biblioteca
- Fontes continuam sendo contextos de navegação isolados; Biblioteca guarda obras individuais com identificador, título, endereço de entrada, endereço de retomada, anotação de capítulo, favorito, status e progresso.
- Adicione manualmente ou use o menu Salvar na biblioteca do leitor. Para vincular um capítulo a uma obra já cadastrada, use Atualizar capítulo da obra e selecione a obra. O número do capítulo é uma anotação manual, não uma suposição do aplicativo.
- Rastreamento automático limitado à página explicitamente vinculada e aos links de capítulos reconhecidos. Para outras páginas do mesmo site, confirme manualmente a obra para evitar misturar séries. O percentual é da página atual, não da série inteira. A posição em pixels usa a política de 120 URLs do leitor.
- Busca por título ou fonte, favoritos, quatro status, ordenação, continuar lendo e capas locais personalizadas. Sem acesso a catálogos externos, sem requisições adicionais de capa; usa o cache assíncrono existente.
- Limite local de 1000 obras. Gravações automáticas agrupadas em quatro segundos e ao sair. A remoção de uma fonte remove as obras vinculadas, após confirmação. Limpar histórico reinicia progresso sem remover obras.
- Testes JVM verificam isolamento de registros, serialização, filtros, validação de URL, remoção e limites. A compilação não substitui teste funcional no Android.
- Próxima prioridade: sistema de extensões com adaptadores de catálogo/obra/capítulos. Não há descoberta universal de capítulos nem atualização automática de catálogos nesta versão.
