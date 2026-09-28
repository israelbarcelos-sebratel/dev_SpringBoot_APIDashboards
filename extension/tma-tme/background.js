importScripts("api.js");

// O widget (content script) não tem acesso a chrome.identity; ele pede os dados aqui.
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.type !== "getMetrics") return false;
  SebratelApi.getMetrics()
    .then((data) => sendResponse({ ok: true, data }))
    .catch((err) => sendResponse({ ok: false, error: err.message }));
  return true; // resposta assíncrona
});
