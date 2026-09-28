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

async function carregar() {
  const btn = $("atualizar-btn");
  btn.disabled = true;
  btn.textContent = "Atualizando…";
  const cfg = await SebratelApi.getConfig();
  const alvo = cfg.viewingAgent || "";

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

carregar().catch((err) => {
  $("quem").textContent = err.message;
});
