/*
 * Roda no "mundo" da página da Native (world MAIN, document_start), antes do painel abrir o socket.
 * O painel recebe os eventos de chamada por socket.io em /monitorSocket/ (polling por XHR ou WebSocket);
 * aqui só ESCUTAMOS as respostas — nada é enviado nem alterado — e repassamos ao content script
 * (chamada.js) por postMessage os eventos que identificam quem está ligando.
 */
(function () {
  if (window.__sebratelNativeHook) return;
  window.__sebratelNativeHook = true;

  const EVENTOS = new Set(["AgentCalled", "AgentConnect", "QueueCallerJoin"]);

  function emitir(nome, dados) {
    if (!EVENTOS.has(nome) || !dados || typeof dados !== "object") return;
    window.postMessage({ source: "sebratel-native-hook", evento: nome, dados }, window.location.origin);
  }

  /** Fim do array JSON que começa em texto[ini] === "[" (ciente de strings e escapes), ou -1. */
  function fimDoArray(texto, ini) {
    let prof = 0;
    let emString = false;
    for (let i = ini; i < texto.length; i++) {
      const c = texto[i];
      if (emString) {
        if (c === "\\") i++;
        else if (c === '"') emString = false;
      } else if (c === '"') emString = true;
      else if (c === "[") prof++;
      else if (c === "]" && --prof === 0) return i;
    }
    return -1;
  }

  /**
   * Eventos socket.io de um trecho recebido: pacotes "42[...]" (Engine.IO message + Socket.IO event),
   * opcionalmente com namespace ("42/ns,[...]") e id de ack ("42123[...]"). Vale para o formato do
   * polling com prefixo de tamanho ("57:42[...]") e com separador \u001e, e para frames WebSocket.
   */
  function processar(texto) {
    if (typeof texto !== "string" || texto.indexOf("42") === -1) return;
    const re = /42(?:\/[^,[]*,)?\d*\[/g;
    let m;
    while ((m = re.exec(texto))) {
      const ini = m.index + m[0].length - 1;
      const fim = fimDoArray(texto, ini);
      if (fim < 0) continue;
      try {
        const arr = JSON.parse(texto.slice(ini, fim + 1));
        if (typeof arr[0] === "string") emitir(arr[0], arr[1]);
      } catch (e) {
        // pedaço que não é JSON (ex.: binário): ignora
      }
      re.lastIndex = fim + 1;
    }
  }

  // Polling: respostas GET de /monitorSocket/.
  const abrir = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (metodo, url) {
    try {
      if (String(url).includes("/monitorSocket/")) {
        this.addEventListener("load", () => {
          try {
            if (this.responseType === "" || this.responseType === "text") processar(this.responseText);
          } catch (e) {
            // nunca atrapalhar o painel
          }
        });
      }
    } catch (e) {
      // idem
    }
    return abrir.apply(this, arguments);
  };

  // WebSocket (se o socket.io subir de polling para websocket).
  const WS = window.WebSocket;
  if (WS) {
    const Embrulhado = function (url, protocolos) {
      const ws = protocolos === undefined ? new WS(url) : new WS(url, protocolos);
      try {
        if (String(url).includes("/monitorSocket/")) {
          ws.addEventListener("message", (ev) => processar(ev.data));
        }
      } catch (e) {
        // idem
      }
      return ws;
    };
    Embrulhado.prototype = WS.prototype;
    for (const k of ["CONNECTING", "OPEN", "CLOSING", "CLOSED"]) Embrulhado[k] = WS[k];
    window.WebSocket = Embrulhado;
  }

  // Para testes e diagnóstico (a página não usa isto).
  window.__sebratelNativeHookProcessar = processar;
})();
