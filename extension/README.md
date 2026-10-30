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

Antes de empacotar, confira a maior versão de `git log --all -p -- extension/tma-tme/manifest.json` e de qualquer build
local, e suba **acima** dela.
