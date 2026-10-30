/*
 * Busca de massiva por CPF/CNPJ com um atalho de teclado que a própria pessoa define no popup. Roda em
 * qualquer página: o atalho abre uma caixa no topo, a pessoa digita o documento que o cliente falou e vê se
 * o cadastro dele está em massiva.
 */
(function () {
  if (window.__sebratelBuscaAtiva) return;
  window.__sebratelBuscaAtiva = true;

  const CONFIG_KEY = "sebratelMassiva";
  const ATALHO_PADRAO = "Alt+Shift+M";
  let atalho = ATALHO_PADRAO;
  let painel = null;

  /** Nome canônico da tecla: "Alt+Shift+M". Vazio para tecla só de modificador. */
  function descrever(ev) {
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
    return [...mods, tecla].join("+");
  }

  function carregarConfig() {
    chrome.storage.local.get([CONFIG_KEY], (r) => {
      atalho = (r[CONFIG_KEY] && r[CONFIG_KEY].atalho) || ATALHO_PADRAO;
    });
  }
  carregarConfig();
  chrome.storage.onChanged.addListener((mudancas, area) => {
    if (area === "local" && mudancas[CONFIG_KEY]) carregarConfig();
  });

  function abrir() {
    const ui = window.SebratelMassivaUI;
    painel?.fechar();
    painel = ui.criar(ui.NOMES.documento);

    const form = document.createElement("form");
    const campo = document.createElement("input");
    campo.type = "text";
    campo.inputMode = "numeric";
    campo.autocomplete = "off";
    campo.placeholder = "CPF ou CNPJ do cliente";
    campo.setAttribute("aria-label", "CPF ou CNPJ do cliente");
    const botao = document.createElement("button");
    botao.type = "submit";
    botao.className = "buscar";
    botao.textContent = "Buscar";
    form.append(campo, botao);
    const saida = document.createElement("div");
    painel.corpo.append(form, saida);

    // Esc fecha; as teclas digitadas não podem vazar para os atalhos da página.
    campo.addEventListener("keydown", (ev) => {
      ev.stopPropagation();
      if (ev.key === "Escape") painel.fechar();
    });
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const documento = campo.value.trim();
      if (!documento) return;
      botao.disabled = true;
      ui.carregando({ corpo: saida, caixa: painel.caixa }, "Consultando massiva…");
      try {
        const resp = await chrome.runtime.sendMessage({ type: "massivaDocumento", documento });
        if (!resp?.ok) throw new Error(resp?.error || "Sem resposta da extensão.");
        ui.resultado({ corpo: saida, caixa: painel.caixa }, resp.data);
        saida.append(ui.linha("nota", "Consulta de massiva. AutoISP ainda não faz parte desta busca."));
      } catch (e) {
        ui.erro({ corpo: saida, caixa: painel.caixa }, e.message || "Tente de novo em instantes.");
      } finally {
        botao.disabled = false;
        campo.select();
      }
    });
    // O foco só vale depois de a caixa entrar no DOM.
    setTimeout(() => campo.focus(), 0);
  }

  window.addEventListener(
    "keydown",
    (ev) => {
      if (ev.repeat || descrever(ev) !== atalho) return;
      ev.preventDefault();
      ev.stopPropagation();
      abrir();
    },
    true
  );
})();
