// Ambiente de teste da extensão: jsdom + relógio virtual + stub mínimo de chrome.*.
// Os scripts reais (content.js, chamada.js, massiva-ui.js, busca.js, native-hook.js) rodam sem alteração.
import { JSDOM } from "jsdom";
import fs from "node:fs";
import path from "node:path";

export function criarAmbiente({ dir, url = "https://sebratel.native-infinity.com.br/", responder = () => ({ ok: true }) }) {
  const dom = new JSDOM("<!doctype html><html><body></body></html>", { url, runScripts: "outside-only", pretendToBeVisual: true });
  const w = dom.window;

  // ---- relógio virtual: Date.now e timers só andam quando o teste manda ----
  const relogio = { agora: 1_760_000_000_000 };
  let seq = 0;
  const timers = new Map();
  w.Date.now = () => relogio.agora;
  w.setTimeout = (fn, ms = 0) => { const id = ++seq; timers.set(id, { t: relogio.agora + ms, fn, cada: 0 }); return id; };
  w.setInterval = (fn, ms) => { const id = ++seq; timers.set(id, { t: relogio.agora + ms, fn, cada: ms }); return id; };
  w.clearTimeout = w.clearInterval = (id) => { timers.delete(id); };
  function avancar(ms) {
    const fim = relogio.agora + ms;
    for (;;) {
      let prox = null;
      for (const [id, tm] of timers) if (tm.t <= fim && (!prox || tm.t < prox[1].t)) prox = [id, tm];
      if (!prox) break;
      const [id, tm] = prox;
      relogio.agora = Math.max(relogio.agora, tm.t);
      if (tm.cada) tm.t += tm.cada; else timers.delete(id);
      try { tm.fn(); } catch { /* erro do script não derruba a simulação */ }
    }
    relogio.agora = fim;
  }

  // ---- contagem de ouvintes ativos em document e window (o que vaza quando um painel some sem se soltar) ----
  const registros = new Map(); // alvo -> Map<fn, Set<"tipo|capture">>
  const proto = w.EventTarget.prototype;
  const add = proto.addEventListener;
  const rem = proto.removeEventListener;
  const capt = (o) => (typeof o === "boolean" ? o : Boolean(o && o.capture));
  proto.addEventListener = function (tipo, fn, op) {
    if (this === w.document || this === w) {
      if (!registros.has(this)) registros.set(this, new Map());
      const m = registros.get(this);
      if (!m.has(fn)) m.set(fn, new Set());
      m.get(fn).add(`${tipo}|${capt(op)}`);
    }
    return add.call(this, tipo, fn, op);
  };
  proto.removeEventListener = function (tipo, fn, op) {
    const m = registros.get(this);
    const s = m?.get(fn);
    if (s) { s.delete(`${tipo}|${capt(op)}`); if (!s.size) m.delete(fn); } // sem manter a função viva
    return rem.call(this, tipo, fn, op);
  };
  const ouvintes = () => {
    let n = 0;
    for (const m of registros.values()) for (const s of m.values()) n += s.size;
    return n;
  };

  // ---- chrome.* ----
  const armazenamento = {};
  const contagem = { storageSet: 0, gravacoesWidget: 0, mensagens: {} };
  const ouvMsg = [];
  const ouvMudanca = [];
  const copia = (v) => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)));
  const chrome = {
    runtime: {
      id: "teste", lastError: undefined,
      getURL: (p) => `chrome-extension://teste/${p}`,
      getManifest: () => ({ version: "0.0.0" }),
      sendMessage(msg, cb) {
        contagem.mensagens[msg.type] = (contagem.mensagens[msg.type] || 0) + 1;
        const p = Promise.resolve(responder(msg));
        if (cb) { p.then(cb); return undefined; }
        return p;
      },
      onMessage: { addListener: (f) => ouvMsg.push(f) },
    },
    storage: {
      local: {
        get(chaves, cb) {
          const r = {};
          for (const k of [].concat(chaves)) if (k in armazenamento) r[k] = copia(armazenamento[k]);
          cb?.(r);
        },
        set(o, cb) {
          contagem.storageSet++;
          if ("sebratelWidgetState" in o) contagem.gravacoesWidget++;
          const mud = {};
          for (const [k, v] of Object.entries(o)) { mud[k] = { oldValue: copia(armazenamento[k]), newValue: copia(v) }; armazenamento[k] = copia(v); }
          for (const f of ouvMudanca) f(mud, "local");
          cb?.();
        },
        remove(k) { delete armazenamento[k]; },
      },
      onChanged: { addListener: (f) => ouvMudanca.push(f) },
    },
  };
  w.chrome = chrome;

  // postMessage síncrono, com source/origin corretos (o jsdom não preenche source)
  w.postMessage = (data) => {
    w.dispatchEvent(new w.MessageEvent("message", { data, origin: w.location.origin, source: w }));
  };

  let oculta = false;
  Object.defineProperty(w.document, "hidden", { get: () => oculta, configurable: true });

  return {
    w, chrome, armazenamento, contagem, avancar, relogio, ouvintes,
    carregar(...arquivos) { for (const f of arquivos) w.eval(fs.readFileSync(path.join(dir, f), "utf8")); },
    emitirParaScripts(msg) { for (const f of [...ouvMsg]) f(msg, {}, () => {}); },
    definirOculta(v) { oculta = v; w.document.dispatchEvent(new w.Event("visibilitychange")); },
    nos: () => w.document.getElementsByTagName("*").length,
    fechar: () => w.close(),
  };
}

export function gcCompleto() {
  if (!global.gc) throw new Error("Rode com: node --expose-gc");
  global.gc(); global.gc();
  return process.memoryUsage().heapUsed;
}

export function pasta(dir) { return path.resolve(dir); }
