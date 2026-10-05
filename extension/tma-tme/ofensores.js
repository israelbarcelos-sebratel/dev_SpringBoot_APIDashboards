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
  nova: { titulo: "TMEA pela regra nova", quando: "no período", minimo: 3 },
  pausas: { titulo: "pausas e comportamentos", quando: "no período", minimo: 1 },
};
const PREFS_KEY = "sebratelOfensores";

const dados = {}; // "periodo:sistema" -> resposta ou { erro }
/** Pares de nomes Native <-> Matrix em vigor (tela "Relacionar nomes"): { native: Map, matrix: Map }. */
let correspondencia = null;
/** Linhas abertas (▸) na aba Pausas, por nome: continuam abertas quando os dados se atualizam. */
const expandidos = new Set();
/** Tipos de pausa abertos (▸) no card "Pausas por tipo", pelo nome do tipo. */
const tiposAbertos = new Set();
/** Atualização automática que chegou enquanto a pessoa mexia num filtro: aplicada quando ela sair dele. */
let redesenhoPendente = false;
let ultimaCarga = 0;
// minimo por período: 3 atendimentos num dia não é o mesmo que 3 em 30 dias.
let prefs = {
  periodo: "hoje",
  periodoNova: "hoje", // período dentro da aba "TMEA · regra nova"
  sistema: "native",
  minimos: { hoje: 3, "30d": 20, "nova-hoje": 3, "nova-30d": 20, "pausas-hoje": 1, "pausas-30d": 20 },
  setor: "",
  turno: "", // da planilha da equipe do suporte (só quem está nela tem turno)
  supervisor: "", // só com um setor de suporte escolhido
  todos: false,
  ordem: {}, // tabela -> { col, dir }: coluna escolhida no cabeçalho (sem = ordem padrão da tabela)
};
/** Chave dos dados/mínimo: "hoje", "30d", "nova-hoje" ou "nova-30d". */
const temSubPeriodo = () => prefs.periodo === "nova" || prefs.periodo === "pausas";
const chavePeriodo = () => (temSubPeriodo() ? `${prefs.periodo}-${prefs.periodoNova}` : prefs.periodo);
const minimo = () => prefs.minimos[chavePeriodo()] ?? (chavePeriodo().endsWith("30d") ? 20 : 3);

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
 * Ordena pela coluna escolhida no cabeçalho da `tabela` (antes de cortar os N primeiros, para o corte
 * pegar os certos). Vazios sempre no fim; sem escolha, fica a ordem padrão.
 * Coluna: { chave, titulo, valor(linha) -> número|texto|null, texto?, inicial? }.
 */
function ordenar(linhas, tabela, colunas) {
  const o = prefs.ordem?.[tabela];
  const c = o && colunas.find((x) => x.chave === o.col && x.valor);
  if (!c) return linhas;
  const dir = o.dir === "asc" ? 1 : -1;
  const vazio = (v) => v === null || v === undefined || v === "" || (typeof v === "number" && !Number.isFinite(v));
  return [...linhas].sort((a, b) => {
    const va = c.valor(a);
    const vb = c.valor(b);
    if (vazio(va) || vazio(vb)) return vazio(va) === vazio(vb) ? 0 : vazio(va) ? 1 : -1;
    return (typeof va === "number" && typeof vb === "number" ? va - vb : String(va).localeCompare(String(vb), "pt-BR")) * dir;
  });
}

/** Direção do 1º clique: números do maior para o menor, textos de A a Z. */
const direcaoInicial = (c) => c.inicial || (c.texto ? "asc" : "desc");

/** Cabeçalho clicável: 1º clique ordena, 2º inverte, 3º volta à ordem padrão da tabela. */
function cabecalho(tabela, colunas) {
  const o = prefs.ordem?.[tabela];
  return el("tr", {}, ...colunas.map((c) => {
    const th = el("th", { scope: "col", class: c.classe || "", text: c.titulo });
    if (!c.valor) {
      th.style.cursor = "default";
      return th;
    }
    const ativa = o?.col === c.chave;
    if (ativa) th.setAttribute("aria-sort", o.dir === "asc" ? "ascending" : "descending");
    th.tabIndex = 0;
    th.title = ativa && o.dir !== direcaoInicial(c) ? "Clique para voltar à ordem padrão" : `Ordenar por ${c.titulo || "esta coluna"}`;
    const clicar = () => {
      const ini = direcaoInicial(c);
      const ordem = { ...(prefs.ordem || {}) };
      if (!ativa) ordem[tabela] = { col: c.chave, dir: ini };
      else if (o.dir === ini) ordem[tabela] = { col: c.chave, dir: ini === "asc" ? "desc" : "asc" };
      else delete ordem[tabela];
      prefs.ordem = ordem;
      salvarPrefs();
      redesenharMantendo(false);
    };
    th.addEventListener("click", clicar);
    th.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        clicar();
      }
    });
    return th;
  }));
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
    if (!noFiltro(a)) continue;
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
  linhas.forEach((l, i) => (l.pos = i + 1)); // "#" = posição na ordem padrão (pior primeiro)
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
  const tabela = `rank-${m.chave}`;
  const colunas = [
    { chave: "pos", titulo: "#", inicial: "asc", valor: (l) => l.pos },
    { chave: "nome", titulo: "Atendente", texto: true, valor: (l) => l.a.nome },
    { chave: "valor", titulo: m.titulo, classe: "metric", valor: (l) => l.valor },
    { chave: "dif", titulo: m.chave === "tmea" ? "vs. setor" : "vs. limite", classe: "metric", valor: (l) => l.excesso },
    { chave: "n", titulo: "Atend.", classe: "metric", valor: (l) => l.a.atendimentos },
  ];
  const ordenadas = ordenar(linhas, tabela, colunas);
  const visiveis = prefs.todos ? ordenadas : ordenadas.slice(0, TOP);
  const tbody = el("tbody");
  visiveis.forEach((l) => {
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
        el("td", { class: "pos", text: String(l.pos) }),
        el("td", { class: "nome" }, nome),
        el("td", { class: "metric " + classe, text: (excedeu && m.chave !== "tmea" ? "▲ " : "") + fmt(l.valor) }),
        el("td", { class: "metric dif " + classe, title: difTitulo, text: dif }),
        el("td", { class: "metric n", title: m.chave === "tmea" ? `${l.amostras} intervalo(s) entre atendimentos` : `${l.amostras} atendimento(s) com tempo válido`, text: String(l.a.atendimentos) })
      )
    );
  });
  const head = cabecalho(tabela, colunas);
  const wrap = el("div", { class: "table-wrap" });
  wrap.append(el("table", {}, el("thead", {}, head), tbody));
  card.append(wrap);
  if (!prefs.todos && linhas.length > TOP) {
    card.append(el("p", { class: "legend", text: `Mostrando os ${TOP} ${prefs.ordem?.[tabela] ? "primeiros" : "piores"} de ${linhas.length}.` }));
  }
  return card;
}

/** Supervisão só se filtra dentro de um setor de suporte. */
const ehSuporte = () => /suporte/i.test(prefs.setor || "");

/** Filtros cumulativos: setor, turno e (no suporte) supervisão. */
function noFiltro(a) {
  return (!prefs.setor || a.setor === prefs.setor)
    && (!prefs.turno || a.turno === prefs.turno)
    && (!ehSuporte() || !prefs.supervisor || a.supervisor === prefs.supervisor);
}

const ORDEM_TURNOS = ["Manhã", "Intermediário", "Tarde", "Madrugada"];
function ordemTurno(a, b) {
  const ia = ORDEM_TURNOS.indexOf(a);
  const ib = ORDEM_TURNOS.indexOf(b);
  if (ia !== ib) return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
  return a.localeCompare(b, "pt-BR");
}

/** Opções de um filtro; mesmas opções: não recria (fecharia a lista aberta e perderia o foco). */
function preencher(sel, valores, todos, atual) {
  const atuais = [...sel.options].slice(1).map((o) => o.value);
  if (atuais.length !== valores.length || atuais.some((v, i) => v !== valores[i])) {
    sel.innerHTML = "";
    sel.append(el("option", { value: "", text: todos }));
    for (const v of valores) sel.append(el("option", { value: v, text: v }));
  }
  sel.value = atual;
}

function desenharFiltros(resp) {
  const unicos = (lista, campo) => [...new Set(lista.map((a) => a[campo]).filter(Boolean))];
  const setores = unicos(resp.atendentes, "setor").sort((a, b) => a.localeCompare(b, "pt-BR"));
  if (prefs.setor && !setores.includes(prefs.setor)) prefs.setor = "";
  preencher($("setor"), setores, "Todos os setores", prefs.setor);

  // Turnos e supervisões de quem está no setor escolhido (só a equipe da planilha tem).
  const doSetor = resp.atendentes.filter((a) => !prefs.setor || a.setor === prefs.setor);
  const turnos = unicos(doSetor, "turno").sort(ordemTurno);
  if (prefs.turno && !turnos.includes(prefs.turno)) prefs.turno = "";
  preencher($("turno"), turnos, "Todos os turnos", prefs.turno);
  const quando = resp.equipeAtualizadaEm ? new Date(resp.equipeAtualizadaEm).toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" }) : null;
  $("turno-filtro").title = quando ? `Turno e supervisão: planilha da equipe do suporte, atualizada em ${quando}.`
    : "Turno e supervisão: planilha da equipe do suporte (ainda não recebida).";

  const supervisores = ehSuporte()
    ? unicos(doSetor.filter((a) => !prefs.turno || a.turno === prefs.turno), "supervisor").sort((a, b) => a.localeCompare(b, "pt-BR"))
    : [];
  if (prefs.supervisor && !supervisores.includes(prefs.supervisor)) prefs.supervisor = "";
  $("supervisor-filtro").hidden = !ehSuporte();
  preencher($("supervisor"), supervisores, "Todas as supervisões", prefs.supervisor);
}

/** A pessoa está num filtro (lista aberta, digitando o mínimo): não redesenhar agora. */
function interagindo() {
  const a = document.activeElement;
  return Boolean(a && ["setor", "turno", "supervisor", "minimo"].includes(a.id));
}

/**
 * Redesenha a aba mantendo o contexto: rolagem da página, rolagem lateral das tabelas e as linhas
 * abertas. Na atualização automática, espera a pessoa sair do filtro em que está mexendo.
 */
function redesenharMantendo(automatico) {
  if (automatico && interagindo()) {
    redesenhoPendente = true;
    return;
  }
  redesenhoPendente = false;
  const y = window.scrollY;
  const lateral = [...document.querySelectorAll("#rankings .table-wrap")].map((w) => w.scrollLeft);
  desenhar();
  document.querySelectorAll("#rankings .table-wrap").forEach((w, i) => {
    if (lateral[i]) w.scrollLeft = lateral[i];
  });
  window.scrollTo(0, y);
}

function desenhar() {
  const per = PERIODOS[prefs.periodo];
  for (const b of document.querySelectorAll(".chip[data-sistema]")) b.setAttribute("aria-pressed", String(b.dataset.sistema === prefs.sistema));
  for (const b of document.querySelectorAll(".periodo")) b.setAttribute("aria-selected", String(b.dataset.periodo === prefs.periodo));
  $("titulo").textContent = prefs.periodo === "nova" ? "TMEA · regra nova (em teste)"
    : prefs.periodo === "pausas" ? "Pausas e comportamentos estranhos" : `Maiores ofensores · ${per.titulo}`;
  $("minimo").value = minimo();
  $("nova-periodos").hidden = !temSubPeriodo();
  for (const b of document.querySelectorAll(".chip[data-nova]")) b.setAttribute("aria-pressed", String(b.dataset.nova === prefs.periodoNova));
  if (prefs.periodo === "nova") {
    desenharNova();
    return;
  }
  if (prefs.periodo === "pausas") {
    desenharPausas();
    return;
  }
  $("legenda").textContent =
    (prefs.periodo === "hoje"
      ? "Mesmos números que cada atendente vê no widget, só de hoje — no começo do dia poucos atendimentos entraram, por isso o mínimo. "
      : "Médias dos últimos 30 dias (inclui hoje), recalculadas a cada 10 minutos. ") +
    "TME e TMA: vermelho acima do limite do sistema. TMEA: âmbar acima da média dos colegas do mesmo setor nos últimos 30 dias. " +
    "Clique no nome para ver os atendimentos de hoje da pessoa.";
  const alvo = $("rankings");
  alvo.innerHTML = "";
  const resp = dados[`${chavePeriodo()}:${prefs.sistema}`];
  if (!resp) {
    $("sub").textContent = "Carregando…";
    return;
  }
  if (resp.erro) {
    alvo.append(el("div", { class: "card" }, el("div", { class: "error", text: resp.erro })));
    $("sub").textContent = "";
    return;
  }
  desenharFiltros(resp);
  for (const m of METRICAS) alvo.append(cardMetrica(resp, m));
  const hora = resp.ultimoRegistro ? resp.ultimoRegistro.slice(11, 16) : "—";
  const total = resp.atendentes.reduce((s, a) => s + a.atendimentos, 0).toLocaleString("pt-BR");
  const calc = resp.calculadoEm ? resp.calculadoEm.slice(11, 16) : "—";
  $("sub").textContent = prefs.periodo === "hoje"
    ? `${resp.atendentes.length} atendente(s) · ${total} atendimentos hoje · dados até ${hora}`
    : `${resp.atendentes.length} atendente(s) · ${total} atendimentos nos últimos ${resp.dias} dias · calculado às ${calc}`;
}

/** Card fixo no topo da aba: o que a regra nova faz, passo a passo, e no que difere da oficial. */
function cardRegras(resp) {
  const corte = resp?.corteAtualMinutos ?? 60;
  const andamento = resp?.maxAndamentoMinutos ?? 120;
  const matrix = prefs.sistema === "matrix";
  const li = (titulo, texto) => el("li", {}, el("b", { text: titulo + " " }), texto);
  return el("div", { class: "card regras" },
    el("h2", { text: "Como estes números são calculados" }),
    el("ol", {},
      li("Intervalo entre atendimentos.", matrix
        ? "Na Matrix os chats correm em paralelo, então o intervalo vai do início de um atendimento até o início do próximo, para cada atendente e dia."
        : "Na Native, vai do fim de uma ligação (data/hora + espera + atendimento) até o atendente atender a próxima, para cada atendente e dia. Ligações que se sobrepõem (transferência) contam 0."),
      li("Sem corte de tempo.", `A regra oficial descarta intervalos acima de ${corte} min — por isso quem atende pouco aparecia com TMEA baixo. Aqui o intervalo conta inteiro.`),
      li("Pausas são descontadas.", `Todo tempo em pausa registrada (${matrix ? "db_matrix_stops" : "evento “Pausa” em db_native_login"}: intervalo, toalete, lanche, pré-saída…) dentro do intervalo sai da conta. Pausa sem fim registrado vale até o próximo registro da pessoa (próxima pausa ou próximo atendimento).`),
      li("Tempo deslogado é descontado.", `Do logoff até o próximo login (${matrix ? "db_matrix_login" : "evento “Sessão” em db_native_login"}) não é ociosidade. Quando o login seguinte não está registrado${matrix ? "" : " (a Native só grava a sessão depois do logoff)"}, o próximo atendimento marca a volta.`),
      li("Intervalo em andamento (só hoje).", `O tempo desde o fim do último atendimento até agora também entra, enquanto a pessoa não deslogou — o TMEA “corre” enquanto ela espera o próximo. Limitado a ${andamento / 60} h: acima disso ela provavelmente encerrou o turno sem o logoff chegar ao banco.`),
      li("TMEA = tempo ocioso ÷ número de intervalos.", "Ocioso = intervalo − pausas − tempo deslogado."),
      li("Comparação com o setor.", `Média do TMEA (regra nova) dos outros atendentes do mesmo setor nos últimos 30 dias, cada um pesando igual, só quem tem ${resp?.minIntervalosReferencia ?? 10}+ intervalos. Âmbar = acima da média do setor.`)
    ),
    el("div", { class: "exemplo" },
      el("b", { text: "Exemplo: " }),
      "atendimento termina 10:00, próximo começa 11:30 (90 min). Houve pausa “Intervalo” das 10:20 às 10:50 (30 min) e logoff das 11:00 às 11:10 (10 min). ",
      el("b", { text: "Ocioso = 90 − 30 − 10 = 50 min." }),
      ` Na regra oficial esse intervalo seria descartado (acima de ${corte} min).`
    ),
    el("p", { class: "obs", text: "A regra oficial (widget, tabela de atendimentos e as outras abas) não mudou. Esta aba serve para comparar antes de adotar a regra nova. Os números de hoje são recalculados a cada minuto; os de 30 dias, a cada 10 minutos." })
  );
}

function desenharNova() {
  const alvo = $("rankings");
  alvo.innerHTML = "";
  const resp = dados[`${chavePeriodo()}:${prefs.sistema}`];
  $("legenda").textContent = "Compare a coluna “TMEA atual” (regra oficial) com “TMEA regra nova”. Clique no nome para ver os atendimentos de hoje da pessoa.";
  alvo.append(cardRegras(resp && !resp.erro ? resp : null));
  if (!resp) {
    $("sub").textContent = "Carregando…";
    return;
  }
  if (resp.erro) {
    alvo.append(el("div", { class: "card" }, el("div", { class: "error", text: resp.erro })));
    $("sub").textContent = "";
    return;
  }
  desenharFiltros(resp);
  const hoje = prefs.periodoNova === "hoje";
  const linhas = resp.atendentes
    .filter((a) => a.atendimentos >= minimo() && noFiltro(a))
    .map((a) => ({ a, valor: a.nova.segundosMedios, excesso: a.referencia ? a.nova.segundosMedios - a.referencia.segundosMedios : null }))
    .sort((x, y) => {
      if ((x.excesso === null) !== (y.excesso === null)) return x.excesso === null ? 1 : -1;
      return (y.excesso ?? y.valor) - (x.excesso ?? x.valor);
    });
  linhas.forEach((l, i) => (l.pos = i + 1));
  const acima = linhas.filter((l) => l.excesso !== null && l.excesso > 0).length;
  const card = el("div", { class: "card nova" });
  card.append(el("div", { class: "rank-head" },
    el("div", { class: "section-marker", text: `TMEA pela regra nova · ${hoje ? "hoje" : `últimos ${resp.dias} dias`}` }),
    el("div", { class: "muted", text: "ordenado pela diferença para a média do setor (regra nova)" }),
    el("div", { class: `resumo-n ${acima ? "warn" : "ok"}`, text: linhas.length ? `${acima} de ${linhas.length} acima do setor` : "" })));
  if (!linhas.length) {
    card.append(el("p", { class: "muted", text: `Ninguém com ${minimo()} ou mais atendimentos no período.` }));
    alvo.append(card);
    return;
  }
  const colunas = [
    { chave: "pos", titulo: "#", inicial: "asc", valor: (l) => l.pos },
    { chave: "nome", titulo: "Atendente", texto: true, valor: (l) => l.a.nome },
    { chave: "n", titulo: "Atend.", classe: "metric", valor: (l) => l.a.atendimentos },
    { chave: "atual", titulo: "TMEA atual", classe: "metric", valor: (l) => l.a.atual?.segundosMedios },
    { chave: "nova", titulo: "TMEA regra nova", classe: "metric nova-col", valor: (l) => l.a.nova.segundosMedios },
    { chave: "dif", titulo: "vs. setor", classe: "metric", valor: (l) => l.excesso },
    { chave: "intervalos", titulo: "Intervalos", classe: "metric", valor: (l) => l.a.nova.intervalos },
    { chave: "ocioso", titulo: "Ocioso total", classe: "metric", valor: (l) => l.a.nova.ociosoSegundos },
    { chave: "pausas", titulo: "Pausas desc.", classe: "metric", valor: (l) => l.a.nova.pausaSegundos },
    { chave: "deslogado", titulo: "Deslogado desc.", classe: "metric", valor: (l) => l.a.nova.deslogadoSegundos },
  ];
  if (hoje) colunas.push({ chave: "andamento", titulo: "Em andamento", classe: "metric", valor: (l) => l.a.nova.emAndamentoSegundos });
  const ordenadas = ordenar(linhas, "nova", colunas);
  const visiveis = prefs.todos ? ordenadas : ordenadas.slice(0, 20);
  const horas = (s) => (s >= 3600 ? `${(s / 3600).toLocaleString("pt-BR", { maximumFractionDigits: 1 })} h` : fmt(s));
  const tbody = el("tbody");
  visiveis.forEach((l) => {
    const n = l.a.nova;
    const classe = l.excesso === null ? "" : l.excesso > 0 ? "warn" : "ok";
    const [pessoa, setor] = separarSetor(l.a);
    const nome = el("div", { class: "link", role: "link", tabindex: "0", title: `${l.a.nome}\nAbrir os atendimentos de hoje dessa pessoa` },
      el("span", { class: "pessoa", text: pessoa }), setor ? el("span", { class: "setor", text: setor }) : null);
    nome.addEventListener("click", () => abrirDetalhe(l.a.nome));
    nome.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); abrirDetalhe(l.a.nome); } });
    const dif = l.excesso === null ? "—" : `${l.excesso > 0 ? "+" : "−"}${fmt(l.excesso)}`;
    const refTitulo = l.a.referencia ? `Média de ${l.a.referencia.atendentes} colega(s) do setor ${l.a.referencia.setor}: ${fmt(l.a.referencia.segundosMedios)}` : "Sem colegas suficientes no setor";
    const atual = l.a.atual;
    tbody.append(el("tr", {},
      el("td", { class: "pos", text: String(l.pos) }),
      el("td", { class: "nome" }, nome),
      el("td", { class: "metric n", text: String(l.a.atendimentos) }),
      el("td", { class: "metric atual", title: atual ? `${atual.amostras} intervalo(s) · regra oficial (corta acima de ${resp.corteAtualMinutos} min)` : "Sem intervalos pela regra oficial", text: atual ? fmt(atual.segundosMedios) : "—" }),
      el("td", { class: `metric ${classe}`, title: `${n.intervalos} intervalo(s)`, text: fmt(n.segundosMedios) }),
      el("td", { class: `metric dif ${classe}`, title: refTitulo, text: dif }),
      el("td", { class: "metric n", text: String(n.intervalos) }),
      el("td", { class: "metric n", title: "Soma do tempo ocioso entre atendimentos", text: horas(n.ociosoSegundos) }),
      el("td", { class: "metric n", title: "Tempo em pausa descontado dos intervalos", text: horas(n.pausaSegundos) }),
      el("td", { class: "metric n", title: "Tempo deslogado descontado dos intervalos", text: horas(n.deslogadoSegundos) }),
      hoje ? el("td", { class: "metric n", title: "Tempo ocioso desde o fim do último atendimento (já incluído no TMEA)", text: n.emAndamentoSegundos === null || n.emAndamentoSegundos === undefined ? "—" : fmt(n.emAndamentoSegundos) }) : null
    ));
  });
  const head = cabecalho("nova", colunas);
  const wrap = el("div", { class: "table-wrap" });
  wrap.append(el("table", {}, el("thead", {}, head), tbody));
  card.append(wrap);
  if (!prefs.todos && linhas.length > 20) card.append(el("p", { class: "legend", text: `Mostrando os 20 primeiros de ${linhas.length}.` }));
  alvo.append(card);
  const calc = resp.calculadoEm ? resp.calculadoEm.slice(11, 16) : "—";
  $("sub").textContent = `${resp.atendentes.length} atendente(s) · calculado às ${calc}`;
}

// ---------------- Aba "Pausas e comportamentos" ----------------

/**
 * Golpes/atalhos mais citados em call center (pesquisa: Call Centre Helper, Brightmetrics, GetVoIP,
 * Avoxi) e como cada um aparece nos dados. `chave` = indicador da tabela; `porDia` = limite por dia
 * trabalhado a partir do qual a célula fica em alerta.
 */
function golpes(resp) {
  const L = resp.limites;
  const sis = prefs.sistema === "matrix" ? "Matrix" : "Native";
  const tma = resp.tmaLimite ? fmt(resp.tmaLimite * resp.longaFator) : "—";
  return [
    { chave: "excedidas", nome: "Estourar a pausa", o: "Sair para a pausa e voltar depois do tempo previsto (“arredondar” o intervalo, toalete que vira 15 min).",
      como: "Pausas mais longas que o tempo previsto do tipo (Toalete 5 min, Lanche 5 min, Outras atividades 10 min…).", limite: `${L.excedidas}+ por dia`, sistemas: resp.indicadores.previsto },
    { chave: "encadeadas", nome: "Renovar a pausa", o: "Encerrar a pausa perto do limite e abrir outra em seguida, para o sistema não marcar “tempo excedido”.",
      como: `Nova pausa começando até ${resp.encadeadaSegundos} s depois do fim da anterior.`, limite: `${L.encadeadas}+ por dia`, sistemas: true },
    { chave: "relampago", nome: "Pausa-relâmpago (furar a fila)", o: "Entrar e sair de pausa em segundos para escapar de uma ligação que está chegando ou mudar de posição na fila.",
      como: `Pausas com menos de ${resp.relampagoSegundos} s.`, limite: `${L.relampago}+ por dia`, sistemas: true },
    { chave: "ociosoLongo", nome: "Logado e parado", o: "Ficar disponível no sistema mas sem atender, sem abrir pausa (pós-atendimento esticado, “problema de TI”).",
      como: `Intervalos entre atendimentos com ${resp.ociosoLongoMinutos}+ min ociosos depois de descontar pausas e tempo deslogado (regra nova do TMEA).`, limite: `${L.ociosoLongo}+ por dia`, sistemas: true },
    { chave: "curtos", nome: "Derrubar a ligação", o: "Atender e desligar logo (“não estou ouvindo”, informação errada para encerrar), devolvendo o cliente para a fila.",
      como: resp.curtosRotulo || "—", limite: `${L.curtos}+ por dia`, sistemas: resp.indicadores.curtos },
    { chave: "longos", nome: "Segurar a linha", o: "Manter a ligação aberta depois que o cliente desligou, no mudo ou na pesquisa, para não receber a próxima.",
      como: `Atendimentos acima de ${resp.longaFator}× o limite do TMA (mais de ${tma}).`, limite: `${L.longos}+ por dia`, sistemas: resp.indicadores.longos },
    { chave: "transferidas", nome: "Transferir para se livrar", o: "Passar o cliente para outra fila/setor em vez de resolver, principalmente casos difíceis.",
      como: "Parte dos atendimentos encerrada como “Transferida”.", limite: `${L.transferidasPct}%+ dos atendimentos`, sistemas: resp.indicadores.transferidas },
    { chave: "internas", nome: "Ligar para ramal interno", o: "Ligar para um colega/ramal ou para o próprio celular para parecer ocupado e não receber ligações.",
      como: "Ligações efetuadas para números de até 4 dígitos (ramais) e o tempo nelas.", limite: `${L.internasMin}+ min por dia`, sistemas: resp.indicadores.internas },
    { chave: "sessoes", nome: "Deslogar e logar", o: "Sair do sistema várias vezes (“caiu”, “travou”) para ficar indisponível sem registrar pausa.",
      como: "Número de sessões de login.", limite: `${L.sessoes}+ por dia`, sistemas: resp.indicadores.sessoes },
    { chave: "semFim", nome: "Pausa sem fim registrado", o: "Pausa que fica aberta no sistema — o tempo real dela não aparece nos relatórios.",
      como: "Pausas sem horário de fim.", limite: `${L.semFim}+ por dia`, sistemas: true },
    { chave: "motivoGenerico", nome: "Motivo vazio na pausa", o: "Preencher a justificativa com “.”, “,” ou uma letra só nas pausas que pedem motivo.",
      como: "Justificativa só com pontuação ou 1–2 letras (a Native grava o motivo digitado).", limite: `${L.motivoGenerico}+ por dia`, sistemas: resp.indicadores.motivo },
  ].filter((g) => g.sistemas).map((g) => ({ ...g, sis }));
}

const NAO_DETECTAVEIS = [
  ["Mute / “linha com problema”", "precisaria do áudio ou de speech analytics (silêncio na gravação)."],
  ["Discar um dígito para bloquear a linha", "precisaria dos eventos do ramal (off-hook sem chamada)."],
  ["Conversa longa com colega / supervisor na chamada", "precisaria dos eventos de conferência."],
  ["Pós-atendimento (ACW) pessoal", "a Native/Matrix não registram o status de pós-atendimento separado — aparece como “logado e parado”."],
  ["Fugir da última ligação do turno", "precisaria da escala de cada pessoa para saber o horário de saída."],
];

/** Taxa por dia trabalhado e se passa do limite (a comparação com a mediana vem depois, em marcarAlertas). */
function taxa(resp, a, chave) {
  const d = Math.max(1, a.dados.dias || 1);
  const L = resp.limites;
  const x = a.dados;
  switch (chave) {
    case "transferidas": {
      const pct = x.atendimentos ? (100 * x.transferidas) / x.atendimentos : 0;
      return { v: pct, alerta: pct >= L.transferidasPct && x.transferidas >= 3 };
    }
    case "internas": {
      const min = x.internasSegundos / 60 / d;
      return { v: min, alerta: min >= L.internasMin };
    }
    case "ociosoLongo":
      return { v: a.ociosoLongo / d, alerta: a.ociosoLongo / d >= L.ociosoLongo };
    default: {
      const v = (x[chave] || 0) / d;
      return { v, alerta: L[chave] !== undefined && v >= L[chave] };
    }
  }
}

function mediana(valores) {
  const v = valores.filter((x) => Number.isFinite(x)).sort((a, b) => a - b);
  if (!v.length) return 0;
  const m = Math.floor(v.length / 2);
  return v.length % 2 ? v[m] : (v[m - 1] + v[m]) / 2;
}

/**
 * Alerta = passou do limite E está em pelo menos 2× a mediana da operação E entre os 10% mais altos
 * (no mesmo sistema e filtro).
 * Assim o que é do sistema (ex.: logoff automático da Native, pausa sem fim na Matrix) não vira alerta
 * para todo mundo — só para quem se destaca do grupo.
 */
function marcarAlertas(linhas, lista) {
  const med = {};
  const p90 = {};
  for (const g of lista) {
    const v = linhas.map((l) => l.t[g.chave].v).filter(Number.isFinite).sort((a, b) => a - b);
    med[g.chave] = mediana(v);
    p90[g.chave] = v.length ? v[Math.min(v.length - 1, Math.floor(v.length * 0.9))] : 0;
  }
  for (const l of linhas) {
    for (const g of lista) {
      const t = l.t[g.chave];
      t.mediana = med[g.chave];
      t.alerta = t.alerta && t.v > 0 && t.v >= 2 * med[g.chave] && t.v >= p90[g.chave];
    }
    l.alertas = lista.filter((g) => l.t[g.chave].alerta);
  }
  return med;
}

function cardGolpes(resp) {
  const lista = golpes(resp);
  const card = el("div", { class: "card regras" },
    el("h2", { text: "Golpes mais comuns em call center e como aparecem nos dados" }),
    el("p", { class: "muted", style: "margin:0 0 10px", text: `Cada item vira uma coluna da tabela abaixo, calculada por dia trabalhado (dias em que a pessoa atendeu) — em 30 dias, 60 pausas estouradas em 20 dias = 3 por dia. A célula fica vermelha quando a pessoa passa do limite E fica em pelo menos 2× a mediana da operação E entre os 10% mais altos (mesmo sistema e filtros): assim o que é comportamento do próprio sistema não acusa todo mundo. Indício não é prova: use para saber onde olhar (gravações, escala, conversa com a pessoa).` }));
  const grid = el("div", { class: "golpes" });
  for (const g of lista) {
    grid.append(el("div", { class: "golpe" },
      el("h3", { text: g.nome }),
      el("p", { text: g.o }),
      el("p", { class: "como" }, el("b", { text: "Como detectamos: " }), g.como),
      el("p", {}, el("span", { class: "limite", text: `Alerta: ${g.limite}` }))));
  }
  for (const [nome, motivo] of NAO_DETECTAVEIS) {
    grid.append(el("div", { class: "golpe fora" }, el("h3", { text: nome }), el("p", { class: "como", text: `Não detectável com os dados atuais: ${motivo}` })));
  }
  card.append(grid);
  const fontes = el("div", { class: "fontes" }, "Fontes da pesquisa: ");
  [["Call Centre Helper — 20 truques para evitar ligações", "https://www.callcentrehelper.com/7-tricks-that-call-centre-employees-play-67004.htm"],
   ["Brightmetrics — workload hacks", "https://brightmetrics.com/blog/workload-hacks-call-center-agents-use/"],
   ["GetVoIP — call avoidance", "https://getvoip.com/blog/call-avoidance/"],
   ["Avoxi — tempo auxiliar (AUX)", "https://www.avoxi.com/blog/how-to-manage-call-center-agent-auxiliary-time/"]].forEach(([t, u], i) => {
    if (i) fontes.append(" · ");
    fontes.append(el("a", { href: u, target: "_blank", rel: "noopener", text: t }));
  });
  card.append(fontes);
  return card;
}

/** Valor do percentil `p` (0–1) de uma lista já ordenada. */
const percentil = (v, p) => (v.length ? v[Math.min(v.length - 1, Math.floor(v.length * p))] : 0);

/** Mínimo de pessoas com o tipo de pausa para comparar alguém com o grupo. */
const MIN_GRUPO_TIPO = 5;

/** Medidas de cada pessoa num tipo de pausa (valores por dia trabalhado, como na tabela de atendentes). */
const MEDIDAS_TIPO = [
  { chave: "porDia", nome: "Tempo/dia", curto: "Tempo por dia" },
  { chave: "vezes", nome: "Vezes/dia", curto: "Muitas vezes" },
  { chave: "media", nome: "Média", curto: "Pausas longas" },
  { chave: "acima", nome: "Acima do previsto", curto: "Estoura o previsto" },
];

/**
 * Outliers de cada tipo de pausa, entre os atendentes nos filtros (setor, turno, supervisão e mínimo de
 * atendimentos) que tiveram esse tipo. Mesma regra dos alertas da tabela: pelo menos 2× a mediana do
 * grupo E entre os 10% mais altos, em alguma das MEDIDAS_TIPO (% acima do previsto só com 3+ pausas).
 * -> tipo -> { n, mediana: {medida: valor}, pessoas: [{ a, t, v, fora: Set(medida) }] } (só quem se destaca).
 */
function outliersPorTipo(resp) {
  const porTipo = {};
  for (const a of resp.atendentes) {
    if (a.dados.atendimentos < minimo() || !noFiltro(a)) continue;
    const d = Math.max(1, a.dados.dias);
    for (const [tipo, t] of Object.entries(a.dados.porTipo || {})) {
      if (!t.qtd) continue;
      const v = {
        porDia: t.segundos / d,
        vezes: t.qtd / d,
        media: t.segundos ? t.segundos / t.qtd : null,
        acima: t.previsto && t.qtd >= 3 ? (100 * t.excedidas) / t.qtd : null,
      };
      (porTipo[tipo] ||= []).push({ a, t, v, fora: new Set() });
    }
  }
  const out = {};
  for (const [tipo, lista] of Object.entries(porTipo)) {
    const med = {};
    for (const m of MEDIDAS_TIPO) {
      const v = lista.map((p) => p.v[m.chave]).filter(Number.isFinite).sort((x, y) => x - y);
      med[m.chave] = v.length ? mediana(v) : null;
      if (v.length < MIN_GRUPO_TIPO) continue;
      const p90 = percentil(v, 0.9);
      for (const p of lista) {
        const x = p.v[m.chave];
        if (Number.isFinite(x) && x > 0 && x >= 2 * med[m.chave] && x >= p90) p.fora.add(m.chave);
      }
    }
    out[tipo] = {
      n: lista.length,
      mediana: med,
      pessoas: lista.filter((p) => p.fora.size).sort((x, y) => y.fora.size - x.fora.size || y.v.porDia - x.v.porDia),
    };
  }
  return out;
}

/** Linha aberta embaixo de um tipo de pausa: quem se destaca do grupo nesse tipo. */
function detalheTipo(resp, tipo, o, ncols) {
  const medidas = MEDIDAS_TIPO.filter((m) => resp.indicadores.previsto || m.chave !== "acima");
  const um = (v) => v.toLocaleString("pt-BR", { maximumFractionDigits: 1 });
  const texto = { porDia: fmt, vezes: um, media: fmt, acima: (v) => `${Math.round(v)}%` };
  const col = el("div", { class: "detalhe-col" });
  if (!o || o.n < MIN_GRUPO_TIPO) {
    col.append(el("p", { class: "muted", text: `Só ${o ? o.n : 0} pessoa(s) nos filtros com “${tipo}”: poucas para comparar com o grupo (mínimo ${MIN_GRUPO_TIPO}).` }));
  } else {
    const medTxt = medidas.map((m) => `${m.nome.toLowerCase()} ${Number.isFinite(o.mediana[m.chave]) ? texto[m.chave](o.mediana[m.chave]) : "—"}`).join(" · ");
    col.append(el("div", {},
      el("h4", { text: o.pessoas.length ? `Outliers em “${tipo}” (${o.pessoas.length} de ${o.n})` : `Ninguém se destaca em “${tipo}”` }),
      el("p", { class: "muted", style: "margin:0 0 6px", text: `Comparados com as ${o.n} pessoas nos filtros que tiveram essa pausa, `
        + `${prefs.periodoNova === "hoje" ? "hoje" : "por dia trabalhado"}. Outlier: pelo menos 2× a mediana do grupo e entre os 10% mais altos. `
        + `Mediana do grupo: ${medTxt}.` })));
    if (o.pessoas.length) {
      const tbody = el("tbody", {}, ...o.pessoas.map((p) => {
        const [pessoa, setor] = separarSetor(p.a);
        const nome = el("div", { class: "link", role: "link", tabindex: "0", title: `${p.a.nome}\nAbrir os atendimentos de hoje dessa pessoa` },
          el("span", { class: "pessoa", text: pessoa }), setor ? el("span", { class: "setor", text: setor }) : null);
        nome.addEventListener("click", () => abrirDetalhe(p.a.nome));
        const totais = {
          porDia: `${horasFmt(p.t.segundos)} no período`,
          vezes: `${p.t.qtd} pausa(s) em ${p.a.dados.dias} dia(s)`,
          media: `${p.t.qtd} pausa(s), ${horasFmt(p.t.segundos)} no total`,
          acima: p.t.previsto ? `${p.t.excedidas} de ${p.t.qtd} acima do previsto (${fmt(p.t.previsto)})` : "Sem tempo previsto",
        };
        return el("tr", {},
          el("td", {}, nome),
          ...medidas.map((m) => {
            const v = p.v[m.chave];
            return el("td", { class: p.fora.has(m.chave) ? "fora" : "", title: totais[m.chave], text: Number.isFinite(v) ? texto[m.chave](v) : "—" });
          }),
          el("td", {}, el("div", { class: "alertas" },
            ...medidas.filter((m) => p.fora.has(m.chave)).map((m) => el("span", { class: "alerta-tag", text: m.curto })))));
      }));
      col.append(el("table", { class: "mini outliers" },
        el("thead", {}, el("tr", {}, ...["Atendente", ...medidas.map((m) => m.nome), "Por quê"].map((c) => el("th", { text: c })))),
        tbody));
    }
  }
  return el("tr", { class: "detalhe" }, el("td", { colspan: String(ncols) }, el("div", { class: "detalhe-grid" }, col)));
}

function cardTipos(resp) {
  const card = el("div", { class: "card pausas" },
    el("div", { class: "rank-head" }, el("div", { class: "section-marker", text: "Pausas por tipo" }),
      el("div", { class: "muted", text: "totais: todos os atendentes do sistema no período · ▸ outliers: entre quem está nos filtros" })));
  const prev = resp.indicadores.previsto;
  const outliers = outliersPorTipo(resp);
  const comparavel = (o) => o && o.n >= MIN_GRUPO_TIPO;
  const acimaPct = (x) => (x.previsto && x.qtd ? (100 * x.excedidas) / x.qtd : null);
  const colunas = [
    { chave: "abrir", titulo: "" },
    { chave: "tipo", titulo: "Tipo", texto: true, valor: (t) => t.tipo },
    { chave: "qtd", titulo: "Pausas", classe: "metric", valor: (t) => t.dados.qtd },
    { chave: "tempo", titulo: "Tempo total", classe: "metric", valor: (t) => t.dados.segundos },
    { chave: "media", titulo: "Média", classe: "metric", valor: (t) => (t.dados.segundos && t.dados.qtd ? t.dados.segundos / t.dados.qtd : null) },
  ].concat(prev ? [
    { chave: "previsto", titulo: "Previsto", classe: "metric", valor: (t) => t.dados.previsto },
    { chave: "acima", titulo: "Acima do previsto", classe: "metric", valor: (t) => acimaPct(t.dados) },
  ] : [], [
    { chave: "outliers", titulo: "Outliers", classe: "metric", valor: (t) => (comparavel(outliers[t.tipo]) ? outliers[t.tipo].pessoas.length : null) },
  ]);
  const tipos = ordenar([...resp.tipos].sort((a, b) => b.dados.segundos - a.dados.segundos), "tipos", colunas);
  if (!tipos.length) {
    card.append(el("p", { class: "muted", text: "Nenhuma pausa no período." }));
    return card;
  }
  const tbody = el("tbody");
  for (const t of tipos) {
    const x = t.dados;
    const o = outliers[t.tipo];
    const comFim = x.segundos && x.qtd ? x.segundos / x.qtd : null;
    const pct = x.qtd ? Math.round((100 * x.excedidas) / x.qtd) : 0;
    const nOut = o?.pessoas.length ?? 0;
    const btn = el("button", { type: "button", class: "expandir", "aria-expanded": "false", title: "Quem se destaca neste tipo de pausa", text: "▸" });
    const tr = el("tr", {},
      el("td", {}, btn),
      el("td", { text: t.tipo }),
      el("td", { class: "metric", text: x.qtd.toLocaleString("pt-BR") }),
      el("td", { class: "metric", text: horasFmt(x.segundos) }),
      el("td", { class: "metric", text: comFim ? fmt(comFim) : "—" }),
      prev ? el("td", { class: "metric", text: x.previsto ? fmt(x.previsto) : "—" }) : null,
      prev ? el("td", { class: `metric ${pct >= 30 ? "bad" : ""}`, text: x.previsto ? `${pct}%` : "—" }) : null,
      comparavel(o)
        ? el("td", { class: `metric${nOut ? " alerta" : ""}`, title: `${nOut} de ${o.n} pessoas nos filtros com essa pausa`, text: String(nOut) })
        : el("td", { class: "metric muted", title: `Poucas pessoas nos filtros com essa pausa para comparar (mínimo ${MIN_GRUPO_TIPO})`, text: "—" }));
    let aberto = null;
    const abrir = (sim) => {
      if (sim && !aberto) {
        aberto = detalheTipo(resp, t.tipo, o, colunas.length);
        tr.after(aberto);
        ajustarDetalhes();
      } else if (!sim && aberto) {
        aberto.remove();
        aberto = null;
      }
      btn.textContent = aberto ? "▾" : "▸";
      btn.setAttribute("aria-expanded", String(Boolean(aberto)));
    };
    btn.addEventListener("click", () => {
      if (aberto) tiposAbertos.delete(t.tipo);
      else tiposAbertos.add(t.tipo);
      abrir(!aberto);
    });
    tbody.append(tr);
    if (tiposAbertos.has(t.tipo)) abrir(true);
  }
  const wrap = el("div", { class: "table-wrap" });
  wrap.append(el("table", {}, el("thead", {}, cabecalho("tipos", colunas)), tbody));
  card.append(wrap);
  requestAnimationFrame(ajustarDetalhes);
  return card;
}

function horasFmt(s) {
  if (!s) return "0:00";
  return s >= 3600 ? `${(s / 3600).toLocaleString("pt-BR", { maximumFractionDigits: 1 })} h` : fmt(s);
}

/** Linha expandida: pausas por tipo e motivos digitados da pessoa. */
function detalhePessoa(resp, a, ncols) {
  const x = a.dados;
  const tipos = Object.entries(x.porTipo).sort((p, q) => q[1].qtd - p[1].qtd);
  const tTipos = el("table", { class: "mini" },
    el("thead", {}, el("tr", {}, ...["Tipo", "Qtd", "Tempo", resp.indicadores.previsto ? "Acima do previsto" : null].filter(Boolean).map((c) => el("th", { text: c })))),
    el("tbody", {}, ...tipos.map(([tipo, t]) => el("tr", {},
      el("td", { text: tipo }), el("td", { text: String(t.qtd) }), el("td", { text: horasFmt(t.segundos) }),
      resp.indicadores.previsto ? el("td", { text: t.previsto ? String(t.excedidas) : "—" }) : null))));
  // Uma coluna só, de cima para baixo: pausas por tipo, resumo e motivos digitados (só a Native tem).
  const coluna = el("div", { class: "detalhe-col" },
    el("div", {}, el("h4", { text: `Pausas por tipo (${x.pausas})` }), tipos.length ? tTipos : el("p", { class: "muted", text: "Nenhuma pausa." })));
  let blocoMotivos = null;
  if (resp.indicadores.motivo) {
    const motivos = Object.entries(x.motivos).sort((p, q) => q[1] - p[1]).slice(0, 12);
    blocoMotivos = (el("div", {},
      el("h4", { text: "Motivos digitados" }),
      el("p", { class: "muted", style: "margin:0 0 6px", text: `${x.motivoGenerico} com motivo genérico (“.”, “,”, 1–2 letras) · ${x.semMotivo} sem motivo (tipo que não pede)` }),
      motivos.length
        ? el("table", { class: "mini" }, el("tbody", {}, ...motivos.map(([m, n]) => el("tr", {}, el("td", { text: m }), el("td", { text: String(n) })))))
        : el("p", { class: "muted", text: "Nenhum motivo com texto." })));
  }
  coluna.append(el("div", {},
    el("h4", { text: "Resumo" }),
    el("table", { class: "mini kv" }, el("tbody", {},
      ...[["Dias com atendimento", x.dias], ["Atendimentos", x.atendimentos], ["Tempo em pausa", horasFmt(x.pausaSegundos)],
        ["Tempo acima do previsto", resp.indicadores.previsto ? horasFmt(x.excedidoSegundos) : "—"],
        ["Maior ociosidade sem pausa", a.maiorOcioso ? fmt(a.maiorOcioso) : "—"],
        ["Ligações p/ ramal interno", resp.indicadores.internas ? `${x.internas} (${horasFmt(x.internasSegundos)})` : "—"],
        ...SISTEMAS.map((s) => {
          const l = logadoEm(s.chave, a.nome, prefs.sistema);
          return [`Tempo logado ${s.titulo}`, !l ? "—" : `${l.estimado ? "≈" : ""}${hm(l.segundos)}${l.segundos ? ` · ${l.dias} dia(s) · ${l.sessoes} sessão(ões)` : ""}`];
        })]
        .map(([k, v]) => el("tr", {}, el("td", { text: k }), el("td", { text: String(v) })))))));
  if (blocoMotivos) coluna.append(blocoMotivos);
  return el("tr", { class: "detalhe" }, el("td", { colspan: String(ncols) }, el("div", { class: "detalhe-grid" }, coluna)));
}

/** Painel das linhas abertas com a largura da parte visível da tabela (senão ele se espalha pela tabela toda). */
function ajustarDetalhes() {
  for (const g of document.querySelectorAll("tr.detalhe .detalhe-grid")) {
    const wrap = g.closest(".table-wrap");
    if (wrap) g.style.width = `${Math.max(280, wrap.clientWidth - 30)}px`;
  }
}
window.addEventListener("resize", ajustarDetalhes);

function hm(s) {
  s = Math.round(s || 0);
  return `${Math.floor(s / 3600)}h${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}`;
}

async function carregarCorrespondencia() {
  if (correspondencia) return;
  try {
    const r = await SebratelApi.request(SebratelApi.NATIVE, "/ext/correspondencias");
    const native = new Map();
    const matrix = new Map();
    const add = (m, k, v) => m.set(k, [...(m.get(k) || []), v]);
    for (const p of r.pares || []) {
      if (p.origem === "bloqueado") continue;
      add(native, p.nativo, p.matrix);
      add(matrix, p.matrix, p.nativo);
    }
    correspondencia = { native, matrix };
  } catch {
    /* sem a correspondência, a outra plataforma é procurada pelo mesmo nome; tenta de novo na próxima carga */
  }
}

/**
 * Tempo logado de `nome` (atendente de `sistemaNome`) na plataforma `alvo`: na própria, pelo nome;
 * na outra, pelos nomes correspondentes (ou o mesmo nome, se não há par). null = dados indisponíveis.
 */
function logadoEm(alvo, nome, sistemaNome) {
  const resp = dados[`${chavePeriodo()}:${alvo}`];
  if (!resp || resp.erro || !resp.logados) return null;
  const nomes = alvo === sistemaNome ? [nome] : correspondencia?.[sistemaNome].get(nome) || [nome];
  const r = { segundos: 0, dias: 0, sessoes: 0, estimado: 0, nomes, achados: [] };
  for (const n of nomes) {
    const l = resp.logados[n];
    if (!l) continue;
    r.segundos += l.segundos;
    r.dias = Math.max(r.dias, l.dias);
    r.sessoes += l.sessoes;
    r.estimado += l.estimadoSegundos;
    r.achados.push(n);
  }
  return r;
}

/** Célula "Logado Native/Matrix": total hoje, média por dia logado em 30 dias. */
function celulaLogado(alvo, a) {
  const titulo = alvo === "matrix" ? "Matrix" : "Native";
  const l = logadoEm(alvo, a.nome, prefs.sistema);
  if (!l) return el("td", { class: "metric muted", title: `Dados da ${titulo} indisponíveis`, text: "—" });
  const outro = alvo !== prefs.sistema;
  if (!l.segundos) {
    return el("td", { class: "metric muted", title: `Sem login na ${titulo} no período${outro ? ` (procurado como: ${l.nomes.join("; ")})` : ""}`, text: "0h00" });
  }
  const hoje = prefs.periodoNova === "hoje";
  const est = l.estimado > 0;
  const linhas = [
    `${titulo}: ${hm(l.segundos)} logado ${hoje ? "hoje" : `em ${l.dias} dia(s) com login`} · ${l.sessoes} sessão(ões)`,
    est ? `≈ inclui ${hm(l.estimado)} da sessão atual, estimada pela atividade (a Native só grava a sessão no logoff)` : null,
    outro ? `Como: ${l.achados.join("; ")}` : null,
  ].filter(Boolean);
  return el("td", { class: "metric", title: linhas.join("\n"), text: `${est ? "≈" : ""}${hm(valorLogado(alvo, a))}` });
}

/** Número mostrado na célula de tempo logado (para ordenar): total hoje, média por dia com login em 30 dias. */
function valorLogado(alvo, a) {
  const l = logadoEm(alvo, a.nome, prefs.sistema);
  if (!l) return null;
  return prefs.periodoNova === "hoje" ? l.segundos : l.segundos / Math.max(1, l.dias || 1);
}

function desenharPausas() {
  const alvo = $("rankings");
  alvo.innerHTML = "";
  const resp = dados[`${chavePeriodo()}:${prefs.sistema}`];
  $("legenda").textContent = "Ordenado por número de alertas. Clique em ▸ para ver as pausas por tipo e os motivos digitados; no nome, para abrir os atendimentos de hoje.";
  if (!resp) {
    $("sub").textContent = "Carregando…";
    return;
  }
  if (resp.erro) {
    alvo.append(el("div", { class: "card" }, el("div", { class: "error", text: resp.erro })));
    $("sub").textContent = "";
    return;
  }
  desenharFiltros(resp);
  const lista = golpes(resp);
  alvo.append(cardGolpes(resp));

  const linhas = resp.atendentes
    .filter((a) => a.dados.atendimentos >= minimo() && noFiltro(a))
    .map((a) => {
      const t = {};
      for (const g of lista) t[g.chave] = taxa(resp, a, g.chave);
      return { a, t, alertas: [] };
    });
  const med = marcarAlertas(linhas, lista);
  linhas
    .sort((x, y) => y.alertas.length - x.alertas.length || y.a.dados.pausaSegundos / Math.max(1, y.a.dados.dias) - x.a.dados.pausaSegundos / Math.max(1, x.a.dados.dias));
  linhas.forEach((l, i) => (l.pos = i + 1));
  const porDia = (l, v) => v / Math.max(1, l.a.dados.dias);
  const sufixo = prefs.periodoNova === "hoje" ? "" : "/dia";
  const colunas = [
    { chave: "abrir", titulo: "" },
    { chave: "pos", titulo: "#", inicial: "asc", valor: (l) => l.pos },
    { chave: "nome", titulo: "Atendente", texto: true, valor: (l) => l.a.nome },
    { chave: "n", titulo: "Atend.", classe: "metric", valor: (l) => l.a.dados.atendimentos },
    { chave: "pausas", titulo: "Pausas/dia", classe: "metric", valor: (l) => porDia(l, l.a.dados.pausas) },
    { chave: "pausa", titulo: "Pausa/dia", classe: "metric", valor: (l) => porDia(l, l.a.dados.pausaSegundos) },
    { chave: "logNative", titulo: `Logado Native${sufixo}`, classe: "metric", valor: (l) => valorLogado("native", l.a) },
    { chave: "logMatrix", titulo: `Logado Matrix${sufixo}`, classe: "metric", valor: (l) => valorLogado("matrix", l.a) },
    ...lista.map((g) => ({ chave: g.chave, titulo: g.nome, classe: "metric", valor: (l) => l.t[g.chave].v })),
    { chave: "alertas", titulo: "Alertas", valor: (l) => l.alertas.length },
  ];

  const card = el("div", { class: "card pausas" });
  const comAlerta = linhas.filter((l) => l.alertas.length >= 2).length;
  card.append(el("div", { class: "rank-head" },
    el("div", { class: "section-marker", text: `Atendentes · ${prefs.periodoNova === "hoje" ? "hoje" : `últimos ${resp.dias} dias`}` }),
    el("div", { class: "muted", text: "valores por dia trabalhado; passe o mouse para ver o total" }),
    el("div", { class: `resumo-n ${comAlerta ? "bad" : "ok"}`, text: linhas.length ? `${comAlerta} de ${linhas.length} com 2 ou mais alertas` : "" })));
  if (!linhas.length) {
    card.append(el("p", { class: "muted", text: `Ninguém com ${minimo()} ou mais atendimentos no período.` }));
    alvo.append(card, cardTipos(resp));
    return;
  }
  const curtoNome = { excedidas: "Estourou", encadeadas: "Renovou", relampago: "Relâmpago", ociosoLongo: "Parado", curtos: "Derrubou",
    longos: "Segurou", transferidas: "Transferiu", internas: "Ramal", sessoes: "Deslogou", semFim: "Sem fim", motivoGenerico: "Motivo vazio" };
  const um = (v) => v.toLocaleString("pt-BR", { maximumFractionDigits: 1 });
  const ncols = 8 + lista.length + 1;
  const tbody = el("tbody");
  const ordenadas = ordenar(linhas, "pausas", colunas);
  const visiveis = prefs.todos ? ordenadas : ordenadas.slice(0, 30);
  visiveis.forEach((l) => {
    const x = l.a.dados;
    const d = Math.max(1, x.dias);
    const [pessoa, setor] = separarSetor(l.a);
    const nome = el("div", { class: "link", role: "link", tabindex: "0", title: `${l.a.nome}\nAbrir os atendimentos de hoje dessa pessoa` },
      el("span", { class: "pessoa", text: pessoa }), setor ? el("span", { class: "setor", text: setor }) : null);
    nome.addEventListener("click", () => abrirDetalhe(l.a.nome));
    const btn = el("button", { type: "button", class: "expandir", "aria-expanded": "false", title: "Pausas por tipo e motivos", text: "▸" });
    const tr = el("tr", {},
      el("td", {}, btn),
      el("td", { class: "pos", text: String(l.pos) }),
      el("td", { class: "nome" }, nome),
      el("td", { class: "metric n", text: String(x.atendimentos) }),
      el("td", { class: "metric", title: `${x.pausas} pausas em ${x.dias} dia(s)`, text: um(x.pausas / d) }),
      el("td", { class: "metric", title: `${horasFmt(x.pausaSegundos)} no período`, text: fmt(x.pausaSegundos / d) }),
      celulaLogado("native", l.a),
      celulaLogado("matrix", l.a),
      ...lista.map((g) => {
        const t = l.t[g.chave];
        const total = g.chave === "transferidas" ? `${x.transferidas} de ${x.atendimentos}` : g.chave === "internas" ? `${x.internas} ligações · ${horasFmt(x.internasSegundos)}`
          : g.chave === "ociosoLongo" ? `${l.a.ociosoLongo} no período` : `${x[g.chave]} no período`;
        const texto = g.chave === "transferidas" ? `${Math.round(t.v)}%` : g.chave === "internas" ? `${Math.round(t.v)} min` : um(t.v);
        const medTxt = g.chave === "transferidas" ? `${Math.round(med[g.chave])}%` : g.chave === "internas" ? `${Math.round(med[g.chave])} min` : um(med[g.chave]);
        return el("td", { class: `metric${t.alerta ? " alerta" : ""}`, title: `${g.nome}: ${total}
Limite ${g.limite} · mediana da operação ${medTxt}/dia`, text: texto });
      }),
      el("td", {}, el("div", { class: "alertas" }, ...l.alertas.map((g) => el("span", { class: "alerta-tag", title: g.nome, text: curtoNome[g.chave] || g.nome })))));
    let aberto = null;
    const abrir = (sim) => {
      if (sim && !aberto) {
        aberto = detalhePessoa(resp, l.a, ncols);
        tr.after(aberto);
        ajustarDetalhes();
      } else if (!sim && aberto) {
        aberto.remove();
        aberto = null;
      }
      btn.textContent = aberto ? "▾" : "▸";
      btn.setAttribute("aria-expanded", String(Boolean(aberto)));
    };
    btn.addEventListener("click", () => {
      if (aberto) expandidos.delete(l.a.nome);
      else expandidos.add(l.a.nome);
      abrir(!aberto);
    });
    tbody.append(tr);
    if (expandidos.has(l.a.nome)) abrir(true);
  });
  const wrap = el("div", { class: "table-wrap" });
  wrap.append(el("table", {}, el("thead", {}, cabecalho("pausas", colunas)), tbody));
  card.append(wrap);
  if (!prefs.todos && linhas.length > 30) card.append(el("p", { class: "legend", text: `Mostrando os 30 primeiros de ${linhas.length}.` }));
  requestAnimationFrame(ajustarDetalhes); // linhas que já estavam abertas, agora com a tabela na tela
  card.append(el("p", { class: "legend", text: "Tempo logado: soma das sessões de login no período (sessões sobrepostas contam uma vez)" +
    (prefs.periodoNova === "hoje" ? ", até agora. " : "; em 30 dias, a média por dia com login. ") +
    `Native: a sessão só é gravada no logoff, então a de quem ainda está logado (≈) é estimada da primeira ligação ou pausa depois do último logoff até agora. ` +
    `Matrix: sessão sem logout vai até o próximo login da pessoa (no máximo ${resp.maxSessaoAbertaHoras ?? 12} h). ` +
    "A outra plataforma é encontrada pela correspondência de nomes (tela “Relacionar nomes” no popup)." }));
  alvo.append(card, cardTipos(resp));
  const calc = resp.calculadoEm ? resp.calculadoEm.slice(11, 16) : "—";
  $("sub").textContent = `${linhas.length} atendente(s) · calculado às ${calc}`;
}

/**
 * @param automatico atualização periódica: silenciosa, só redesenha se os dados mudaram e mantém o
 *                   que já está na tela quando uma consulta falha
 */
async function carregar(automatico = false) {
  const btn = $("atualizar-btn");
  if (!automatico) {
    btn.disabled = true;
    btn.textContent = "Atualizando…";
  }
  ultimaCarga = Date.now();
  const chave = chavePeriodo();
  const rota = prefs.periodo === "nova" ? `/ext/ofensores/tmea-nova?periodo=${prefs.periodoNova}`
    : prefs.periodo === "pausas" ? `/ext/ofensores/pausas?periodo=${prefs.periodoNova}`
    : `/ext/ofensores?periodo=${prefs.periodo}`;
  const [res] = await Promise.all([
    Promise.allSettled(SISTEMAS.map((s) => SebratelApi.request(s.base(), rota))),
    prefs.periodo === "pausas" ? carregarCorrespondencia() : null,
  ]);
  let mudou = false;
  res.forEach((r, i) => {
    const k = `${chave}:${SISTEMAS[i].chave}`;
    const novo = r.status === "fulfilled"
      ? r.value
      : { erro: r.reason?.status === 403 ? "Somente administradores." : r.reason?.message || "Dados indisponíveis" };
    if (automatico && novo.erro && dados[k] && !dados[k].erro) return; // falha passageira: fica o que já está na tela
    if (JSON.stringify(novo) !== JSON.stringify(dados[k])) {
      dados[k] = novo;
      mudou = true;
    }
  });
  // Outra aba/período escolhido durante a consulta: os dados ficam guardados, a tela é daquele.
  if (chave === chavePeriodo() && (mudou || !automatico)) redesenharMantendo(automatico);
  if (!automatico) {
    btn.disabled = false;
    btn.textContent = "Atualizar";
  }
}

for (const b of document.querySelectorAll(".chip[data-sistema]")) {
  b.addEventListener("click", () => {
    prefs.sistema = b.dataset.sistema;
    prefs.setor = "";
    prefs.supervisor = "";
    salvarPrefs();
    desenhar();
  });
}
$("setor").addEventListener("change", (e) => {
  prefs.setor = e.target.value;
  if (!ehSuporte()) prefs.supervisor = "";
  salvarPrefs();
  desenhar();
});
$("turno").addEventListener("change", (e) => {
  prefs.turno = e.target.value;
  salvarPrefs();
  desenhar();
});
$("supervisor").addEventListener("change", (e) => {
  prefs.supervisor = e.target.value;
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
for (const b of document.querySelectorAll(".chip[data-nova]")) {
  b.addEventListener("click", () => {
    if (prefs.periodoNova === b.dataset.nova) return;
    prefs.periodoNova = b.dataset.nova;
    salvarPrefs();
    desenhar();
    carregar().catch(() => {});
  });
}
$("minimo").addEventListener("change", (e) => {
  prefs.minimos = { ...prefs.minimos, [chavePeriodo()]: Math.max(1, Number(e.target.value) || 1) };
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
  if (!["hoje", "30d"].includes(prefs.periodoNova)) prefs.periodoNova = "hoje";
  $("todos").checked = prefs.todos;
  carregar().catch((err) => {
    $("sub").textContent = err.message;
  });
});
// Hoje é recalculado no servidor a cada minuto; 30 dias, a cada 10 minutos. Com a aba em segundo
// plano não consulta — ao voltar, atualiza se já passou o intervalo.
const intervalo = () => (chavePeriodo().endsWith("30d") ? 600_000 : 60_000);
function atualizarSeVenceu() {
  if (!document.hidden && Date.now() - ultimaCarga >= intervalo()) carregar(true).catch(() => {});
}
setInterval(atualizarSeVenceu, 15_000);
document.addEventListener("visibilitychange", atualizarSeVenceu);
// Saiu do filtro com uma atualização esperando: aplica agora.
document.addEventListener("focusout", () => setTimeout(() => {
  if (redesenhoPendente && !interagindo()) redesenharMantendo(true);
}, 0));
