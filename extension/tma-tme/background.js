importScripts("api.js");

// Abas em que a pessoa fechou (true) ou reabriu pelo popup (false) o widget. Fica em
// storage.session: vale para F5 e para a navegação dentro da mesma aba, some quando a aba (ou o
// navegador) fecha — e fechar numa aba não fecha nas outras.
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

// Instalou/atualizou/recarregou a extensão: leva o widget às abas abertas, sem precisar de F5.
// Páginas do Chrome, da Web Store e PDFs recusam a injeção — ignoramos.
chrome.runtime.onInstalled.addListener(async () => {
  const tabs = await chrome.tabs.query({});
  for (const t of tabs) {
    if (t.id !== undefined && !t.discarded) injetar(t.id).catch(() => {});
  }
});

// O widget (content script) não tem acesso a chrome.identity; ele pede os dados aqui.
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg?.type === "openDetalhes") {
    // Tabela completa numa aba da própria extensão (lá o api.js tem chrome.identity).
    chrome.tabs.create({ url: chrome.runtime.getURL("detalhes.html") });
    return false;
  }
  if (msg?.type === "widgetAba") {
    // Estado desta aba: true = fechado aqui, false = reaberto aqui, null = segue a preferência.
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
  if (msg?.type === "widgetFechar") {
    if (sender.tab?.id !== undefined) marcarAba(sender.tab.id, true).catch(() => {});
    return false;
  }
  if (msg?.type === "widgetMostrar") {
    // Vindo do popup: reabre só na aba informada.
    // Sem script vivo na aba (aberta antes da instalação/atualização), injeta na hora — ele já
    // nasce visível porque a aba acabou de ser marcada como "reaberta".
    marcarAba(msg.tabId, false)
      .then(() => chrome.tabs.sendMessage(msg.tabId, { type: "widgetMostrar" }).catch(() => injetar(msg.tabId)))
      .then(() => sendResponse({ ok: true }))
      .catch(() => sendResponse({ ok: false }));
    return true;
  }
  if (msg?.type !== "getMetrics") return false;
  SebratelApi.getMetrics()
    .then((data) => sendResponse({ ok: true, data }))
    .catch((err) => sendResponse({ ok: false, error: err.message }));
  return true; // resposta assíncrona
});
