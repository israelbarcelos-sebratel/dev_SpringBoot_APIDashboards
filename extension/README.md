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
