# Koma 3.0 — prioridade GUT 1: fluidez e renderização

## Alterações
- Estante: ListView recicla linhas e cartões, com 2 ou 3 colunas; busca agrupada em 180 ms. Favoritar um cartão não recria toda a estante.
- Capas: cache LRU medido em bytes (4–12 MiB, conforme memória disponível); no máximo 2 trabalhadores e fila de 16 tarefas, com cancelamento ao reciclar/ocultar. A leitura do provedor e a decodificação não ocorrem em onDraw. As capas usam uma resolução próxima do tamanho visual, limitada a 640 px; isso NÃO se aplica aos capítulos.
- Pressão de memória: o cache de capas é reduzido ou esvaziado quando o Android informa pressão/ocultação. Em dispositivos classificados como low-RAM, há apenas um trabalhador.
- Rolagem: a extensão agrupa mensagens em intervalos de 1,5 s e o aplicativo agrupa a persistência em 4 s, com tentativa de gravação ao ocultar/navegar/fechar. A posição anterior é recuperada enquanto o conteúdo cresce, sem executar scrollTo repetidamente se o tamanho não mudou. É possível perder os segundos finais se o processo for morto abruptamente.
- Imagens: modo original intocado; em leitores reconhecidos, o app solicita decodificação assíncrona de imagens sem preferência explícita. A preparação opcional considera somente imagens já carregadas, até 3 milhões de pixels, próximas e abaixo da tela, em momento ocioso. Não usa fetch, não cria imagem duplicada, não altera src, não força o carregamento de imagens distantes, não guarda buffers de pixels. O Gecko administra cache, renderização e qualidade das páginas. O modo low-RAM desativa a preparação extra.
- Uma sessão Gecko por vez, fechada ao retornar à estante. Mantidos uBlock Origin, navegação HTTPS e configurações individuais.

## Verificações automáticas
A CI executa 8 testes Node da política de rolagem e escolha da próxima imagem, 16 verificações Java de limites/escala e compila o APK. A inspeção do ZIP do APK confirma os arquivos das extensões e bibliotecas ARM64.

## Medição no aparelho (não simulada)
Compare o Koma 2.0 e 3.0 no mesmo aparelho, com os mesmos sites, capítulos, brilho e conexão, e sem outros apps pesados. Repita 3 vezes com cache quente e frio.
1. Estante: use 30+ fontes e capas grandes; deslize por 30 s e digite um filtro. Verifique pausas e a memória.
2. Leitura: abra o mesmo capítulo, role por 60 s, volte à estante e reabra. Verifique imagens ausentes, qualidade, posição e carregamento. Compare a opção Preparar próxima imagem ligada/desligada.
3. Via ADB, execute antes e depois de cada sequência:

    adb shell dumpsys meminfo br.app.koma
    adb shell dumpsys gfxinfo br.app.koma reset
    adb shell dumpsys gfxinfo br.app.koma framestats
    adb logcat -d -s KomaPerf:D

O log page_stop_ms mede o tempo entre início e fim do carregamento da página; não mede quando toda a imagem está visível. gfxinfo mede a interface Android; não mede diretamente os quadros do compositor Gecko. Para FPS real do leitor, use uma captura Perfetto/SurfaceFlinger e compare a camada do Gecko. Não há percentuais de melhoria medidos nesta CI. O resultado depende do site e do aparelho.
