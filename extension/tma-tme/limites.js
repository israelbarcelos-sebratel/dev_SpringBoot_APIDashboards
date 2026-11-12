/*
 * Tela do administrador: limites de produtividade (TMA, TME e demais tempos) de cada sistema
 * (GET/PUT/DELETE /ext/limites na API Native e na Matrix). Acima do limite o widget mostra o tempo em
 * vermelho com "▲". O valor vale para todos os atendentes do sistema; sem alteração vale o padrão do
 * servidor. O TMEA não tem limite fixo: é comparado com a média do setor.
 */
const $ = (id) => document.getElementById(id);

const SISTEMAS = [
  { chave: "native", titulo: "Native", base: () => SebratelApi.NATIVE },
  { chave: "matrix", titulo: "Matrix", base: () => SebratelApi.MATRIX },
];
const ROTULOS = {
  tma: "TMA · tempo médio de atendimento",
  tme: "TME · tempo médio de espera",
  tmic: "TMIC · tempo médio de interação do cliente",
  tmia: "TMIA · tempo médio de interação do atendente",
};

/** Resposta de cada sistema ({ sistema, itens }) ou { erro }. */
const dados = {};

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

function msg(texto, tipo = "ok") {
  const m = $("msg");
  m.textContent = texto;
  m.className = `msg ${tipo}`;
}

/** 1800 -> "30:00", 45 -> "0:45", 5400 -> "1:30:00". */
function fmt(seg) {
  seg = Math.round(seg);
  const h = Math.floor(seg / 3600);
  const m = Math.floor((seg % 3600) / 60);
  const s = seg % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

/** "30:00" / "0:45" / "1:30:00" -> segundos; null se o formato não for esse (ou for zero). */
function lerTempo(texto) {
  const m = /^\s*(?:(\d+):)?(\d{1,3}):([0-5]\d)\s*$/.exec(texto);
  if (!m) return null;
  const seg = Number(m[1] || 0) * 3600 + Number(m[2]) * 60 + Number(m[3]);
  return seg > 0 && seg <= 86400 ? seg : null;
}

function quando(texto) {
  const m = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})/.exec(texto || "");
  return m ? `${m[3]}/${m[2]}/${m[1]} ${m[4]}:${m[5]}` : texto || "";
}

function pedido(sis, caminho, opts) {
  return SebratelApi.request(sis.base(), caminho, opts);
}

async function aplicar(sis, caminho, opts, ok) {
  msg("Salvando…", "");
  try {
    dados[sis.chave] = await pedido(sis, caminho, opts);
    msg(ok, "ok");
  } catch (err) {
    msg(err.message, "erro");
  }
  desenhar();
}

function linhaDoItem(sis, item) {
  const input = el("input", {
    type: "text",
    inputmode: "numeric",
    "aria-label": `Limite de ${item.chave.toUpperCase()} na ${sis.titulo}`,
    placeholder: item.meta ? "" : "sem limite",
    value: item.meta ? fmt(item.meta) : "",
    autocomplete: "off",
  });
  input.className = "limite-input";

  const salvar = el("button", { class: "btn btn-primary btn-sm", type: "button", text: "Salvar" });
  const semLimite = el("button", { class: "btn btn-secondary btn-sm", type: "button", text: "Sem limite" });
  const restaurar = el("button", { class: "btn btn-secondary btn-sm", type: "button", text: "Voltar ao padrão" });
  salvar.disabled = true;
  semLimite.disabled = !item.meta;
  restaurar.disabled = !item.alterado;

  const mudou = () => {
    const seg = lerTempo(input.value);
    salvar.disabled = seg === null || seg === item.meta;
    input.classList.toggle("invalido", input.value.trim() !== "" && seg === null);
  };
  input.addEventListener("input", mudou);
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !salvar.disabled) salvar.click();
  });
  const nome = item.chave.toUpperCase();
  salvar.addEventListener("click", () => {
    const seg = lerTempo(input.value);
    if (seg === null) {
      msg("Use o formato mm:ss (ex.: 30:00 ou 0:45).", "erro");
      return;
    }
    aplicar(sis, "/ext/limites", { method: "PUT", body: { metrica: item.chave, segundos: seg } }, `${nome} na ${sis.titulo}: limite de ${fmt(seg)}.`);
  });
  semLimite.addEventListener("click", () =>
    aplicar(sis, "/ext/limites", { method: "PUT", body: { metrica: item.chave, segundos: null } }, `${nome} na ${sis.titulo}: sem limite.`)
  );
  restaurar.addEventListener("click", () =>
    aplicar(sis, `/ext/limites/${encodeURIComponent(item.chave)}`, { method: "DELETE" }, `${nome} na ${sis.titulo}: voltou ao padrão (${item.padrao ? fmt(item.padrao) : "sem limite"}).`)
  );

  const situacao = item.alterado
    ? el("span", { class: "badge b-manual", text: "Alterado", title: `Por ${item.atualizadoPor} em ${quando(item.atualizadoEm)}` })
    : el("span", { class: "badge b-igual", text: "Padrão" });

  return el(
    "tr",
    {},
    el("td", { class: "nome", title: item.formula || "" }, el("b", { text: ROTULOS[item.chave] || nome }), el("div", { class: "muted", text: item.formula || "" })),
    el("td", { class: "metric", text: item.padrao ? fmt(item.padrao) : "sem limite" }),
    el("td", { class: "metric" }, input),
    el("td", { class: "situacao" }, situacao, item.alterado ? el("div", { class: "muted", text: `${item.atualizadoPor} · ${quando(item.atualizadoEm)}` }) : null),
    el("td", { class: "acoes" }, salvar, semLimite, restaurar)
  );
}

function desenharSistema(sis) {
  const card = $(`sis-${sis.chave}`);
  card.innerHTML = "";
  card.append(el("div", { class: "section-marker", text: sis.titulo }));
  const d = dados[sis.chave];
  if (!d) {
    card.append(el("p", { class: "muted", text: "Carregando…" }));
    return;
  }
  if (d.erro) {
    card.append(el("div", { class: "error", text: d.erro }));
    return;
  }
  if (!d.itens.length) {
    card.append(el("p", { class: "muted", text: "Nenhuma métrica configurada." }));
    return;
  }
  const head = el(
    "tr",
    {},
    el("th", { scope: "col", text: "Métrica" }),
    el("th", { scope: "col", class: "metric", text: "Padrão" }),
    el("th", { scope: "col", class: "metric", text: "Limite (mm:ss)" }),
    el("th", { scope: "col", text: "Situação" }),
    el("th", { scope: "col", text: "" })
  );
  const wrap = el("div", { class: "table-wrap" });
  wrap.append(el("table", {}, el("thead", {}, head), el("tbody", {}, ...d.itens.map((i) => linhaDoItem(sis, i)))));
  card.append(wrap);
}

function desenhar() {
  for (const s of SISTEMAS) desenharSistema(s);
  const n = SISTEMAS.reduce((a, s) => a + (dados[s.chave]?.itens?.filter((i) => i.alterado).length || 0), 0);
  $("sub").textContent = `${n} limite(s) alterado(s) em relação ao padrão`;
}

async function carregar() {
  const btn = $("atualizar-btn");
  btn.disabled = true;
  for (const s of SISTEMAS) delete dados[s.chave];
  desenhar();
  const res = await Promise.allSettled(SISTEMAS.map((s) => pedido(s, "/ext/limites")));
  res.forEach((r, i) => {
    dados[SISTEMAS[i].chave] =
      r.status === "fulfilled" ? r.value : { erro: r.reason?.status === 403 ? "Somente administradores." : r.reason?.message || "Dados indisponíveis" };
  });
  desenhar();
  btn.disabled = false;
}

$("atualizar-btn").addEventListener("click", () => carregar().catch(() => {}));
carregar().catch((err) => {
  $("sub").textContent = err.message;
});
