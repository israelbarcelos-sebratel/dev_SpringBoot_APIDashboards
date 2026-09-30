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
const PREFS_KEY = "sebratelOfensores";

const dados = {}; // sistema -> resposta ou { erro }
let prefs = { sistema: "native", minimo: 3, setor: "", todos: false };

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
    if (!t || a.atendimentos < prefs.minimo) continue;
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

function abrirDetalhe(nome) {
  chrome.tabs.create({ url: chrome.runtime.getURL(`detalhes.html?atendente=${encodeURIComponent(nome)}`) });
}

function cardMetrica(resp, m) {
  const { meta, linhas } = ranking(resp, m.chave);
  const acima = linhas.filter((l) => l.excesso !== null && l.excesso > 0).length;
  const card = el("div", { class: "card rank" });
  const regra = m.chave === "tmea" ? "comparado com a média do setor (30 dias)" : meta ? `limite ${fmt(meta)}` : "sem limite definido";
  card.append(
    el("div", { class: "section-head" },
      el("div", {}, el("div", { class: "section-marker", text: `${m.titulo} · ${m.nome}` }), el("div", { class: "muted", text: regra })),
      el("span", { class: `resumo-n ${acima ? (m.chave === "tmea" ? "warn" : "bad") : "ok"}`, text: linhas.length ? `${acima} de ${linhas.length} ${m.chave === "tmea" ? "acima do setor" : "acima do limite"}` : "" })
    )
  );
  if (!linhas.length) {
    card.append(el("p", { class: "muted", text: "Ninguém com atendimentos suficientes hoje." }));
    return card;
  }
  const visiveis = prefs.todos ? linhas : linhas.slice(0, TOP);
  const tbody = el("tbody");
  visiveis.forEach((l, i) => {
    const excedeu = l.excesso !== null && l.excesso > 0;
    const classe = l.excesso === null ? "" : excedeu ? (m.chave === "tmea" ? "warn" : "bad") : "ok";
    const nome = el("button", { type: "button", class: "link", title: "Abrir os atendimentos de hoje dessa pessoa", text: l.a.nome });
    nome.addEventListener("click", () => abrirDetalhe(l.a.nome));
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
  for (const b of document.querySelectorAll(".chip[data-sistema]")) b.setAttribute("aria-pressed", String(b.dataset.sistema === prefs.sistema));
  const alvo = $("rankings");
  alvo.innerHTML = "";
  const resp = dados[prefs.sistema];
  if (!resp) return;
  if (resp.erro) {
    alvo.append(el("div", { class: "card" }, el("div", { class: "error", text: resp.erro })));
    $("sub").textContent = "";
    return;
  }
  desenharSetores(resp);
  for (const m of METRICAS) alvo.append(cardMetrica(resp, m));
  const hora = resp.ultimoRegistro ? resp.ultimoRegistro.slice(11, 16) : "—";
  $("sub").textContent = `${resp.atendentes.length} atendente(s) com atendimentos hoje · dados até ${hora} · atualizado às ${new Date().toLocaleTimeString("pt-BR")}`;
}

async function carregar() {
  const btn = $("atualizar-btn");
  btn.disabled = true;
  btn.textContent = "Atualizando…";
  const res = await Promise.allSettled(SISTEMAS.map((s) => SebratelApi.request(s.base(), "/ext/ofensores")));
  res.forEach((r, i) => {
    dados[SISTEMAS[i].chave] = r.status === "fulfilled"
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
$("minimo").addEventListener("change", (e) => {
  prefs.minimo = Math.max(1, Number(e.target.value) || 1);
  e.target.value = prefs.minimo;
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
  prefs = { ...prefs, ...(r[PREFS_KEY] || {}) };
  $("minimo").value = prefs.minimo;
  $("todos").checked = prefs.todos;
  carregar().catch((err) => {
    $("sub").textContent = err.message;
  });
});
// Os números de hoje mudam a cada minuto no servidor.
setInterval(() => carregar().catch(() => {}), 60_000);
