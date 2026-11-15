# Novelo

Um botão pra tirar as coisas da cabeça. (Antes se chamava Fio; desde a 1.24.0 é Novelo em tudo: `com.villamediana.novelo`, código e repositório.)

Segure e fale (ou toque pra deixar ouvindo). O Novelo transcreve, entende o que é e guarda no lugar certo:

- **Tarefas**: com prazo, lembrete, etapas e status (a fazer, fazendo, esperando, feito).
- **Notas**: ideias, informações, senhas (escondidas até você tocar).
- **Diário**: o relato do dia. As tarefas e notas que aparecem nele viram itens próprios.

Também dá pra **perguntar** ("o que eu tenho pra hoje?", "quando falei do contrato?", "qual era a senha do wifi da minha mãe?"): o Novelo procura no que você guardou e mostra o trecho. Pode **dar notícia** de algo ("terminei a planilha", "o fornecedor respondeu"): ele atualiza o item, marca etapas e guarda o andamento. Em tarefas longas, ele mesmo marca um momento pra perguntar como está.

Pessoas, lugares, projetos e assuntos citados viram nós de um **grafo**. É ele que liga as lembranças entre si e ajuda a achar as coisas.

A **câmera** do app lê o que está na foto (um papel, um quadro, um recibo) e guarda do mesmo jeito; a foto também vai pra galeria do celular na hora.

A **busca** acha pelas palavras e pelo sentido: as outras palavras que podem ter sido usadas pra mesma coisa vêm do Omni, no próprio celular, ou de quem pensa quando ele não está baixado (só o que foi digitado na busca vai).

## Primeiros passos

Na primeira vez o app pede as permissões e a pasta do backup. Nada pra baixar, nada pra configurar. O app fala português, inglês ou espanhol.

## Onde roda

Quem **ouve**, **vê** e **pensa** é a inteligência do Novelo: o app manda o pedido pronto (as instruções, a fala em pedaços, a foto) para o servidor do Novelo ([novelo-server](https://github.com/Villamediana/novelo-server)), que escolhe o modelo e devolve a resposta. Cada instalação tem uma chave no hardware seguro do celular e assina todo pedido; não há login. O servidor guarda números (modelo, tempo, custo), nunca o que foi dito.

Quem prefere (ou quando o teste da inteligência do Novelo termina sem Novelo+) segue com a **inteligência própria**, em Ajustes › Inteligência:

- **Celular**: o LFM3-Omni 2B (Liquid AI, via llama.cpp) ouve e vê sem internet. Tem 1,5 GB e é baixado uma vez. Nada no celular pensa.
- **API** (OpenAI, Anthropic, Kimi, Azure Foundry, OpenRouter ou outro endereço): você informa a chave (a conexão é testada sozinha) e escolhe o modelo. A chave fica cifrada no aparelho (AndroidKeyStore).

Essa tela fica escondida pra quem está todo na inteligência do Novelo (`SHOW_INTELLIGENCE` em `ui/settings/SettingsScreen.kt`).

Sem internet, o que você disser fica guardado e vai sozinho quando a rede voltar. O plano (teste, Novelo+) é sempre a palavra do servidor: o app só pergunta.

## Backup

Um arquivo `Novelo backup.zip` na pasta que você escolhe (no celular ou num drive que aparece na lista), atualizado a cada mudança.

## Atualização

O app procura versões novas nas [releases](../../releases) deste repositório e se atualiza com um toque. Baixe a APK da última release pra instalar a primeira vez.

## Build

JDK 21, Android SDK 37 e NDK 29 (o NDK só pro motor no celular). O endereço do servidor do Novelo não fica no repositório: o build lê `NOVELO_SERVER_URL` de `~/.config/novelo/server.env` (sem ele, o app sai só com a inteligência própria e o backup em pasta).

```bash
./gradlew :app:testDebugUnitTest             # testes (NOVELO_SHOTS: prints das telas em app/build/novelo-shots)
tools/release.sh -n $'- novidade'            # versão nova, com o motor no celular (ver docs/ARCHITECTURE.md)
```

Licenças: llama.cpp (MIT); os modelos LFM3 são baixados do Hugging Face sob a LFM Open License v1.0.
