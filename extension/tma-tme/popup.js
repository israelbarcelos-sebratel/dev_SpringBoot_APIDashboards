const $ = (id) => document.getElementById(id);

function show(view) {
  for (const v of document.querySelectorAll(".view")) v.style.display = "none";
  $(view).style.display = "block";
}

function msg(text, kind = "error") {
  $("msg").textContent = text || "";
  $("msg").className = `msg ${kind}`;
}

function fillSelect(select, nomes, placeholder) {
  select.innerHTML = "";
  if (placeholder !== undefined) select.add(new Option(placeholder, ""));
  for (const n of nomes) select.add(new Option(n, n));
}

function badge(text, kind) {
  const b = document.createElement("span");
  b.className = `badge badge-${kind}`;
  b.textContent = text;
  return b;
}

function nativeReq(path, opts) {
  return SebratelApi.request(SebratelApi.NATIVE, path, opts);
}

async function render() {
  show("view-loading");
  const token = await SebratelApi.getToken(false);
  if (!token) {
    show("view-login");
    return;
  }

  let me;
  try {
    me = await nativeReq("/ext/me");
  } catch (err) {
    show("view-login");
    msg(err.message);
    return;
  }

  // Quem sou: o vínculo é automático (e-mail nos atendimentos da Matrix) — ninguém escolhe o próprio nome.
  $("who-email").textContent = me.email;
  const nomesEl = $("who-nomes");
  nomesEl.innerHTML = "";
  if (me.nomes && me.nomes.length) {
    nomesEl.append(me.atendente);
    nomesEl.append(document.createElement("br"));
    nomesEl.append(me.vinculo === "manual" ? badge("definido pelo administrador", "manual") : badge("automático pela Matrix", "auto"));
  } else {
    nomesEl.textContent = "—";
  }
  const roleEl = $("who-role");
  roleEl.innerHTML = "";
  roleEl.append(me.role === "admin" ? badge("administrador", "admin") : badge("usuário comum", "user"));

  // Mais de um cadastro no mesmo e-mail (trocou de área): a pessoa escolhe qual usar; o servidor guarda.
  const variosCadastros = me.nomes && me.nomes.length > 1;
  $("pref-area").style.display = variosCadastros ? "block" : "none";
  if (variosCadastros) {
    fillSelect($("pref-select"), me.nomes, "Todos os cadastros (somados)");
    $("pref-select").value = me.preferido || "";
    $("pref-notice").style.display = me.preferido ? "none" : "block";
  }

  const semVinculo = !me.nomes || !me.nomes.length;
  $("who-unbound").style.display = semVinculo ? "block" : "none";
  // Sem vínculo, o pedido de ajuda é o único caminho: já abre o formulário.
  $("support-details").open = semVinculo;

  const isAdmin = me.role === "admin";
  for (const el of document.querySelectorAll(".admin-only")) el.style.display = isAdmin ? "block" : "none";
  const cfg = await SebratelApi.getConfig();
  if (isAdmin) {
    const nomes = await nativeReq("/ext/atendentes").catch(() => []);
    fillSelect($("viewing-agent"), nomes, "— meus próprios dados —");
    $("viewing-agent").value = nomes.includes(cfg.viewingAgent) ? cfg.viewingAgent : "";
    fillSelect($("mg-atendente"), nomes, "— automático pela Matrix —");
  } else if (cfg.viewingAgent) {
    await SebratelApi.setConfig({ viewingAgent: "" });
  }
  await atualizarBotaoAba();
  show("view-main");
}

/** Aba ativa e se o widget está à vista nela (false = mostrando, como no background). */
async function abaAtual() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab) return { tab: null, mostrando: false };
  const r = await chrome.runtime.sendMessage({ type: "widgetEstado", tabId: tab.id }).catch(() => null);
  return { tab, mostrando: r?.oculto === false };
}

async function atualizarBotaoAba() {
  const { mostrando } = await abaAtual();
  $("show-btn").textContent = mostrando ? "Esconder widget nesta aba" : "Mostrar widget nesta aba";
  $("show-btn").className = mostrando ? "btn btn-secondary" : "btn btn-primary";
}

$("login-btn").addEventListener("click", async () => {
  msg("");
  const token = await SebratelApi.getToken(true);
  if (!token) {
    msg("Login cancelado ou não permitido.");
    return;
  }
  render();
});

$("support-btn").addEventListener("click", async () => {
  const btn = $("support-btn");
  btn.disabled = true;
  btn.textContent = "Enviando…";
  try {
    await nativeReq("/ext/suporte", { method: "POST", body: { mensagem: $("support-text").value } });
    $("support-text").value = "";
    $("support-details").open = false;
    msg("Pedido enviado! A equipe de desenvolvimento vai responder no seu e-mail.", "ok");
  } catch (err) {
    msg(err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "Fale com seu administrador";
  }
});

$("pref-select").addEventListener("change", async (e) => {
  const select = e.target;
  select.disabled = true;
  try {
    await nativeReq("/ext/me/preferido", { method: "PUT", body: { atendente: select.value } });
    msg("Cadastro salvo. O widget vai atualizar em até 15s.", "ok");
    render();
  } catch (err) {
    msg(err.message);
  } finally {
    select.disabled = false;
  }
});

$("viewing-agent").addEventListener("change", async (e) => {
  await SebratelApi.setConfig({ viewingAgent: e.target.value });
  msg(e.target.value ? `Widget mostrando ${e.target.value}.` : "Widget mostrando os seus dados.", "ok");
});

$("mg-save").addEventListener("click", async () => {
  const email = $("mg-email").value.trim();
  if (!email) {
    msg("Informe o e-mail.");
    return;
  }
  try {
    // Atendente vazio = vínculo automático pela Matrix (só define o papel).
    const u = await nativeReq("/ext/usuarios", {
      method: "PUT",
      body: { email, atendente: $("mg-atendente").value, role: $("mg-role").value },
    });
    const vinculo = u.nomes && u.nomes.length ? u.nomes.join(", ") : "sem vínculo";
    msg(`Salvo: ${u.email} → ${vinculo} (${u.role === "admin" ? "administrador" : "usuário comum"}).`, "ok");
    if (email.toLowerCase() === $("who-email").textContent) render();
  } catch (err) {
    msg(err.message);
  }
});

$("ofensores-btn").addEventListener("click", () => {
  chrome.tabs.create({ url: chrome.runtime.getURL("ofensores.html") });
  window.close();
});

$("corr-btn").addEventListener("click", () => {
  chrome.tabs.create({ url: chrome.runtime.getURL("correspondencias.html") });
  window.close();
});

$("detalhe-btn").addEventListener("click", () => {
  chrome.tabs.create({ url: chrome.runtime.getURL("detalhes.html") });
  window.close();
});

$("show-btn").addEventListener("click", async () => {
  // Mostra ou esconde só na aba atual. Só fecha o popup depois da resposta: fechar antes interrompe o pedido.
  const { tab, mostrando } = await abaAtual();
  const tipo = mostrando ? "widgetEsconder" : "widgetMostrar";
  const resp = tab ? await chrome.runtime.sendMessage({ type: tipo, tabId: tab.id }).catch(() => null) : null;
  if (!resp?.ok) {
    msg("Esta página não aceita o widget (páginas internas do Chrome, Web Store e PDFs).");
    return;
  }
  window.close();
});

// O widget é por aba: sem preferência global (as antigas "hidden" e "autoAbrir" saem do estado).
chrome.storage.local.get(["sebratelWidgetState"], (r) => {
  const { hidden, autoAbrir, ...estado } = r.sebratelWidgetState || {};
  if (hidden !== undefined || autoAbrir !== undefined) chrome.storage.local.set({ sebratelWidgetState: estado });
});

// --- Massiva: aviso de ligação, código do agente e atalho da busca (guardados só neste navegador) ---
const MZ_KEY = "sebratelMassiva";
const MZ_ATALHO_PADRAO = "Alt+Shift+M";

async function mzLer() {
  const r = await chrome.storage.local.get([MZ_KEY]);
  return r[MZ_KEY] || {};
}

async function mzSalvar(patch) {
  await chrome.storage.local.set({ [MZ_KEY]: { ...(await mzLer()), ...patch } });
}

async function mzMostrar() {
  const cfg = await mzLer();
  $("mz-aviso").checked = cfg.avisoLigacao !== false;
  $("mz-agente").value = cfg.agente || "";
  $("mz-diag").checked = cfg.diagnostico === true;
  $("mz-atalho").value = cfg.atalho || MZ_ATALHO_PADRAO;
}

/** Mesma descrição de tecla do busca.js ("Alt+Shift+M"); vazio se for só modificador ou tecla sem suporte. */
function mzDescrever(ev) {
  const cod = ev.code || "";
  let tecla = "";
  if (/^Key[A-Z]$/.test(cod)) tecla = cod.slice(3);
  else if (/^Digit\d$/.test(cod)) tecla = cod.slice(5);
  else if (/^F\d{1,2}$/.test(cod)) tecla = cod;
  else if (cod === "Space") tecla = "Space";
  if (!tecla) return "";
  const mods = [];
  if (ev.ctrlKey) mods.push("Ctrl");
  if (ev.altKey) mods.push("Alt");
  if (ev.shiftKey) mods.push("Shift");
  if (ev.metaKey) mods.push("Meta");
  // Tecla solta (sem modificador) atrapa a digitação normal; só F1–F12 podem ir sozinhas.
  if (!mods.length && !/^F\d{1,2}$/.test(tecla)) return "";
  return [...mods, tecla].join("+");
}

$("mz-aviso").addEventListener("change", (e) => mzSalvar({ avisoLigacao: e.target.checked }));
$("mz-diag").addEventListener("change", (e) => mzSalvar({ diagnostico: e.target.checked }));
$("mz-agente").addEventListener("change", (e) => mzSalvar({ agente: e.target.value.replace(/\D/g, "") }));
$("mz-atalho").addEventListener("keydown", async (ev) => {
  if (ev.key === "Tab") return;
  ev.preventDefault();
  const atalho = mzDescrever(ev);
  if (!atalho) return;
  $("mz-atalho").value = atalho;
  await mzSalvar({ atalho });
});
$("mz-padrao").addEventListener("click", async () => {
  await mzSalvar({ atalho: MZ_ATALHO_PADRAO });
  $("mz-atalho").value = MZ_ATALHO_PADRAO;
});
mzMostrar();

$("logout-btn").addEventListener("click", async () => {
  const token = await SebratelApi.getToken(false);
  if (token) {
    await SebratelApi.removeToken(token);
    // Revoga no Google também, senão getAuthToken devolveria a mesma conta sem perguntar.
    await fetch(`https://oauth2.googleapis.com/revoke?token=${encodeURIComponent(token)}`, { method: "POST" }).catch(() => {});
  }
  await SebratelApi.setConfig({ viewingAgent: "" });
  chrome.storage.local.get(["sebratelWidgetState"], (r) => {
    const { lastMetrics, ...rest } = r.sebratelWidgetState || {};
    chrome.storage.local.set({ sebratelWidgetState: rest });
  });
  msg("");
  render();
});

render();
