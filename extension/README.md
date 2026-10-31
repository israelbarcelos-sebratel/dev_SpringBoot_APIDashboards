# Extensão TMA/TME — empacotamento e distribuição

Duas formas de instalar, servidas pelo `ExtensaoDownloadController` e pelo `ExtensaoCrxController`:

| Rota | O que é | Para quê |
|---|---|---|
| `GET /download` e `/download/tma-tme-extensao.zip` | pasta `extension/tma-tme` zipada na hora | instalação manual ("Carregar sem compactação") |
| `GET /download/updates.xml` e `/download/tma-tme.crx` | manifesto de atualização + pacote assinado | instalação forçada pelo Admin Console do Google (auto-hospedada) |

## Publicar uma versão nova

1. Suba o `version` em `tma-tme/manifest.json`.
2. Reempacote **com a chave original** (a que gerou o `key` do manifest; ID `fdfneajgonojaieieeghflmdcnefhikp`):
   ```
   chrome --pack-extension=<copia-da-pasta-tma-tme> --pack-extension-key=<tma-tme-extension-key.pem>
   ```
   Empacote uma **cópia** da pasta (o Chrome grava o `.crx` ao lado dela) e copie o resultado para `extension/tma-tme.crx`.
3. Commit da pasta + do `.crx` no mesmo PR. O teste `ExtensaoCrxControllerTest` falha se a versão do `.crx`
   não bater com a do manifest.

A chave privada (`.pem`) **nunca** entra no repositório nem na imagem. Com outra chave o ID muda e o
login Google da extensão (cliente OAuth registrado para o ID acima) deixa de funcionar.

## Configuração do servidor

`APP_EXTENSAO_BASE_URL` (no Portainer: `EXTENSAO_BASE_URL`): URL pública do serviço, sem barra final
(ex.: `http://10.0.0.5:8092`). É o que vai no `codebase` do `updates.xml`. Se vazio, usa o host da própria
requisição. Em ambientes compartilhados, defina.

## Admin Console

Dispositivos → Chrome → Aplicativos e extensões → Usuários e navegadores → (UO) → `+` → "Adicionar por URL":
ID `fdfneajgonojaieieeghflmdcnefhikp`, URL `<base>/download/updates.xml`, instalação forçada.


## Massiva e AutoISP: aviso de ligação e busca por CPF/CNPJ (0.28.0)

- **Aviso de ligação:** `native-hook.js` (world MAIN, só em `sebratel.native-infinity.com.br`) escuta os eventos
  `AgentCalled`/`AgentConnect`/`QueueCallerJoin` do socket.io do painel e repassa o telefone a `chamada.js`, que
  mostra um banner com a situação de massiva. Nada é enviado nem alterado na Native.
- **Busca por CPF/CNPJ:** `busca.js` abre uma caixa no topo de qualquer página com um atalho que a pessoa define no
  popup (padrão `Alt+Shift+M`). Mostra massiva e as alterações de rede ativas no AutoISP (a partir da 0.28.0).
- **Servidor:** a extensão chama `POST /ext/massiva/telefone` e `/ext/massiva/documento` na native-api (token Google
  da extensão). A native-api consulta o gateway da Sebratel com Basic: defina na stack
  `MASSIVA_GATEWAY_URL`, `MASSIVA_GATEWAY_USER` e `MASSIVA_GATEWAY_PASSWORD`. Sem elas a rota responde 503. A
  credencial nunca vai para a extensão.


## Versões já usadas (não repetir)

O Chrome só atualiza para uma versão **maior**; repetir um número com conteúdo diferente deixa quem já o instalou
sem receber a mudança, e uma versão menor nunca é instalada por cima de uma maior.

- `0.7.0` a `0.22.0`: histórico do Git (não houve `0.12.0`).
- `0.23.0` a `0.26.0`: existiram só em builds locais (pasta `app/tma-tme-extension`, fora do repositório; a `0.26.0` foi
  empacotada com a chave errada). A `0.23.0` chegou a ser publicada em staging por engano e foi substituída.
- `0.27.0`: primeira com aviso de massiva e busca por CPF/CNPJ.
- `0.28.0`: acrescenta as alterações de rede do AutoISP ao aviso e à busca, resume o motivo da massiva e mostra o
  que o cliente teve nas últimas 6 horas (massivas e eventos de rede já encerrados).
- `0.29.0`: a previsão mostra o dia e a hora exatos (e "previsão vencida"), e o painel sempre informa os eventos das
  últimas 6 horas, inclusive "não existem outros eventos".
- `0.30.0`: "Modo de teste" no popup: mostra um aviso também para ligações ignoradas (ramal interno, saída, outro
  agente), com o motivo. Serve para confirmar que o evento da Native chega à extensão.
- `0.31.0`: o painel não fecha mais sozinho; só pelo botão × ou com 3 cliques fora dele em menos de 1 segundo.
- `0.32.0`: quando o cliente digita o CPF/CNPJ na URA e a Native cola os dígitos no número da ligação (0800 + documento),
  a extensão extrai o documento (só se os dígitos verificadores baterem) e consulta por ele em vez do telefone. Foi
  publicada em staging e substituída pela `0.33.0` (a consulta por documento não vai adiante por enquanto).
- `0.33.0`: quando os dígitos digitados na URA chegam colados ao número da ligação (0800 766 1000 + dígitos), o painel
  só mostra "Número digitado na URA" com esses dígitos, sem consultar o servidor.
- `0.34.0`: botão de **minimizar** no widget (deixa só o cabeçalho; a escolha vale para todas as abas) e **aviso de versão
  nova**: a extensão compara a versão instalada com a do `updates.xml` do servidor e, se houver uma mais nova, mostra
  um aviso no widget e no popup com o botão **Atualizar**, que pede a atualização ao Chrome e recarrega a extensão sem
  passar por `chrome://extensions`. Depois de atualizar, dê F5 na Native para o hook de eventos ser reinstalado.
- `0.35.0`: tela de gestor (ofensores) ganha o card **CSAT** (menores notas primeiro, Native e Matrix; a Native só mostra
  dado quando a pesquisa dela tiver respostas com o nome do atendente) e a busca por CPF/CNPJ abre também por botão
  (lupa no widget e "Buscar CPF/CNPJ nesta aba" no popup), sem depender do atalho de teclado.
- `0.35.1`: a extensão se recupera sozinha ao ser atualizada com abas abertas (reinjeta busca e aviso de chamada; as
  cópias antigas se calam), sem F5. A `0.35.0` não chegou a ser publicada.
- `0.36.0`: o painel de ligação e a busca por CPF/CNPJ ganham o **Histórico do cliente (12 meses)**: nível de
  recorrência, protocolos por janela de tempo, assuntos presumidos por etiquetas, CSAT e sugestões (ex.: visita técnica).
- `0.37.0`: o histórico cruza tudo pelo mesmo cliente: CPF/CNPJ **e** todos os telefones do cadastro do ERP (`people`,
  num formato único DDD+número), protocolos da Matrix com o assunto de cada um, massivas em que ele esteve
  (`massiva_history`), ligações da Native (recebidas, sem atendimento, retornos da equipe) e o CSAT.
- `0.38.0`: a logo da barra do Chrome (e o nome "Sebratel" no widget) fica **dourada** enquanto há versão nova a instalar.
- `0.38.1`: a logo fica em **ouro metálico** (sombra, tom de ouro e reflexo), não em amarelo chapado.
- `0.38.2`: a versão publicada é conferida a cada 1 minuto (antes 30), então a logo dourada e o aviso aparecem para todos
  quase na hora do deploy. Uma extensão não recebe webhook; por isso a verificação é frequente.
- `0.39.0`: o histórico separa as origens: **Matrix** (protocolos, assuntos, CSAT Matrix) e **Native** (ligações e CSAT Native), cada
  um com seu rótulo e seus dados, sem misturar. O CSAT de cada sistema só conta as respostas dos atendimentos do cliente.
- `0.40.0`: a conferência de versão nova roda por **alarme de 1 minuto** do próprio Chrome (permissão `alarms`, que não gera
  aviso de permissão), sem depender de aba, widget ou clique. Antes o ícone dourado só aparecia quando algo acordava a extensão.

Antes de empacotar, confira a maior versão de `git log --all -p -- extension/tma-tme/manifest.json` e de qualquer build
local, e suba **acima** dela.
