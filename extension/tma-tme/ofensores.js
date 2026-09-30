/*
 * Tela do administrador: maiores ofensores de hoje em TME, TMA e TMEA (GET /ext/ofensores nas duas
 * APIs). Os números são os mesmos que cada atendente vê no próprio widget; aqui só ordenamos.
 * TMA/TME são comparados com o limite do sistema; o TMEA, com a média do setor da pessoa (30 dias),
 * porque cada setor tem um ritmo diferente.
 */
const $ = (id) => document.getElementById(id);

const SISTEMAS = [
  { chave: "native", titulo: "Native", base: () => SebratelApi.NATIVE },
  { chave: "matrix", titulo: "Matrix", base: () => SebratelApi.MATRIX },
];
const METRICAS = [
  { chave: "tme", titulo: "TME", nome: "Tempo médio de espera" },
  { chave: "tma", titulo: "TMA", nome: "Tempo médio de atendimento" },
  { chave: "tmea", titulo: "TMEA", nome: "Tempo médio entre atendimentos" },
];
const TOP = 10;
const PERIODOS = {
  hoje: { titulo: "hoje", quando: "hoje", minimo: 3 },
  "30d": { titulo: "últimos 30 dias", quando: "nos últimos 30 dias", minimo: 20 },
};
const PREFS_KEY = "sebratelOfensores";

const dados = {}; // "periodo:sistema" -> resposta ou { erro }
// minimo por período: 3 atendimentos num dia não é o mesmo que 3 em 30 dias.
let prefs = { periodo: "hoje", sistema: "native", minimos: { hoje: 3, "30d": 20 }, setor: "", todos: false };
const minimo = () => prefs.minimos[prefs.periodo] ?? PERIODOS[prefs.periodo].minimo;

function fmt(seg) {
  if (seg === null || seg === undefined) return "—";
  seg = Math.round(Math.abs(seg));
  const h = Math.floor(seg / 3600);
  const m = Math.floor((seg % 3600) / 60);
  const s = seg % 60;
  const mmss = `${String(m).padStart(h ? 2 : 1, "0")}:${String(s).padStart(2, "0")}`;
  return h ? `${h}:${mmss}` : mmss;
}

function el(tag, attrs = {}, ...filhos) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") n.className = v;
    else if (k === "text") n.textContent = v;
    else n.setAttribute(k, v);
  }
  for (const f of filhos) if (f !== null && f !== undefined) n.append(f);
  return n;
}

function salvarPrefs() {
  try {
    chrome.storage.local.set({ [PREFS_KEY]: prefs });
  } catch {
    /* só conveniência */
  }
}

/**
 * Ranking de uma métrica: quem tem ao menos `minimo` atendimentos hoje, do pior para o melhor.
 * TMA/TME: pelo valor (o limite é o mesmo para todos). TMEA: pela diferença para a média do setor;
 * sem referência, pelo valor, depois dos que têm.
 */
function ranking(resp, metrica) {
  const meta = resp.metas?.[metrica] ?? null;
  const linhas = [];
  for (const a of resp.atendentes) {
    const t = a.tempos[metrica];
    if (!t || a.atendimentos < minimo()) continue;
    if (prefs.setor && a.setor !== prefs.setor) continue;
    const valor = t.segundosMedios;
    let ref = null;
    let excesso = null;
    if (metrica === "tmea") {
      ref = a.tmeaReferencia;
      excesso = ref ? valor - ref.segundosMedios : null;
    } else if (meta) {
      excesso = valor - meta;
    }
    linhas.push({ a, valor, amostras: t.amostras, ref, excesso });
  }
  linhas.sort((x, y) => {
    if (metrica === "tmea" && (x.excesso === null) !== (y.excesso === null)) return x.excesso === null ? 1 : -1;
    const cx = metrica === "tmea" && x.excesso !== null ? x.excesso : x.valor;
    const cy = metrica === "tmea" && y.excesso !== null ? y.excesso : y.valor;
    return cy - cx;
  });
  return { meta, linhas };
}

/** "Caroline Vargas - Financeiro" -> ["Caroline Vargas", "Financeiro"] (o setor pode vir antes ou depois). */
function separarSetor(a) {
  const partes = a.nome.split(/\s+-\s+/);
  if (partes.length > 1) {
    const chave = (s) => s.normalize("NFD").replace(/\p{M}/gu, "").trim().toLowerCase();
    const i = a.setor ? partes.findIndex((p) => chave(p) === chave(a.setor)) : -1;
    const idx = i >= 0 ? i : partes.length - 1;
    return [partes.filter((_, j) => j !== idx).join(" - "), partes[idx]];
  }
  return [a.nome, a.setor || ""];
}

function abrirDetalhe(nome) {
  chrome.tabs.create({ url: chrome.runtime.getURL(`detalhes.html?atendente=${encodeURIComponent(nome)}`) });
}

function cardMetrica(resp, m) {
  const { meta, linhas } = ranking(resp, m.chave);
  const acima = linhas.filter((l) => l.excesso !== null && l.excesso > 0).length;
  const card = el("div", { class: "card rank" });
  const regra = m.chave === "tmea" ? "comparado com a média do setor (30 dias)" : meta ? `limite ${fmt(meta)}` : "sem limite definido";
  card.append(
    el("div", { class: "rank-head" },
      el("div", { class: "section-marker", text: `${m.titulo} · ${m.nome}` }),
      el("div", { class: "muted", text: regra }),
      el("div", { class: `resumo-n ${acima ? (m.chave === "tmea" ? "warn" : "bad") : "ok"}`, text: linhas.length ? `${acima} de ${linhas.length} ${m.chave === "tmea" ? "acima do setor" : "acima do limite"}` : "" })
    )
  );
  if (!linhas.length) {
    card.append(el("p", { class: "muted", text: `Ninguém com ${minimo()} ou mais atendimentos ${PERIODOS[prefs.periodo].quando}.` }));
    return card;
  }
  const visiveis = prefs.todos ? linhas : linhas.slice(0, TOP);
  const tbody = el("tbody");
  visiveis.forEach((l, i) => {
    const excedeu = l.excesso !== null && l.excesso > 0;
    const classe = l.excesso === null ? "" : excedeu ? (m.chave === "tmea" ? "warn" : "bad") : "ok";
    const [pessoa, setor] = separarSetor(l.a);
    const nome = el("div", { class: "link", role: "link", tabindex: "0", title: `${l.a.nome}
Abrir os atendimentos de hoje dessa pessoa` },
      el("span", { class: "pessoa", text: pessoa }),
      setor ? el("span", { class: "setor", text: setor }) : null);
    nome.addEventListener("click", () => abrirDetalhe(l.a.nome));
    nome.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        abrirDetalhe(l.a.nome);
      }
    });
    let dif = "—";
    let difTitulo = "";
    if (l.excesso !== null) {
      dif = `${l.excesso > 0 ? "+" : "−"}${fmt(l.excesso)}`;
      difTitulo = m.chave === "tmea"
        ? `Média de ${l.ref.atendentes} colega(s) do ${l.ref.setor ? "setor " + l.ref.setor : "operação"}: ${fmt(l.ref.segundosMedios)}`
        : `Limite ${fmt(meta)}`;
    }
    tbody.append(
      el("tr", {},
        el("td", { class: "pos", text: String(i + 1) }),
        el("td", { class: "nome" }, nome),
        el("td", { class: "metric " + classe, text: (excedeu && m.chave !== "tmea" ? "▲ " : "") + fmt(l.valor) }),
        el("td", { class: "metric dif " + classe, title: difTitulo, text: dif }),
        el("td", { class: "metric n", title: m.chave === "tmea" ? `${l.amostras} intervalo(s) entre atendimentos` : `${l.amostras} atendimento(s) com tempo válido`, text: String(l.a.atendimentos) })
      )
    );
  });
  const head = el("tr", {},
    el("th", { scope: "col", text: "#" }),
    el("th", { scope: "col", text: "Atendente" }),
    el("th", { scope: "col", class: "metric", text: m.titulo }),
    el("th", { scope: "col", class: "metric", text: m.chave === "tmea" ? "vs. setor" : "vs. limite" }),
    el("th", { scope: "col", class: "metric", text: "Atend." })
  );
  const wrap = el("div", { class: "table-wrap" });
  wrap.append(el("table", {}, el("thead", {}, head), tbody));
  card.append(wrap);
  if (!prefs.todos && linhas.length > TOP) {
    card.append(el("p", { class: "legend", text: `Mostrando os ${TOP} piores de ${linhas.length}.` }));
  }
  return card;
}

function desenharSetores(resp) {
  const sel = $("setor");
  const setores = [...new Set(resp.atendentes.map((a) => a.setor).filter(Boolean))].sort((a, b) => a.localeCompare(b, "pt-BR"));
  if (prefs.setor && !setores.includes(prefs.setor)) prefs.setor = "";
  sel.innerHTML = "";
  sel.append(el("option", { value: "", text: "Todos os setores" }));
  for (const s of setores) sel.append(el("option", { value: s, text: s }));
  sel.value = prefs.setor;
}

function desenhar() {
  const per = PERIODOS[prefs.periodo];
  for (const b of document.querySelectorAll(".chip[data-sistema]")) b.setAttribute("aria-pressed", String(b.dataset.sistema === prefs.sistema));
  for (const b of document.querySelectorAll(".periodo")) b.setAttribute("aria-selected", String(b.dataset.periodo === prefs.periodo));
  $("titulo").textContent = `Maiores ofensores · ${per.titulo}`;
  $("minimo").value = minimo();
  $("legenda").textContent =
    (prefs.periodo === "hoje"
      ? "Mesmos números que cada atendente vê no widget, só de hoje — no começo do dia poucos atendimentos entraram, por isso o mínimo. "
      : "Médias dos últimos 30 dias (inclui hoje), recalculadas a cada 10 minutos. ") +
    "TME e TMA: vermelho acima do limite do sistema. TMEA: âmbar acima da média dos colegas do mesmo setor nos últimos 30 dias. " +
    "Clique no nome para ver os atendimentos de hoje da pessoa.";
  const alvo = $("rankings");
  alvo.innerHTML = "";
  const resp = dados[`${prefs.periodo}:${prefs.sistema}`];
  if (!resp) {
    $("sub").textContent = "Carregando…";
    return;
  }
  if (resp.erro) {
    alvo.append(el("div", { class: "card" }, el("div", { class: "error", text: resp.erro })));
    $("sub").textContent = "";
    return;
  }
  desenharSetores(resp);
  for (const m of METRICAS) alvo.append(cardMetrica(resp, m));
  const hora = resp.ultimoRegistro ? resp.ultimoRegistro.slice(11, 16) : "—";
  const total = resp.atendentes.reduce((s, a) => s + a.atendimentos, 0).toLocaleString("pt-BR");
  const calc = resp.calculadoEm ? resp.calculadoEm.slice(11, 16) : "—";
  $("sub").textContent = prefs.periodo === "hoje"
    ? `${resp.atendentes.length} atendente(s) · ${total} atendimentos hoje · dados até ${hora}`
    : `${resp.atendentes.length} atendente(s) · ${total} atendimentos nos últimos ${resp.dias} dias · calculado às ${calc}`;
}

async function carregar() {
  const btn = $("atualizar-btn");
  btn.disabled = true;
  btn.textContent = "Atualizando…";
  const periodo = prefs.periodo;
  const res = await Promise.allSettled(SISTEMAS.map((s) => SebratelApi.request(s.base(), `/ext/ofensores?periodo=${periodo}`)));
  res.forEach((r, i) => {
    dados[`${periodo}:${SISTEMAS[i].chave}`] = r.status === "fulfilled"
      ? r.value
      : { erro: r.reason?.status === 403 ? "Somente administradores." : r.reason?.message || "Dados indisponíveis" };
  });
  desenhar();
  btn.disabled = false;
  btn.textContent = "Atualizar";
}

for (const b of document.querySelectorAll(".chip[data-sistema]")) {
  b.addEventListener("click", () => {
    prefs.sistema = b.dataset.sistema;
    prefs.setor = "";
    salvarPrefs();
    desenhar();
  });
}
$("setor").addEventListener("change", (e) => {
  prefs.setor = e.target.value;
  salvarPrefs();
  desenhar();
});
for (const b of document.querySelectorAll(".periodo")) {
  b.addEventListener("click", () => {
    if (prefs.periodo === b.dataset.periodo) return;
    prefs.periodo = b.dataset.periodo;
    salvarPrefs();
    desenhar(); // mostra o que já tem (ou "Carregando…") e busca o atual
    carregar().catch(() => {});
  });
}
$("minimo").addEventListener("change", (e) => {
  prefs.minimos = { ...prefs.minimos, [prefs.periodo]: Math.max(1, Number(e.target.value) || 1) };
  e.target.value = minimo();
  salvarPrefs();
  desenhar();
});
$("todos").addEventListener("change", (e) => {
  prefs.todos = e.target.checked;
  salvarPrefs();
  desenhar();
});
$("atualizar-btn").addEventListener("click", () => carregar().catch(() => {}));

chrome.storage.local.get([PREFS_KEY], (r) => {
  const salvo = r[PREFS_KEY] || {};
  delete salvo.minimo; // formato antigo (um mínimo só)
  prefs = { ...prefs, ...salvo, minimos: { ...prefs.minimos, ...(salvo.minimos || {}) } };
  if (!PERIODOS[prefs.periodo]) prefs.periodo = "hoje";
  $("todos").checked = prefs.todos;
  carregar().catch((err) => {
    $("sub").textContent = err.message;
  });
});
// Os números de hoje mudam a cada minuto no servidor.
setInterval(() => carregar().catch(() => {}), 60_000);
