# Koma
Leitor para Android 8 ou superior, em aparelhos ARM64 como o Motorola Edge 20 Pro.

## Gerar o APK
A compilação requer internet para obter GeckoView, as ferramentas do Android e a versão oficial do uBlock Origin.

**Com GitHub Actions**  
Abra Actions → Gerar APK. Quando a tarefa terminar, baixe Koma-Android-arm64 na seção Artifacts. Extraia o ZIP e abra app-debug.apk no celular.

**No computador**  
Use JDK 17, Python 3, Android SDK Platform 37.2 e Gradle 9.4.1. Configure ANDROID_HOME para a pasta do SDK. Na pasta que contém settings.gradle, execute:

    gradle :app:assembleDebug

O arquivo fica em app/build/outputs/apk/debug/app-debug.apk.

## Ler
1. Abra o Koma e toque em **Adicionar site**. Cole o endereço HTTPS.
2. Toque no site para ler. Os capítulos e a busca seguem o formato de cada site.
3. Para mostrar os controles, use o gesto **Voltar** do Android. Toque em **Estante** para sair. A última página visitada no mesmo domínio fica salva.
4. Segure um site na estante para abrir sua página inicial ou removê-lo.

## Imagem e memória
O leitor usa uma sessão Gecko por vez. A estante libera a sessão. O app mantém a imagem entregue pelo site e deixa a decodificação, o zoom e o cache com o Gecko. Em leitores com contêineres reconhecidos, o estilo de leitura ajusta imagens largas e mantém a proporção. Em outros sites, vale o layout original. O endereço e a última posição de rolagem são salvos por fonte; a restauração depende do carregamento do site. A estante usa linhas recicláveis e capas decodificadas em segundo plano. O Koma não baixa cópias das imagens dos capítulos nem reduz sua qualidade. Em leitores reconhecidos, a preparação opcional tenta decodificar apenas uma imagem próxima que já terminou de carregar. Veja PERFORMANCE.md para detalhes e limites.

## Componentes
Mozilla GeckoView (MPL-2.0) e uBlock Origin (GPL-3.0). O script tools/blocker.py baixa a versão indicada do catálogo da Mozilla, confere o resumo SHA-256 e a identidade da extensão e inclui seu conteúdo no APK. A extensão é inicializada antes da primeira página. Para atualizar o código do bloqueador, altere a versão e gere outro APK.
Fontes: https://github.com/gorhill/uBlock e https://firefox-source-docs.mozilla.org/mobile/android/geckoview/


## Koma 4.0
Veja [READING.md](READING.md) para os novos controles de leitura e limitações.
