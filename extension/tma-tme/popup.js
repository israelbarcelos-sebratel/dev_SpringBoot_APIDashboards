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
    me.nomes.forEach((n, i) => {
      if (i) nomesEl.append(document.createElement("br"));
      nomesEl.append(n);
    });
    nomesEl.append(document.createElement("br"));
    nomesEl.append(me.vinculo === "manual" ? badge("definido pelo administrador", "manual") : badge("automático pela Matrix", "auto"));
  } else {
    nomesEl.textContent = "—";
  }
  const roleEl = $("who-role");
  roleEl.innerHTML = "";
  roleEl.append(me.role === "admin" ? badge("administrador", "admin") : badge("usuário comum", "user"));

  const semVinculo = !me.nomes || !me.nomes.length;
  $("who-unbound").style.display = semVinculo ? "block" : "none";
  // Sem vínculo, o pedido de ajuda é o único caminho: já abre o formulário.
  $("support-details").open = semVinculo;

  const isAdmin = me.role === "admin";
  $("admin-area").style.display = isAdmin ? "block" : "none";
  const cfg = await SebratelApi.getConfig();
  if (isAdmin) {
    const nomes = await nativeReq("/ext/atendentes").catch(() => []);
    fillSelect($("viewing-agent"), nomes, "— meus próprios dados —");
    $("viewing-agent").value = nomes.includes(cfg.viewingAgent) ? cfg.viewingAgent : "";
    fillSelect($("mg-atendente"), nomes, "— automático pela Matrix —");
  } else if (cfg.viewingAgent) {
    await SebratelApi.setConfig({ viewingAgent: "" });
  }
  show("view-main");
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

$("viewing-agent").addEventListener("change", async (e) => {
  await SebratelApi.setConfig({ viewingAgent: e.target.value });
  msg("O widget vai atualizar em até 15s.", "ok");
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

$("show-btn").addEventListener("click", async () => {
  // Só fecha depois de gravar: fechar o popup antes interrompe o get/set e o widget nunca reaparece.
  const r = await chrome.storage.local.get(["sebratelWidgetState"]);
  await chrome.storage.local.set({ sebratelWidgetState: { ...(r.sebratelWidgetState || {}), hidden: false } });
  window.close();
});

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
