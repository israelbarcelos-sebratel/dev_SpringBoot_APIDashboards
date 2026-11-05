// Testes unitários/integração leve dos pontos que vazavam memória. Uso: npm test  (ou TMA_DIR=<pasta> para outra versão)
import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import fs from "node:fs";
import path from "node:path";
import { criarAmbiente, gcCompleto } from "./lib/ambiente.mjs";

const DIR = path.resolve(process.env.TMA_DIR || "../tma-tme");
const RESULTADO = { status: "livre", telefone: "11999990000", historico: [], autoisp: { recentes: [] } };
const responder = (msg) => (msg.type === "massivaTelefone" ? { ok: true, data: RESULTADO } : { ok: true });

function ligacao(amb, telefone, extra = {}) {
  amb.w.postMessage({ source: "sebratel-native-hook", evento: "AgentConnect", dados: { callerNumber: telefone, agent: "1234", ...extra } });
}
const esperar = () => new Promise((r) => setImmediate(r));

test("chamada.js: mapa de telefones vistos não cresce sem limite", async () => {
  const amb = criarAmbiente({ dir: DIR, responder });
  amb.carregar("massiva-ui.js", "chamada.js");
  for (let i = 0; i < 3000; i++) {
    ligacao(amb, `1199${String(1000000 + i)}`);
    amb.avancar(30_000);
    if (i % 50 === 0) await esperar();
  }
  await esperar();
  const tam = amb.w.__sebratelChamadaTeste.tamanhoVistos?.();
  assert.ok(tam !== undefined, "versão sem gancho de diagnóstico tamanhoVistos");
  assert.ok(tam <= 200, `vistos=${tam}`);
  amb.fechar();
});

test("chamada.js: mesmo telefone dentro da janela de 60 s consulta só uma vez", async () => {
  const amb = criarAmbiente({ dir: DIR, responder });
  amb.carregar("massiva-ui.js", "chamada.js");
  ligacao(amb, "11988887777"); ligacao(amb, "11988887777");
  await esperar();
  assert.equal(amb.contagem.mensagens.massivaTelefone, 1);
  amb.avancar(61_000);
  ligacao(amb, "11988887777");
  await esperar();
  assert.equal(amb.contagem.mensagens.massivaTelefone, 2);
  amb.fechar();
});

test("massiva-ui: trocar de painel solta os ouvintes do painel anterior", async () => {
  const amb = criarAmbiente({ dir: DIR, responder });
  amb.carregar("massiva-ui.js");
  amb.w.document.querySelectorAll("div"); // o jsdom registra ouvintes internos na 1ª consulta: entram na base
  const base = amb.ouvintes();
  for (let i = 0; i < 500; i++) amb.w.SebratelMassivaUI.criar(`p${i}`);
  assert.equal(amb.ouvintes() - base, 2, "só o painel atual deve ter click+keydown no document");
  assert.equal(amb.w.document.querySelectorAll("#sebratel-massiva-host").length, 1);
  amb.w.document.body.click();
  assert.equal(amb.ouvintes(), base, "clique fora fecha e solta tudo");
  amb.fechar();
});

test("massiva-ui: 2000 painéis criados e fechados não retêm memória", async () => {
  const amb = criarAmbiente({ dir: DIR, responder });
  amb.carregar("massiva-ui.js");
  const ciclo = (n) => { for (let i = 0; i < n; i++) { const p = amb.w.SebratelMassivaUI.criar("x"); p.corpo.append(...Array.from({ length: 30 }, (_, k) => amb.w.SebratelMassivaUI.linha("detalhe", `linha ${k}`.repeat(20)))); } };
  ciclo(500); const antes = gcCompleto();
  ciclo(2000); const depois = gcCompleto();
  assert.ok((depois - antes) / 1e6 < 8, `cresceu ${((depois - antes) / 1e6).toFixed(1)} MB`);
  amb.fechar();
});

test("content.js: não regrava o storage quando as métricas não mudaram e não consulta com a aba oculta", async () => {
  let n = 0;
  const amb = criarAmbiente({
    dir: DIR,
    responder: (m) => {
      if (m.type === "widgetAba") return { oculto: false };
      if (m.type === "getMetrics") { n++; return { ok: true, data: { agent: "Fulano", updatedAt: new Date(amb.relogio.agora).toISOString(), native: { available: true }, matrix: { available: true } } }; }
      return { ok: true };
    },
  });
  amb.carregar("content.js");
  await esperar(); await esperar();
  const base = amb.contagem.storageSet;
  for (let i = 0; i < 40; i++) { amb.avancar(15_000); await esperar(); }
  assert.ok(amb.contagem.storageSet - base <= 1, `gravações: ${amb.contagem.storageSet - base}`);
  assert.ok(n >= 40, `consultas visíveis: ${n}`);
  amb.definirOculta(true);
  const antes = n;
  for (let i = 0; i < 40; i++) { amb.avancar(15_000); await esperar(); }
  assert.equal(n, antes, "aba oculta não pode consultar a API");
  amb.definirOculta(false);
  await esperar();
  assert.equal(n, antes + 1, "ao voltar consulta na hora");
  amb.fechar();
});

test("content.js: ouvintes de arrastar só existem durante o arrasto", async () => {
  const amb = criarAmbiente({ dir: DIR, responder: (m) => (m.type === "widgetAba" ? { oculto: false } : { ok: false, error: "x" }) });
  amb.carregar("content.js");
  await esperar(); await esperar();
  const w = amb.w;
  const cab = w.document.querySelector("#sebratel-tma-widget .header");
  assert.ok(cab, "widget montado");
  const parado = amb.ouvintes();
  cab.dispatchEvent(new w.MouseEvent("mousedown", { bubbles: true, clientX: 5, clientY: 5 }));
  assert.equal(amb.ouvintes(), parado + 2);
  w.document.dispatchEvent(new w.MouseEvent("mouseup"));
  assert.equal(amb.ouvintes(), parado);
  amb.fechar();
});

test("content.js: aba Native/Matrix arrasta para fora, vira painel móvel, encaixa de volta e lembra a posição", async () => {
  const amb = criarAmbiente({ dir: DIR, responder: (m) => (m.type === "widgetAba" ? { oculto: false } : { ok: false, error: "x" }) });
  amb.carregar("content.js");
  await esperar(); await esperar();
  const w = amb.w;
  const sec = w.document.querySelector("#sebratel-section-native");
  const titulo = sec.querySelector(".section-title");
  const mouse = (tipo, x, y) => new w.MouseEvent(tipo, { bubbles: true, clientX: x, clientY: y, button: 0 });
  // o jsdom não faz layout: a posição "na tela" da aba é a que o script gravou no style
  sec.getBoundingClientRect = () => ({ left: parseFloat(sec.style.left) || 0, top: parseFloat(sec.style.top) || 0, width: 208, height: 100 });
  const parado = amb.ouvintes();

  // clique sem arrastar não solta a aba
  titulo.dispatchEvent(mouse("mousedown", 10, 10));
  w.document.dispatchEvent(mouse("mousemove", 12, 11));
  w.document.dispatchEvent(mouse("mouseup", 12, 11));
  assert.ok(!sec.classList.contains("destacada"), "um clique não deve soltar a aba");
  assert.equal(amb.ouvintes(), parado, "ouvintes soltos após o clique");

  // arrasto de verdade solta e posiciona
  titulo.dispatchEvent(mouse("mousedown", 10, 10));
  assert.equal(amb.ouvintes(), parado + 2, "ouvintes só durante o arrasto");
  w.document.dispatchEvent(mouse("mousemove", 300, 200));
  w.document.dispatchEvent(mouse("mouseup", 300, 200));
  assert.equal(amb.ouvintes(), parado);
  assert.ok(sec.classList.contains("destacada"));
  assert.equal(sec.style.left, "290px");
  assert.equal(sec.style.top, "190px");
  assert.ok(w.document.querySelector("#sebratel-tma-widget").classList.contains("tem-destacada"));
  await esperar(); // saveState grava de forma assíncrona
  assert.deepEqual(amb.armazenamento.sebratelWidgetState.destacadas.native, { left: "290px", top: "190px" });

  // já solta, arrastar só move
  titulo.dispatchEvent(mouse("mousedown", 300, 200));
  w.document.dispatchEvent(mouse("mousemove", 400, 260));
  w.document.dispatchEvent(mouse("mouseup", 400, 260));
  assert.equal(sec.style.left, "390px");

  // arrastar para fora da janela é contido
  titulo.dispatchEvent(mouse("mousedown", 400, 260));
  w.document.dispatchEvent(mouse("mousemove", 99999, 99999));
  w.document.dispatchEvent(mouse("mouseup", 99999, 99999));
  assert.ok(parseFloat(sec.style.left) <= w.innerWidth, "ficou fora da janela");
  assert.ok(parseFloat(sec.style.top) <= w.innerHeight, "ficou fora da janela");

  // duplo clique no título encaixa de volta
  titulo.dispatchEvent(new w.MouseEvent("dblclick", { bubbles: true }));
  assert.ok(!sec.classList.contains("destacada"));
  assert.equal(sec.style.left, "");
  await esperar();
  assert.equal(amb.armazenamento.sebratelWidgetState.destacadas.native, null);
  assert.ok(!w.document.querySelector("#sebratel-tma-widget").classList.contains("tem-destacada"));
  amb.fechar();
});

test("content.js: abas soltas voltam onde estavam ao reabrir a página", async () => {
  const amb = criarAmbiente({ dir: DIR, responder: (m) => (m.type === "widgetAba" ? { oculto: false } : { ok: false, error: "x" }) });
  amb.armazenamento.sebratelWidgetState = { destacadas: { native: null, matrix: { left: "120px", top: "80px" } } };
  amb.carregar("content.js");
  await esperar(); await esperar();
  const mx = amb.w.document.querySelector("#sebratel-section-matrix");
  assert.ok(mx.classList.contains("destacada"));
  assert.equal(mx.style.left, "120px");
  assert.ok(!amb.w.document.querySelector("#sebratel-section-native").classList.contains("destacada"));
  amb.fechar();
});

test("background.js: marcarAba em paralelo não perde nenhuma aba", async () => {
  let guardado = {};
  const chrome = {
    storage: { session: { get: async () => ({ widgetAbas: JSON.parse(JSON.stringify(guardado.widgetAbas || {})) }), set: async (o) => { await new Promise((r) => setImmediate(r)); guardado = JSON.parse(JSON.stringify(o)); } }, local: { get: async () => ({}), set: async () => {} } },
    tabs: { onRemoved: { addListener() {} }, query: async () => [] },
    runtime: { onInstalled: { addListener() {} }, onUpdateAvailable: { addListener() {} }, onMessage: { addListener() {} }, getManifest: () => ({ version: "1.0.0" }) },
    alarms: { create() {}, onAlarm: { addListener() {} } },
    action: { setIcon: async () => {}, setTitle: async () => {} },
  };
  const ctx = vm.createContext({ chrome, importScripts() {}, SebratelApi: { NATIVE: "http://x" }, fetch: async () => ({ text: async () => "" }), console, setTimeout, Date, URL });
  vm.runInContext(fs.readFileSync(path.join(DIR, "background.js"), "utf8") + "\n;globalThis.__marcar = marcarAba;", ctx);
  await Promise.all(Array.from({ length: 20 }, (_, i) => ctx.__marcar(100 + i, false)));
  assert.equal(Object.keys(guardado.widgetAbas).length, 20);
});
