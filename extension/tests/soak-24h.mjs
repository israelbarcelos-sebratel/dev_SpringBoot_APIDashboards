// Simulação de 24 h de uso comprimidas em no máximo --duracao-min minutos (padrão 14), com carga CRESCENTE:
// a hora 1 recebe poucas ligações e a hora 24 recebe ~60x mais. Roda os scripts reais da extensão no jsdom com
// relógio virtual, mede heap (após GC), ouvintes ativos, nós de DOM, mapa de telefones e gravações de storage a
// cada hora virtual, e termina com veredito (exit 1 se vazar).
//   node --expose-gc soak-24h.mjs [--dir ../tma-tme] [--duracao-min 14] [--pico 720] [--saida ./relatorio]
import fs from "node:fs";
import path from "node:path";
import { criarAmbiente, gcCompleto } from "./lib/ambiente.mjs";

const arg = (n, d) => { const i = process.argv.indexOf(`--${n}`); return i > 0 ? process.argv[i + 1] : d; };
const DIR = path.resolve(arg("dir", "../tma-tme"));
const ROTULO = arg("rotulo", "versao");
const DURACAO_MIN = Number(arg("duracao-min", 14));
if (DURACAO_MIN > 15) throw new Error("O limite do teste é 15 minutos.");
const PICO = Number(arg("pico", 720)); // ligações na hora mais carregada
const MINIMO = 12;                      // ligações na primeira hora
const SAIDA = path.resolve(arg("saida", "./relatorio"));
const HORAS = 24;
const ORCAMENTO_HORA_MS = (DURACAO_MIN * 60_000) / HORAS;

// mulberry32: sequência reproduzível entre a versão antiga e a nova
let semente = 20261105;
const rnd = () => { semente |= 0; semente = (semente + 0x6d2b79f5) | 0; let t = Math.imul(semente ^ (semente >>> 15), 1 | semente); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };

const historicoGrande = Array.from({ length: 12 }, (_, i) => ({ titulo: `Massiva ${i}`, inicio: "2026-10-01T10:00:00Z", motivo: "x".repeat(300) }));
let metricaAtual = 100;
let mudancasDeMetrica = 0; // quantas vezes o servidor devolveu números diferentes
let amb;
amb = criarAmbiente({
  dir: DIR,
  responder: (m) => {
    switch (m.type) {
      case "widgetAba": return { oculto: false };
      case "getMetrics": {
        if (rnd() < 0.3) { metricaAtual += Math.floor(rnd() * 20) - 5; mudancasDeMetrica++; } // o TMA muda de vez em quando, não a cada 15 s
        return { ok: true, data: { agent: "Atendente Teste", updatedAt: new Date(amb.relogio.agora).toISOString(),
          native: { available: true, ultimoRegistro: "2026-11-05 09:13:33", tma: { hoje: metricaAtual, amostras: 9, meta: 300 }, atendimentos: { hoje: 20, mes: 400 }, filas: { rotulo: "Fila", itens: [{ fila: "A", segundos: 900, atendimentos: 3 }] } },
          matrix: { available: true, tma: { hoje: 200, amostras: 4, meta: 300 }, atendimentos: { hoje: 5, mes: 90 } } } };
      }
      case "massivaTelefone": case "massivaDocumento":
        return { ok: true, data: { status: rnd() < 0.15 ? "em_massiva" : "livre", telefone: m.telefone, motivo: "Relato inicial: " + "queda de rede ".repeat(20), previsao_horas: 3, historico: rnd() < 0.5 ? historicoGrande : [], autoisp: { recentes: [] } } };
      case "clienteResumo": case "clienteLigacoes": return { ok: true, data: { itens: [] } };
      default: return { ok: true };
    }
  },
});
amb.carregar("native-hook.js", "massiva-ui.js", "busca.js", "chamada.js", "content.js");
const w = amb.w;
const esperar = () => new Promise((r) => setImmediate(r));
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

function emitirFrame(evento, dados) { w.__sebratelNativeHookProcessar(`42["${evento}",${JSON.stringify(dados)}]`); }
const pool = []; // telefones que já ligaram: parte reaparece (cliente que liga de novo)
function telefone() {
  if (pool.length > 50 && rnd() < 0.15) return pool[Math.floor(rnd() * pool.length)];
  const t = `119${String(Math.floor(rnd() * 1e8)).padStart(8, "0")}`;
  pool.push(t);
  if (pool.length > 5000) pool.shift();
  return t;
}
function teclar(code, extra = {}) { w.dispatchEvent(new w.KeyboardEvent("keydown", { code, key: extra.key || code, altKey: extra.altKey, shiftKey: extra.shiftKey, bubbles: true, cancelable: true })); }

async function umaLigacao() {
  const r = rnd();
  const agente = "1234";
  if (r < 0.10) emitirFrame("AgentCalled", { agent: agente, callerNumber: `08007661000${Math.floor(rnd() * 1e9)}` }); // dígitos da URA
  else if (r < 0.25) emitirFrame("QueueCallerJoin", { agent: agente, callerNumber: "2001" });                         // ramal interno
  else if (r < 0.32) emitirFrame("AgentConnect", { agent: agente, callerNumber: telefone(), direction: "outgoing" }); // saída
  else { const t = telefone(); emitirFrame("AgentCalled", { agent: agente, callerNumber: t }); emitirFrame("AgentConnect", { agent: agente, callerNumber: t }); }
  for (let k = 0; k < 20; k++) w.__sebratelNativeHookProcessar(rnd() < 0.5 ? "2" : `42["DialBegin",{"x":"${"y".repeat(200)}"}]`); // ruído do socket
  await esperar(); await esperar();
  // o que a pessoa faz com o painel; se ignora, o próximo aviso o substitui
  const a = rnd();
  if (a < 0.30) w.document.body.click(); // clica fora
  else if (a < 0.38) teclar("Escape", { key: "Escape" });
}

async function buscaPorDocumento() {
  teclar("KeyM", { altKey: true, shiftKey: true });
  await esperar(); await esperar();
  teclar("Escape", { key: "Escape" });
}

const amostras = [];
const t0 = Date.now();
console.log(`Simulação [${ROTULO}]: ${DIR}\n24 h virtuais em ${DURACAO_MIN} min reais · ligações/h: ${MINIMO} -> ${PICO}\n`);
console.log("hora | lig/h | heapMB | ouvintes(pico) | nós | vistos | widget.set | getMetrics | tempo(s)");

let picoOuvintes = 0;
for (let h = 0; h < HORAS; h++) {
  const ini = Date.now();
  const n = Math.round(MINIMO + (PICO - MINIMO) * (h / (HORAS - 1)) ** 1.6); // crescimento gradual
  const passoMs = 3_600_000 / n;
  amb.definirOculta(h < 6); // madrugada: aba da Native em segundo plano
  const set0 = amb.contagem.gravacoesWidget;
  const gm0 = amb.contagem.mensagens.getMetrics || 0;
  for (let i = 0; i < n; i++) {
    await umaLigacao();
    if (i % 25 === 0) await buscaPorDocumento();
    amb.avancar(passoMs);
    picoOuvintes = Math.max(picoOuvintes, amb.ouvintes());
    if (i % 100 === 0) await esperar();
  }
  const heap = gcCompleto();
  const am = {
    hora: h + 1, ligacoes: n, heapMB: +(heap / 1e6).toFixed(1), ouvintes: amb.ouvintes(), picoOuvintes, nos: amb.nos(),
    vistos: w.__sebratelChamadaTeste?.tamanhoVistos?.() ?? null,
    storageSet: amb.contagem.gravacoesWidget - set0, getMetrics: (amb.contagem.mensagens.getMetrics || 0) - gm0,
  };
  amostras.push(am);
  const gasto = Date.now() - ini;
  console.log(`${String(am.hora).padStart(4)} | ${String(n).padStart(5)} | ${String(am.heapMB).padStart(6)} | ${String(am.ouvintes).padStart(3)} (${picoOuvintes}) | ${String(am.nos).padStart(3)} | ${String(am.vistos ?? "?").padStart(6)} | ${String(am.storageSet).padStart(11)} | ${String(am.getMetrics).padStart(10)} | ${(gasto / 1000).toFixed(1)}`);
  await dormir(Math.max(0, ORCAMENTO_HORA_MS - gasto)); // preenche o orçamento de tempo real da hora (<= 15 min no total)
}

// ---- veredito ----
const reg = (ys) => { const n = ys.length, xs = ys.map((_, i) => i); const mx = xs.reduce((a, b) => a + b) / n, my = ys.reduce((a, b) => a + b) / n; return xs.reduce((a, x, i) => a + (x - mx) * (ys[i] - my), 0) / xs.reduce((a, x) => a + (x - mx) ** 2, 0); };
const aposAquecer = amostras.slice(6); // ignora a fase de aquecimento (JIT, caches)
const inclinacao = reg(aposAquecer.map((a) => a.heapMB)); // MB por hora virtual
const totalSet = amostras.reduce((a, x) => a + x.storageSet, 0);
const totalGm = amostras.reduce((a, x) => a + x.getMetrics, 0);
const criterios = [
  { nome: "Heap estável (inclinação < 0,5 MB/h virtual, após aquecer)", ok: inclinacao < 0.5, valor: `${inclinacao.toFixed(2)} MB/h` },
  { nome: "Crescimento do heap da hora 7 à 24 < 10 MB", ok: aposAquecer.at(-1).heapMB - aposAquecer[0].heapMB < 10, valor: `${(aposAquecer.at(-1).heapMB - aposAquecer[0].heapMB).toFixed(1)} MB` },
  { nome: "Ouvintes em document/window limitados (pico <= 25)", ok: picoOuvintes <= 25, valor: String(picoOuvintes) },
  { nome: "Mapa de telefones vistos <= 200", ok: (amostras.at(-1).vistos ?? Infinity) <= 200, valor: String(amostras.at(-1).vistos) },
  { nome: "Nós de DOM estáveis (fim <= hora 7 + 5)", ok: amostras.at(-1).nos <= amostras[6].nos + 5, valor: `${amostras[6].nos} -> ${amostras.at(-1).nos}` },
  { nome: "Aba oculta (horas 2-6) não consulta a API", ok: amostras.slice(1, 6).every((a) => a.getMetrics === 0), valor: `${amostras.slice(1, 6).reduce((a, x) => a + x.getMetrics, 0)} consultas` },
  { nome: "Estado do widget só é gravado quando os números mudam", ok: totalSet <= mudancasDeMetrica + 20, valor: `${totalSet} gravações / ${mudancasDeMetrica} mudanças / ${totalGm} consultas` },
];
console.log(`\nTempo real: ${((Date.now() - t0) / 60000).toFixed(1)} min\n`);
for (const c of criterios) console.log(`${c.ok ? "PASSOU " : "FALHOU "} ${c.nome}: ${c.valor}`);
fs.mkdirSync(SAIDA, { recursive: true });
const arquivo = path.join(SAIDA, `soak-${ROTULO}.json`);
fs.writeFileSync(arquivo, JSON.stringify({ rotulo: ROTULO, dir: DIR, duracaoMin: DURACAO_MIN, pico: PICO, amostras, criterios }, null, 2));
console.log(`\nRelatório: ${arquivo}`);
process.exit(criterios.every((c) => c.ok) ? 0 : 1);
