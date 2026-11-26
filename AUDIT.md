# Auditoria Koma 7.0 → 7.1
Base auditada: dc381c7a0b5152ac4f8052fbb9f135426be40082. APK 7.0: 211511955 bytes, SHA-256 05d468eacadb1cbe7abb125283220e143d95d5d09dc51b319b0009481a5c9fe7; CRC ZIP aprovado. Bibliotecas ARM64: 181726056 bytes (85,9%); libxul.so: 154844552 bytes. omni.ja comprimido: 14968106 bytes; uBlock comprimido: 7188397 bytes. APK contém uBlock 1.77.0 e extensão Koma 7.0. Mantidos motor, filtros e bibliotecas essenciais; não confundir tamanho do APK com RAM.
## Correções
- Alta: proteger progresso durante restauração lenta; aguardar até 30 s e preservar posição até recuperação ou ação real do leitor. Progresso da biblioteca condicionado à prontidão.
- Alta: navegação por fragmento não deve invalidar ponte nativa; comparar documento sem fragmento, mantendo host/caminho/query e URL atual. SPA que troca caminho via History API continua uma limitação.
- Alta: cancelamento do download limpa arquivo mesmo se coincidir com conclusão; recusar duplicata por fonte e URL.
- Média: stream de saída por imagem (64 KiB), sem abrir/fechar arquivo por bloco. Atualizar diálogo por imagem. Metadados incluem comprimento para detectar truncamento.
- Média: cache DOM de 5 s com invalidação por mudanças; limite de 1200 referências por lote e 96 contêineres. Teste sintético: 18 leituras em vez de 120 em 90 s sem mudanças; não mede CPU.
- Média: regiões offline de até 512 pixels decodificados, largura máxima 2048; teto nominal de 4 MiB ARGB por bitmap, não por processo/decoder. Suspender tarefas em segundo plano e remover retries antigos.
- CI: verifica assinatura APK e imprime manifesto/permissões efetivos.
## Limites
Sem Android/emulador neste ambiente: PSS, FPS, CPU, bateria, temperatura, eficácia do uBlock, compatibilidade de sites e estabilidade do runtime não medidos. APK debug com chave de CI; atualizações podem exigir reinstalação; não há backup completo. Download mesma origem apenas, sem CDN; formato e comprimento não provam integridade semântica.
## Protocolo no aparelho
Mesmas fontes/capítulos, brilho e conexão, três repetições com cache frio/quente. Medir processos principal e Gecko com adb shell dumpsys meminfo br.app.koma e Perfetto/FrameTimeline para compositor; gfxinfo apenas UI nativa. Comparar CPU, bateria e temperatura por 15 min. Testar modo avião, cancelamento, rotação, fontes grandes, restauração lenta, isolamento, pop-ups e painel uBlock. Verificar memória após 20 ciclos de abrir/fechar leitor. Não inventar números de desempenho.
