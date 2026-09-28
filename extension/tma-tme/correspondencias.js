/*
 * Tela do administrador: relaciona os nomes de atendente da Matrix com os da Native
 * (GET/PUT /ext/correspondencias na API Native). O servidor sugere os pares automaticamente; aqui o
 * admin confere os que mudam de setor, desfaz os errados e vincula os que faltam.
 */
const $ = (id) => document.getElementById(id);

const FILTROS = [
  { chave: "conferir", rotulo: "Para conferir", teste: (l) => l.tipo === "par" && l.par.setorDiferente && l.par.origem === "auto" },
  { chave: "auto", rotulo: "Automáticos (grafia diferente)", teste: (l) => l.tipo === "par" && l.par.origem === "auto" },
  { chave: "manual", rotulo: "Manuais", teste: (l) => l.tipo === "par" && l.par.origem === "manual" },
  { chave: "bloqueado", rotulo: "Desfeitos", teste: (l) => l.tipo === "par" && l.par.origem === "bloqueado" },
  { chave: "semMatrix", rotulo: "Só na Matrix", teste: (l) => l.tipo === "soMatrix" },
  { chave: "semNative", rotulo: "Só na Native", teste: (l) => l.tipo === "soNative" },
  { chave: "igual", rotulo: "Mesmo nome", teste: (l) => l.tipo === "par" && l.par.origem === "igual" },
  { chave: "todos", rotulo: "Todos", teste: () => true },
];
const ORIGEM = {
  igual: ["Mesmo nome", "b-igual"],
  auto: ["Automático", "b-auto"],
  manual: ["Manual", "b-manual"],
  bloqueado: ["Desfeito", "b-bloqueado"],
};

let dados = null;
let filtro = "conferir";

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

function req(opts) {
  return SebratelApi.request(SebratelApi.NATIVE, "/ext/correspondencias", opts);
}

function msg(texto, tipo = "ok") {
  const m = $("msg");
  m.textContent = texto;
  m.className = `msg ${tipo}`;
}

/** Pares + nomes sem par, numa lista só (uma linha por item). */
function linhas() {
  const out = dados.pares.map((par) => ({ tipo: "par", par }));
  for (const m of dados.semParMatrix) out.push({ tipo: "soMatrix", matrix: m });
  for (const n of dados.semParNative) out.push({ tipo: "soNative", nativo: n });
  return out;
}

function desenharFiltros() {
  const box = $("filtros");
  box.innerHTML = "";
  const todas = linhas();
  for (const f of FILTROS) {
    const b = el("button", { class: "chip", type: "button", "aria-pressed": String(f.chave === filtro) }, f.rotulo + " ", el("b", { text: String(todas.filter(f.teste).length) }));
    b.addEventListener("click", () => {
      filtro = f.chave;
      desenhar();
    });
    box.append(b);
  }
}

function botao(texto, classe, acao) {
  const b = el("button", { class: `btn btn-sm ${classe}`, type: "button", text: texto });
  b.addEventListener("click", async () => {
    b.disabled = true;
    try {
      await acao();
    } finally {
      b.disabled = false;
    }
  });
  return b;
}

async function ajustar(matrix, nativo, acao, ok) {
  try {
    dados = await req({ method: "PUT", body: { matrix, nativo, acao } });
    msg(ok);
    desenhar();
  } catch (err) {
    msg(err.message, "erro");
  }
}

/** Preenche o formulário "Vincular manualmente" e rola até ele. */
function preparar(matrix, nativo) {
  $("v-matrix").value = matrix || "";
  $("v-native").value = nativo || "";
  $(matrix ? "v-native" : "v-matrix").focus();
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function desenharLista() {
  const f = FILTROS.find((x) => x.chave === filtro);
  const busca = $("busca").value.trim().toLowerCase();
  const lista = linhas()
    .filter(f.teste)
    .filter((l) => {
      if (!busca) return true;
      const txt = l.tipo === "par" ? `${l.par.matrix} ${l.par.nativo}` : l.matrix || l.nativo;
      return txt.toLowerCase().includes(busca);
    });

  const card = $("lista");
  card.innerHTML = "";
  card.append(el("div", { class: "section-head" }, el("div", { class: "section-marker", text: f.rotulo }), el("span", { class: "muted", text: `${lista.length} item(ns)` })));
  if (filtro === "conferir") {
    card.append(
      el("div", {
        class: "notice",
        text: "Pares automáticos em que o setor muda de um sistema para o outro — geralmente é a mesma pessoa que trocou de área, mas vale confirmar.",
      })
    );
  }
  if (!lista.length) {
    card.append(el("p", { class: "muted", text: "Nada aqui." }));
    return;
  }

  const tbody = el("tbody");
  for (const l of lista) {
    const tr = el("tr");
    if (l.tipo === "par") {
      const p = l.par;
      if (p.origem === "bloqueado") tr.className = "bloqueado";
      const [rotulo, classe] = ORIGEM[p.origem] || [p.origem, ""];
      const status = el("td", {}, el("span", { class: `badge ${classe}`, text: rotulo }));
      if (p.setorDiferente) status.append(" ", el("span", { class: "badge b-setor", text: "setor diferente" }));
      const acoes = el("td", { class: "acoes" });
      if (p.origem === "bloqueado") {
        acoes.append(botao("Restaurar", "btn-secondary", () => ajustar(p.matrix, p.nativo, "restaurar", "Par restaurado.")));
      } else if (p.origem === "manual") {
        acoes.append(botao("Remover", "btn-danger", () => ajustar(p.matrix, p.nativo, "desvincular", "Vínculo manual removido — volta o automático, se houver.")));
      } else {
        acoes.append(botao("Não é a mesma pessoa", "btn-danger", () => ajustar(p.matrix, p.nativo, "desvincular", "Par desfeito.")));
      }
      tr.append(el("td", { class: "nome", text: p.matrix }), el("td", { class: "nome", text: p.nativo }), status, acoes);
    } else {
      const soMatrix = l.tipo === "soMatrix";
      tr.append(
        el("td", { class: "nome", text: soMatrix ? l.matrix : "—" }),
        el("td", { class: "nome", text: soMatrix ? "—" : l.nativo }),
        el("td", {}, el("span", { class: "badge b-igual", text: soMatrix ? "Sem par na Native" : "Sem par na Matrix" })),
        el("td", { class: "acoes" }, botao("Vincular…", "btn-secondary", async () => preparar(l.matrix, l.nativo)))
      );
    }
    tbody.append(tr);
  }
  const head = el("tr", {}, ...["Matrix", "Native", "Situação", ""].map((t) => el("th", { text: t, scope: "col" })));
  const wrap = el("div", { class: "table-wrap" });
  wrap.append(el("table", {}, el("thead", {}, head), tbody));
  card.append(wrap);
  card.append(
    el("p", {
      class: "legend",
      text:
        "“Só na Matrix/Native” pode ser normal: nem todo atendente trabalha nos dois sistemas. " +
        "Nomes considerados: quem atendeu nos últimos 90 dias.",
    })
  );
}

function desenhar() {
  desenharFiltros();
  desenharLista();
  const efetivos = dados.pares.filter((p) => p.origem !== "bloqueado").length;
  $("sub").textContent = `${dados.nomesMatrix.length} nomes na Matrix · ${dados.nomesNative.length} na Native · ${efetivos} pares em vigor`;
}

function preencherListas() {
  for (const [id, nomes] of [["dl-matrix", dados.nomesMatrix], ["dl-native", dados.nomesNative]]) {
    const dl = $(id);
    dl.innerHTML = "";
    for (const n of nomes) dl.append(el("option", { value: n }));
  }
}

async function carregar() {
  const btn = $("atualizar-btn");
  btn.disabled = true;
  try {
    dados = await req();
    preencherListas();
    desenhar();
  } catch (err) {
    $("sub").textContent = err.status === 403 ? "Somente administradores." : err.message;
    $("lista").innerHTML = "";
  } finally {
    btn.disabled = false;
  }
}

$("v-btn").addEventListener("click", async () => {
  const matrix = $("v-matrix").value.trim();
  const nativo = $("v-native").value.trim();
  if (!dados.nomesMatrix.includes(matrix) || !dados.nomesNative.includes(nativo)) {
    msg("Escolha os dois nomes da lista (digite e selecione a sugestão).", "erro");
    return;
  }
  $("v-btn").disabled = true;
  await ajustar(matrix, nativo, "vincular", `Vinculado: ${matrix} ↔ ${nativo}.`);
  $("v-btn").disabled = false;
  $("v-matrix").value = "";
  $("v-native").value = "";
});
$("busca").addEventListener("input", () => dados && desenharLista());
$("atualizar-btn").addEventListener("click", () => carregar());

carregar();
