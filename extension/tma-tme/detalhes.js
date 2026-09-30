/*
 * Tabela completa dos atendimentos de hoje (GET /ext/widget/detalhe nas duas APIs): cada linha é um
 * atendimento com as colunas que formam os tempos, e os cards do topo mostram a fórmula e a média —
 * a mesma que aparece no widget. Quem pode ver quem é decidido pelo servidor (admin usa o
 * "Ver dados de" do popup).
 */
const $ = (id) => document.getElementById(id);

const SISTEMAS = [
  { chave: "native", titulo: "Native", base: () => SebratelApi.NATIVE },
  { chave: "matrix", titulo: "Matrix", base: () => SebratelApi.MATRIX },
];
const ROTULOS = { tma: "TMA", tme: "TME", tmic: "TMIC", tmia: "TMIA", tmea: "TMEA" };
// Rótulo da coluna por atendimento (o TMEA de uma linha é o intervalo antes dela).
const ROTULOS_COLUNA = { tmea: "Intervalo antes (TMEA)" };

/** Dados carregados por sistema + ordenação atual da tabela. */
const estado = {};
/** Resumo dos últimos dias por sistema (GET /ext/widget/resumo) ou { erro }. */
const resumo = {};
const RESUMO_DIAS = [7, 15, 30];
const RESUMO_KEY = "sebratelResumoDias";
let resumoDias = 7;

function fmt(seg) {
  if (seg === null || seg === undefined) return "—";
  seg = Math.round(seg);
  const h = Math.floor(seg / 3600);
  const m = Math.floor((seg % 3600) / 60);
  const s = seg % 60;
  const mmss = `${String(m).padStart(h ? 2 : 1, "0")}:${String(s).padStart(2, "0")}`;
  return h ? `${h}:${mmss}` : mmss;
}

/**
 * Limites por sistema vêm do servidor (metricas[].meta): dentro verde, acima vermelho. O TMEA não
 * tem limite: a média do dia é comparada com a do setor (acima = âmbar); por linha fica neutro.
 */
function nivel(m, seg, porLinha = false) {
  if (seg === null || seg === undefined) return "empty";
  if (m.meta) return seg <= m.meta ? "ok" : "bad";
  if (m.referencia && !porLinha) return seg <= m.referencia.segundosMedios ? "ok" : "warn";
  return "";
}

function grupoReferencia(ref) {
  return ref.setor ? `setor ${ref.setor}` : "operação";
}

/** "2026-09-28 14:03:21" de hoje vira "14:03:21"; outros valores ficam como vieram. */
function valorColuna(v) {
  if (v === null || v === undefined || v === "") return "—";
  const m = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})/.exec(v);
  return m ? m[2] : v;
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

function colunasDaTabela(dados) {
  const cols = [];
  const variosNomes = new Set(dados.linhas.map((l) => l.atendente)).size > 1;
  if (variosNomes) cols.push({ chave: "atendente", rotulo: "Atendente", tipo: "texto" });
  for (const c of dados.colunas) cols.push({ ...c, tipo: "texto" });
  for (const m of dados.metricas) {
    const rotulo = ROTULOS_COLUNA[m.chave] || ROTULOS[m.chave] || m.chave.toUpperCase();
    cols.push({ chave: m.chave, rotulo, tipo: "metrica", metrica: m });
  }
  return cols;
}

function valorOrdenacao(linha, col) {
  if (col.tipo === "metrica") return linha.tempos[col.chave] ?? -1;
  return linha[col.chave] ?? "";
}

function linhasVisiveis(sis) {
  const { dados, ordem } = estado[sis];
  const busca = $("busca").value.trim().toLowerCase();
  let linhas = dados.linhas;
  if (busca) {
    linhas = linhas.filter((l) =>
      Object.entries(l).some(([k, v]) => k !== "tempos" && v && String(v).toLowerCase().includes(busca))
    );
  }
  if (ordem) {
    const dir = ordem.dir === "asc" ? 1 : -1;
    linhas = [...linhas].sort((a, b) => {
      const va = valorOrdenacao(a, ordem.col);
      const vb = valorOrdenacao(b, ordem.col);
      return (typeof va === "number" ? va - vb : String(va).localeCompare(String(vb), "pt-BR")) * dir;
    });
  }
  return linhas;
}

function renderKpis(dados) {
  const box = el("div", { class: "kpis" });
  const a = dados.atendimentos;
  if (a) {
    box.append(
      el(
        "div",
        { class: "kpi kpi-total" },
        el("div", { class: "kpi-label", text: "ATENDIMENTOS" }),
        el("div", { class: "kpi-value", text: `${a.hoje.toLocaleString("pt-BR")} hoje` }),
        el("div", {
          class: "kpi-formula",
          text: `${a.mes === null || a.mes === undefined ? "…" : a.mes.toLocaleString("pt-BR")} do dia 1º do mês até agora`,
        })
      )
    );
  }
  for (const m of dados.metricas) {
    const rotulo = ROTULOS[m.chave] || m.chave.toUpperCase();
    const n = m.amostras;
    const total = dados.linhas.length;
    box.append(
      el(
        "div",
        { class: "kpi" },
        el("div", { class: "kpi-label", text: `${rotulo} · média de hoje` }),
        el("div", { class: `kpi-value ${nivel(m, m.segundosMedios)}`, text: excedido(m, m.segundosMedios) }),
        el("div", { class: "kpi-formula", text: m.formula || "" }),
        el("div", { class: "kpi-n", text: textoAmostras(m, n, total) })
      )
    );
  }
  return box;
}

/** Valor com "▲" e quanto passou quando excede o limite: "▲ 6:12 (+1:12)". */
function excedido(m, seg) {
  if (m.meta && seg !== null && seg !== undefined && seg > m.meta) {
    return `▲ ${fmt(seg)} (+${fmt(seg - m.meta)})`;
  }
  return fmt(seg);
}

function textoAmostras(m, n, total) {
  if (m.chave === "tmea") {
    let t = `${n} intervalo(s) entre atendimentos hoje`;
    if (m.referencia) {
      const ref = m.referencia;
      t += ` · média do ${grupoReferencia(ref)}: ${fmt(ref.segundosMedios)} (${ref.atendentes} colega(s), últimos ${ref.dias} dias)`;
      if (m.segundosMedios !== null && m.segundosMedios !== undefined) {
        const dif = Math.round(m.segundosMedios - ref.segundosMedios);
        t += dif <= 0 ? ` · ${fmt(-dif)} abaixo` : ` · ${fmt(dif)} acima`;
      }
    }
    return t;
  }
  return `${n} atendimento(s) na média${total > n ? ` · ${total - n} sem esse tempo (não contam)` : ""}${
    m.meta ? ` · limite ${fmt(m.meta)}` : ""
  }`;
}

function renderTabela(sis) {
  const { dados, ordem } = estado[sis];
  const cols = colunasDaTabela(dados);
  const linhas = linhasVisiveis(sis);

  const trHead = el("tr");
  for (const c of cols) {
    const th = el("th", { class: c.tipo === "metrica" ? "metric" : "", text: c.rotulo, scope: "col" });
    if (ordem && ordem.col.chave === c.chave) th.setAttribute("aria-sort", ordem.dir === "asc" ? "ascending" : "descending");
    th.addEventListener("click", () => {
      const atual = estado[sis].ordem;
      const dir = atual && atual.col.chave === c.chave && atual.dir === "desc" ? "asc" : "desc";
      estado[sis].ordem = { col: c, dir };
      desenharSecao(sis);
    });
    trHead.append(th);
  }

  const tbody = el("tbody");
  for (const l of linhas) {
    const tr = el("tr");
    for (const c of cols) {
      if (c.tipo === "metrica") {
        const v = l.tempos[c.chave];
        const td = el("td", { class: `metric ${nivel(c.metrica, v, true)}`, text: fmt(v) });
        if (v === null || v === undefined) {
          td.title =
            c.chave === "tmea"
              ? "Primeiro atendimento do dia ou intervalo acima do limite (pausa/almoço) — não entra no TMEA"
              : "Sem esse tempo neste atendimento — não entra na média";
        } else if (c.metrica.meta && v > c.metrica.meta) {
          td.title = `Acima do limite de ${fmt(c.metrica.meta)} (+${fmt(v - c.metrica.meta)})`;
        }
        tr.append(td);
      } else {
        const bruto = l[c.chave];
        const td = el("td", { class: c.chave === "cliente" ? "cliente" : "", text: valorColuna(bruto) });
        if (bruto) td.title = bruto;
        tr.append(td);
      }
    }
    tbody.append(tr);
  }

  // Rodapé: média das linhas visíveis (com filtro, é a média do filtro; sem filtro, bate com o widget).
  const trFoot = el("tr");
  cols.forEach((c, i) => {
    if (c.tipo !== "metrica") {
      trFoot.append(el("td", { text: i === 0 ? `Média (${linhas.length} linha(s))` : "" }));
      return;
    }
    const vals = linhas.map((l) => l.tempos[c.chave]).filter((v) => v !== null && v !== undefined);
    const media = vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null;
    trFoot.append(el("td", { class: `metric ${nivel(c.metrica, media)}`, text: fmt(media) }));
  });

  const wrap = el("div", { class: "table-wrap" });
  wrap.append(el("table", {}, el("thead", {}, trHead), tbody, el("tfoot", {}, trFoot)));
  return wrap;
}

function desenharSecao(sis) {
  const cfg = SISTEMAS.find((s) => s.chave === sis);
  const card = $(`secao-${sis}`);
  card.innerHTML = "";
  const st = estado[sis];

  const head = el("div", { class: "section-head" }, el("div", { class: "section-marker", text: `${cfg.titulo} · hoje` }));
  if (st.dados?.ultimoRegistro) {
    head.append(el("span", { class: "muted", text: `dados até ${valorColuna(st.dados.ultimoRegistro).slice(0, 5)}` }));
  }
  card.append(head);

  if (st.erro) {
    card.append(el("div", { class: "error", text: st.erro }));
    return;
  }
  const dados = st.dados;
  card.append(renderKpis(dados));
  if (!dados.linhas.length) {
    card.append(el("p", { class: "muted", text: "Nenhum atendimento hoje ainda." }));
    return;
  }
  if (dados.truncado) {
    card.append(el("div", { class: "notice", text: `Mostrando os ${dados.linhas.length} atendimentos mais recentes.` }));
  }
  card.append(renderTabela(sis));
  card.append(
    el("p", {
      class: "legend",
      text:
        "Tempos em minutos:segundos. “—” = o atendimento não tem esse tempo (ex.: ainda em andamento) e não entra na média. " +
        "TMA/TME: verde dentro do limite de produtividade, vermelho (▲) acima. " +
        "TMEA: tempo sem atendimento entre um e outro, comparado com a média dos colegas do setor nos últimos 30 dias (verde abaixo, âmbar acima). " +
        "Clique no título de uma coluna para ordenar.",
    })
  );
}

function hm(s) {
  if (s === null || s === undefined) return "—";
  s = Math.round(s);
  return `${Math.floor(s / 3600)}h${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}`;
}

const SEMANA = ["dom", "seg", "ter", "qua", "qui", "sex", "sáb"];

/** Cabeçalho de um dia: "qua" em cima, "24/09" (ou "hoje") embaixo. */
function thDia(dia, hoje) {
  const [a, m, d] = dia.split("-").map(Number);
  const semana = SEMANA[new Date(a, m - 1, d).getDay()];
  return el("th", { scope: "col", class: "metric" }, el("span", { class: "dia-semana", text: semana }), dia === hoje ? "hoje" : `${String(d).padStart(2, "0")}/${String(m).padStart(2, "0")}`);
}

/**
 * Itens da tabela, na ordem pedida: TMA, TME, tempo logado Native, tempo logado Matrix, tempo em
 * pausa e atendimentos. Com os dois sistemas, TMA/TME/pausa/atendimentos aparecem um por sistema.
 */
function itensResumo(ativos) {
  const suf = (s) => (ativos.length > 1 ? ` · ${s.titulo}` : "");
  const itens = [];
  const tempo = (chave) => ativos.forEach((s, i) => itens.push({ sis: s, tipo: "tempo", chave, rotulo: `${ROTULOS[chave]}${suf(s)}`, grupo: i === 0 }));
  tempo("tma");
  tempo("tme");
  ativos.filter((s) => resumo[s.chave].temLogado).forEach((s, i) => itens.push({ sis: s, tipo: "logado", rotulo: `Tempo logado ${s.titulo}`, grupo: i === 0 }));
  ativos.filter((s) => resumo[s.chave].temPausa).forEach((s, i) => itens.push({ sis: s, tipo: "pausa", rotulo: `Tempo em pausa${suf(s)}`, grupo: i === 0 }));
  ativos.forEach((s, i) => itens.push({ sis: s, tipo: "atendimentos", rotulo: `Atendimentos${suf(s)}`, grupo: i === 0 }));
  return itens;
}

/** Célula de um item: um dia (`d` = linha do dia, ou undefined sem atividade) ou uma média (`m`). */
function celulaResumo(item, r, { d, m, titulo }) {
  const meta = r.metas?.[item.chave];
  if (item.tipo === "tempo") {
    const v = d ? d.tempos?.[item.chave]?.segundosMedios : m?.tempos?.[item.chave];
    const td = el("td", { class: `metric ${v === null || v === undefined ? "empty" : meta ? (v <= meta ? "ok" : "bad") : ""}`, text: fmt(v) });
    const n = d?.tempos?.[item.chave]?.amostras;
    td.title = [titulo, n ? `${n} atendimento(s) com esse tempo` : null, meta ? `limite ${fmt(meta)}` : null].filter(Boolean).join(" · ");
    return td;
  }
  if (item.tipo === "atendimentos") {
    const v = d ? d.atendimentos : m?.atendimentos;
    const txt = v === null || v === undefined ? "—" : d ? String(v) : v.toLocaleString("pt-BR", { maximumFractionDigits: 1 });
    return el("td", { class: `metric${txt === "—" ? " empty" : ""}`, title: titulo || "", text: txt });
  }
  const v = d ? d[item.tipo === "logado" ? "logadoSegundos" : "pausaSegundos"] : m?.[item.tipo === "logado" ? "logadoSegundos" : "pausaSegundos"];
  const est = d && item.tipo === "logado" && d.estimadoSegundos > 0;
  const td = el("td", { class: `metric${v === null || v === undefined ? " empty" : ""}`, text: `${est ? "≈" : ""}${hm(v)}` });
  td.title = [titulo, est ? `inclui ${hm(d.estimadoSegundos)} da sessão atual, estimada pela atividade (a Native só grava a sessão no logoff)` : null].filter(Boolean).join(" · ");
  return td;
}

function desenharResumo() {
  const card = $("resumo");
  card.innerHTML = "";
  const chips = el("span", { class: "chips", role: "group", "aria-label": "Período" });
  for (const n of RESUMO_DIAS) {
    const b = el("button", { type: "button", class: "chip", "aria-pressed": String(n === resumoDias), text: `${n} dias` });
    b.addEventListener("click", () => {
      if (n === resumoDias) return;
      resumoDias = n;
      try {
        chrome.storage.local.set({ [RESUMO_KEY]: n });
      } catch {
        /* só conveniência */
      }
      carregarResumo(alvoAtual).catch(() => {});
    });
    chips.append(b);
  }
  card.append(el("div", { class: "section-head" }, el("div", { class: "section-marker", text: `Resumo dos últimos ${resumoDias} dias` }), chips));

  const respostas = SISTEMAS.map((s) => resumo[s.chave]);
  if (respostas.every((r) => !r)) {
    card.append(el("p", { class: "muted", text: "Carregando…" }));
    return;
  }
  const ativos = SISTEMAS.filter((s) => resumo[s.chave] && !resumo[s.chave].erro && resumo[s.chave].linhas.length);
  if (!ativos.length) {
    const erro = respostas.find((r) => r?.erro)?.erro;
    card.append(el(erro ? "div" : "p", { class: erro ? "error" : "muted", text: erro || "Nenhuma atividade no período." }));
    return;
  }
  const hoje = resumo[ativos[0].chave].hoje;
  const dias = [...new Set(ativos.flatMap((s) => resumo[s.chave].linhas.map((l) => l.dia)))].sort();
  const porDia = Object.fromEntries(ativos.map((s) => [s.chave, Object.fromEntries(resumo[s.chave].linhas.map((l) => [l.dia, l]))]));

  const head = el("tr", {}, el("th", { scope: "col", text: "Item" }), ...dias.map((d) => thDia(d, hoje)),
    el("th", { scope: "col", class: "metric media", text: "Sua média" }),
    el("th", { scope: "col", class: "metric media", text: "Média do setor" }));
  const tbody = el("tbody");
  for (const item of itensResumo(ativos)) {
    const r = resumo[item.sis.chave];
    const ref = r.setor;
    const grupoRef = ref.setor ? `setor ${ref.setor}` : "operação";
    const tr = el("tr", { class: item.grupo ? "grupo" : "" }, el("td", { class: "item", text: item.rotulo }));
    for (const dia of dias) tr.append(celulaResumo(item, r, { d: porDia[item.sis.chave][dia] || null, titulo: dia === hoje ? "hoje, até agora" : dia.split("-").reverse().join("/") }));
    const tdVoce = celulaResumo(item, r, { m: r.voce, titulo: "Sua média no período" });
    // Outliers ficam fora só da média do setor (cada um continua vendo os próprios números completos).
    const chave = item.tipo === "tempo" ? item.chave : item.tipo === "logado" ? "logadoSegundos" : item.tipo === "pausa" ? "pausaSegundos" : "atendimentos";
    const base = ref.base?.[chave] ?? ref.atendentes;
    const fora = ref.outliers?.[chave] || 0;
    const tdSetor = celulaResumo(item, r, {
      m: ref,
      titulo: `Média de ${base} colega(s) do ${grupoRef} (${item.sis.titulo}) no mesmo período` +
        (fora === 1 ? " · 1 colega não entrou por estar muito acima ou abaixo do padrão do setor (outlier)"
          : fora > 1 ? ` · ${fora} colegas não entraram por estarem muito acima ou abaixo do padrão do setor (outliers)` : ""),
    });
    tdVoce.classList.add("media");
    tdSetor.classList.add("media");
    tr.append(tdVoce, tdSetor);
    tbody.append(tr);
  }
  const wrap = el("div", { class: "table-wrap" });
  wrap.append(el("table", {}, el("thead", {}, head), tbody));
  card.append(wrap);
  const setores = ativos.map((s) => `${s.titulo}: ${resumo[s.chave].setor.setor || "operação toda"} (${resumo[s.chave].setor.atendentes} colega(s))`).join(" · ");
  card.append(el("p", {
    class: "legend",
    text: "Cada coluna é um dia com atividade (dias sem atendimento nem login não aparecem). " +
      "As médias não contam hoje (dia em andamento). Sua média: atendimentos por dia com atendimento, TMA/TME sobre todos os atendimentos do período, pausa por dia com atendimento e tempo logado por dia com login. " +
      `Média do setor: a mesma conta para cada colega do seu setor no mesmo período, cada um pesando igual — ${setores}. ` +
      "Em cada item, quem está muito fora do padrão do setor (outlier: acima de Q3 + 1,5×IQR ou abaixo de Q1 − 1,5×IQR) não entra na média do setor — " +
      "mas os números de cada pessoa aparecem sempre completos na tabela dela. Passe o mouse na média do setor para ver quantos colegas entraram na conta. " +
      "Tempo em pausa: pausas encerradas, no dia em que começaram. ≈: a Native só grava a sessão no logoff; a de hoje é estimada da primeira ligação ou pausa depois do último logoff. " +
      "TMA/TME: verde dentro do limite, vermelho acima.",
  }));
}

let alvoAtual = "";

async function carregarResumo(alvo) {
  for (const s of SISTEMAS) delete resumo[s.chave];
  desenharResumo();
  const q = `?dias=${resumoDias}${alvo ? `&atendente=${encodeURIComponent(alvo)}` : ""}`;
  const res = await Promise.allSettled(SISTEMAS.map((s) => SebratelApi.request(s.base(), `/ext/widget/resumo${q}`)));
  res.forEach((r, i) => {
    resumo[SISTEMAS[i].chave] = r.status === "fulfilled" ? r.value : { erro: r.reason?.message || "Dados indisponíveis" };
  });
  desenharResumo();
}

async function carregar() {
  const btn = $("atualizar-btn");
  btn.disabled = true;
  btn.textContent = "Atualizando…";
  const cfg = await SebratelApi.getConfig();
  const alvo = new URLSearchParams(location.search).get("atendente") || cfg.viewingAgent || "";
  alvoAtual = alvo;
  const pedidoResumo = carregarResumo(alvo).catch(() => {});

  const secoes = $("secoes");
  if (!secoes.children.length) {
    for (const s of SISTEMAS) secoes.append(el("div", { class: "card", id: `secao-${s.chave}` }));
  }

  const resultados = await Promise.allSettled(SISTEMAS.map((s) => SebratelApi.detalhe(s.base(), alvo)));
  let nome = null;
  resultados.forEach((r, i) => {
    const sis = SISTEMAS[i].chave;
    const ordem = estado[sis]?.ordem || null;
    if (r.status === "fulfilled") {
      estado[sis] = { dados: r.value, ordem };
      nome = nome || r.value.nomes.join(", ");
    } else {
      estado[sis] = { erro: r.reason?.message || "Dados indisponíveis", ordem };
    }
    desenharSecao(sis);
  });

  await pedidoResumo;
  const agora = new Date().toLocaleTimeString("pt-BR");
  $("quem").textContent = nome
    ? `${nome}${alvo ? " (visão de administrador)" : ""} · atualizado às ${agora}`
    : `Não foi possível carregar · ${agora}`;
  btn.disabled = false;
  btn.textContent = "Atualizar";
}

function csvCampo(v) {
  const s = v === null || v === undefined ? "" : String(v);
  return /[";\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** CSV (separador ;, para abrir direto no Excel pt-BR) das linhas visíveis dos dois sistemas. */
function exportarCsv() {
  const linhasCsv = [];
  for (const s of SISTEMAS) {
    const st = estado[s.chave];
    if (!st?.dados?.linhas.length) continue;
    const cols = colunasDaTabela(st.dados);
    linhasCsv.push([s.titulo]);
    linhasCsv.push(cols.map((c) => (c.tipo === "metrica" ? `${c.rotulo} (seg)` : c.rotulo)));
    for (const l of linhasVisiveis(s.chave)) {
      linhasCsv.push(cols.map((c) => (c.tipo === "metrica" ? l.tempos[c.chave] : l[c.chave])));
    }
    linhasCsv.push([]);
  }
  if (!linhasCsv.length) return;
  const texto = "﻿" + linhasCsv.map((l) => l.map(csvCampo).join(";")).join("\r\n");
  const url = URL.createObjectURL(new Blob([texto], { type: "text/csv;charset=utf-8" }));
  const a = el("a", { href: url, download: `atendimentos-hoje-${new Date().toISOString().slice(0, 10)}.csv` });
  document.body.append(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

$("atualizar-btn").addEventListener("click", () => carregar().catch(() => {}));
$("csv-btn").addEventListener("click", exportarCsv);
$("busca").addEventListener("input", () => {
  for (const s of SISTEMAS) if (estado[s.chave]?.dados) desenharSecao(s.chave);
});

chrome.storage.local.get([RESUMO_KEY], (r) => {
  if (RESUMO_DIAS.includes(r?.[RESUMO_KEY])) resumoDias = r[RESUMO_KEY];
  carregar().catch((err) => {
    $("quem").textContent = err.message;
  });
});
