importScripts("api.js");

// Abas em que a pessoa mandou mostrar (false) ou fechou (true) o widget; aba sem registro não tem
// widget. Fica em storage.session: vale para F5 e para a navegação dentro da mesma aba, some quando
// a aba (ou o navegador) fecha — e o que se faz numa aba não muda as outras.
const ABAS_KEY = "widgetAbas";

async function abas() {
  const r = await chrome.storage.session.get(ABAS_KEY);
  return r[ABAS_KEY] || {};
}

async function marcarAba(tabId, oculto) {
  const a = await abas();
  if (oculto === null) delete a[tabId];
  else a[tabId] = oculto;
  await chrome.storage.session.set({ [ABAS_KEY]: a });
}

chrome.tabs.onRemoved.addListener((tabId) => {
  marcarAba(tabId, null).catch(() => {});
});

/** Coloca o widget numa aba já aberta (o Chrome só injeta sozinho nas páginas carregadas depois). */
async function injetar(tabId) {
  await chrome.scripting.insertCSS({ target: { tabId }, files: ["widget.css"] });
  await chrome.scripting.executeScript({ target: { tabId }, files: ["content.js"] });
}

// Instalou/atualizou/recarregou a extensão: leva o script às abas abertas, sem precisar de F5 (o
// widget só aparece nas que estavam marcadas para mostrar).
// Páginas do Chrome, da Web Store e PDFs recusam a injeção — ignoramos.
// Também recoloca o painel/busca de massiva e, na Native, o ouvinte de chamadas: as cópias antigas ficaram órfãs
// (sem ligação com a extensão) e se calam sozinhas. Quem fica sem recuperar é só o hook MAIN da Native, que não
// depende da extensão e continua vivo.
const NATIVE_URL = "https://sebratel.native-infinity.com.br/";
chrome.runtime.onInstalled.addListener(async () => {
  const tabs = await chrome.tabs.query({});
  for (const t of tabs) {
    if (t.id === undefined || t.discarded) continue;
    injetar(t.id).catch(() => {});
    chrome.scripting
      .executeScript({ target: { tabId: t.id }, files: ["massiva-ui.js", "busca.js"] })
      .catch(() => {});
    if (t.url && t.url.startsWith(NATIVE_URL)) {
      chrome.scripting.executeScript({ target: { tabId: t.id }, files: ["chamada.js"] }).catch(() => {});
    }
  }
});

// ---- Aviso de versão nova ----
// O Chrome só atualiza a extensão sozinho de tempos em tempos; aqui comparamos a versão instalada com a que o
// servidor publica (/download/updates.xml) para avisar a pessoa e deixá-la pedir a atualização na hora.
const ATUALIZACAO_KEY = "sebratelAtualizacao";
// O updates.xml tem poucas centenas de bytes. O cache fica abaixo do período do alarme (1 min) para que cada
// alarme sempre consulte o servidor de verdade.
const VERIFICAR_A_CADA_MS = 30 * 1000;

function versaoMaior(a, b) {
  const x = String(a).split(".").map(Number);
  const y = String(b).split(".").map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] || 0) - (y[i] || 0);
    if (d !== 0) return d > 0;
  }
  return false;
}

async function verificarAtualizacao() {
  const atual = chrome.runtime.getManifest().version;
  const salvo = (await chrome.storage.local.get(ATUALIZACAO_KEY))[ATUALIZACAO_KEY] || {};
  if (salvo.atual === atual && Date.now() - (salvo.verificadoEm || 0) < VERIFICAR_A_CADA_MS) return salvo;
  try {
    const r = await fetch(`${SebratelApi.NATIVE}/download/updates.xml`, { cache: "no-store" });
    const xml = await r.text();
    const m = xml.match(/<updatecheck[^>]*\sversion=['"]([\d.]+)['"]/);
    const publicada = m ? m[1] : null;
    const novo = {
      atual,
      publicada,
      disponivel: Boolean(publicada && versaoMaior(publicada, atual)),
      verificadoEm: Date.now(),
    };
    await chrome.storage.local.set({ [ATUALIZACAO_KEY]: novo });
    return novo;
  } catch {
    // Sem rede ou servidor fora: não avisa nada e tenta de novo na próxima vez.
    return { atual, publicada: null, disponivel: false, verificadoEm: 0 };
  }
}

/**
 * Logo da barra do Chrome: dourada enquanto há versão nova a instalar, original no resto. A dourada sai da própria
 * logo vermelha com a matiz girada (sem arquivo extra), no tamanho de cada ícone.
 */
const TAMANHOS_ICONE = [16, 32, 48, 128];
let iconeDouradoAtual = null;

/**
 * Troca o vermelho da logo por ouro metálico. O brilho da esfera (claro e escuro) passa por uma rampa de cores de
 * ouro — marrom-dourado nas sombras, ouro no meio, quase branco-champanhe no reflexo — com contraste maior e uma faixa
 * diagonal de brilho, que é o que dá cara de metal e não de amarelo chapado. O cinza das peças não muda.
 */
const RAMPA_OURO = [
  [0.0, [74, 44, 4]],
  [0.22, [140, 92, 8]],
  [0.42, [201, 145, 18]],
  [0.58, [236, 182, 44]],
  [0.76, [255, 217, 102]],
  [0.9, [255, 238, 170]],
  [1.0, [255, 250, 220]],
];

function corDoOuro(t) {
  for (let i = 1; i < RAMPA_OURO.length; i++) {
    if (t <= RAMPA_OURO[i][0]) {
      const [t0, c0] = RAMPA_OURO[i - 1];
      const [t1, c1] = RAMPA_OURO[i];
      const k = (t - t0) / (t1 - t0);
      return c0.map((v, j) => v + (c1[j] - v) * k);
    }
  }
  return RAMPA_OURO[RAMPA_OURO.length - 1][1];
}

function douradoNosVermelhos(px, largura) {
  for (let i = 0; i < px.length; i += 4) {
    const r = px[i], g = px[i + 1], b = px[i + 2];
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    if (px[i + 3] === 0 || (max - min) / 255 < 0.12 || max !== r) continue; // transparente, cinza ou fora do vermelho
    const luz = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    let t = Math.min(1, Math.max(0, (luz - 0.16) / 0.42)); // estica o brilho da esfera para a rampa toda
    t = t * t * (3 - 2 * t); // curva S: sombras mais fundas e reflexos mais claros
    const n = i / 4, x = (n % largura) / largura, y = Math.floor(n / largura) / largura;
    t = Math.min(1, t + 0.32 * Math.exp(-Math.pow((x + y) / 2 - 0.34, 2) / 0.006)); // faixa diagonal de brilho
    const c = corDoOuro(t);
    px[i] = Math.round(c[0]);
    px[i + 1] = Math.round(c[1]);
    px[i + 2] = Math.round(c[2]);
  }
}

async function iconeDourado() {
  const imageData = {};
  for (const t of TAMANHOS_ICONE) {
    const resp = await fetch(chrome.runtime.getURL(`icons/icon${t}.png`));
    const bmp = await createImageBitmap(await resp.blob());
    const canvas = new OffscreenCanvas(t, t);
    const ctx = canvas.getContext("2d");
    ctx.drawImage(bmp, 0, 0, t, t);
    const img = ctx.getImageData(0, 0, t, t);
    douradoNosVermelhos(img.data, t);
    imageData[t] = img;
  }
  return imageData;
}

async function aplicarIcone(disponivel) {
  if (iconeDouradoAtual === disponivel) return;
  try {
    if (disponivel) {
      await chrome.action.setIcon({ imageData: await iconeDourado() });
      await chrome.action.setTitle({ title: "TMA/TME Sebratel — atualização disponível" });
    } else {
      const path = Object.fromEntries(TAMANHOS_ICONE.map((t) => [t, `icons/icon${t}.png`]));
      await chrome.action.setIcon({ path });
      await chrome.action.setTitle({ title: "TMA/TME Sebratel" });
    }
    iconeDouradoAtual = disponivel;
  } catch {
    /* sem ícone novo desta vez: tenta de novo na próxima verificação */
  }
}

/** Verifica a versão publicada (com cache) e acerta a cor da logo; roda quando o serviço acorda. */
async function verificarEPintar() {
  const r = await verificarAtualizacao();
  await aplicarIcone(Boolean(r.disponivel));
  return r;
}
verificarEPintar().catch(() => {});

// Alarme de 1 minuto: acorda o service worker sozinho, sem depender de aba, widget ou clique, e a logo dourada aparece
// para todos logo depois do deploy. ("alarms" não mostra aviso de permissão, então não desativa a extensão no
// Chrome de quem já a tem instalada.) Criar de novo com o mesmo nome só substitui o alarme.
const ALARME_VERSAO = "sebratel-versao";
chrome.alarms.create(ALARME_VERSAO, { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((alarme) => {
  if (alarme.name === ALARME_VERSAO) verificarEPintar().catch(() => {});
});

/** Pede ao Chrome a atualização agora; quando ela baixar, recarrega a extensão para a versão nova valer. */
async function atualizarAgora() {
  chrome.runtime.onUpdateAvailable.addListener(() => chrome.runtime.reload());
  const r = await chrome.runtime.requestUpdateCheck();
  return { status: r?.status || "unknown", versao: r?.version || null };
}

// O widget (content script) não tem acesso a chrome.identity; ele pede os dados aqui.
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg?.type === "atualizacao") {
    verificarEPintar().then(sendResponse).catch(() => sendResponse({ disponivel: false }));
    return true;
  }
  if (msg?.type === "atualizarAgora") {
    atualizarAgora()
      .then((r) => sendResponse({ ok: true, ...r }))
      .catch((err) => sendResponse({ ok: false, error: err.message }));
    return true;
  }
  if (msg?.type === "openDetalhes") {
    // Tabela completa numa aba da própria extensão (lá o api.js tem chrome.identity).
    chrome.tabs.create({ url: chrome.runtime.getURL("detalhes.html") });
    return false;
  }
  if (msg?.type === "widgetAba") {
    // Estado desta aba: false = mostrar, true = fechado aqui, null = nunca mandou mostrar (sem widget).
    const tabId = sender.tab?.id;
    if (tabId === undefined) {
      sendResponse({ oculto: null });
      return false;
    }
    abas()
      .then((a) => sendResponse({ oculto: tabId in a ? a[tabId] : null }))
      .catch(() => sendResponse({ oculto: null }));
    return true;
  }
  if (msg?.type === "widgetEstado") {
    // Vindo do popup: o widget está à vista na aba informada? (mesmo formato do widgetAba)
    abas()
      .then((a) => sendResponse({ oculto: msg.tabId in a ? a[msg.tabId] : null }))
      .catch(() => sendResponse({ oculto: null }));
    return true;
  }
  if (msg?.type === "widgetEsconder") {
    // Vindo do popup: esconde só na aba informada (como o × do widget).
    marcarAba(msg.tabId, true)
      .then(() => chrome.tabs.sendMessage(msg.tabId, { type: "widgetEsconder" }).catch(() => {}))
      .then(() => sendResponse({ ok: true }))
      .catch(() => sendResponse({ ok: false }));
    return true;
  }
  if (msg?.type === "widgetFechar") {
    if (sender.tab?.id !== undefined) marcarAba(sender.tab.id, true).catch(() => {});
    return false;
  }
  if (msg?.type === "widgetMostrar") {
    // Vindo do popup: mostra só na aba informada.
    // Sem script vivo na aba (aberta antes da instalação/atualização), injeta na hora — ele já
    // nasce visível porque a aba acabou de ser marcada para mostrar.
    marcarAba(msg.tabId, false)
      .then(() => chrome.tabs.sendMessage(msg.tabId, { type: "widgetMostrar" }).catch(() => injetar(msg.tabId)))
      .then(() => sendResponse({ ok: true }))
      .catch(() => sendResponse({ ok: false }));
    return true;
  }
  if (msg?.type === "clienteResumo" || msg?.type === "clienteLigacoes") {
    const pedido = { documento: msg.documento, telefone: msg.telefone };
    (msg.type === "clienteResumo" ? SebratelApi.clienteResumo(pedido) : SebratelApi.clienteLigacoes(pedido))
      .then((data) => sendResponse({ ok: true, data }))
      .catch((err) => sendResponse({ ok: false, error: err.message }));
    return true;
  }
  if (msg?.type === "massivaTelefone" || msg?.type === "massivaDocumento") {
    // Aviso de ligação e busca por CPF/CNPJ: o content script não tem chrome.identity, pede aqui.
    const consulta = msg.type === "massivaTelefone"
      ? SebratelApi.massivaTelefone(msg.telefone)
      : SebratelApi.massivaDocumento(msg.documento);
    consulta
      .then((data) => sendResponse({ ok: true, data }))
      .catch((err) => sendResponse({ ok: false, error: err.message }));
    return true;
  }
  if (msg?.type !== "getMetrics") return false;
  SebratelApi.getMetrics()
    .then((data) => sendResponse({ ok: true, data }))
    .catch((err) => sendResponse({ ok: false, error: err.message }));
  return true; // resposta assíncrona
});
