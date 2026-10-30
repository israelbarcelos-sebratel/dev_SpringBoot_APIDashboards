/*
 * Aviso de massiva para quem está recebendo a ligação. Roda só na página da Native: o native-hook.js (world
 * MAIN) manda por postMessage o telefone de quem liga; aqui pedimos a situação ao background (que consulta o
 * servidor da Sebratel com o token Google) e mostramos o resultado num banner no topo da tela.
 */
(function () {
  if (window.__sebratelChamadaAtiva?.()) return;
  window.__sebratelChamadaAtiva = () => { try { return !!chrome.runtime?.id; } catch { return false; } };

  const CONFIG_KEY = "sebratelMassiva";
  const JANELA_MS = 60000; // mesmo telefone dentro deste prazo = mesma ligação (AgentCalled + AgentConnect)
  const vistos = new Map(); // telefone -> instante da última exibição

  function digitos(valor) {
    return String(valor ?? "").replace(/\D/g, "");
  }

  /**
   * Na URA o cliente digita números (CPF/CNPJ e opções de menu) e a Native os cola no número da ligação: 0800 766 1000
   * + o que foi digitado ("0800766100012345678910"). Procura isso em qualquer campo do evento e devolve só o que foi
   * digitado, sem a parte do 0800. Não valida nem consulta nada: o atendente vê os dígitos como chegaram.
   */
  function digitadoNaUra(dados) {
    for (const valor of Object.values(dados || {})) {
      if (typeof valor !== "string" && typeof valor !== "number") continue;
      const m = digitos(valor).match(/^(?:55)?0?800\d{7}(\d{1,30})$/);
      if (m) return m[1];
    }
    return null;
  }

  /** Telefone externo (10 ou 11 dígitos, com ou sem 55/0); ramal interno e lixo viram null. */
  function telefoneExterno(dados) {
    const bruto = dados.callerNumber ?? dados.callerIdNum ?? dados.calleridnum ?? dados.connectedLineNum;
    const d = digitos(bruto);
    if (d.length < 10 || d.length > 14) return null;
    if (String(dados.direction || "").toLowerCase() === "outgoing") return null;
    return d;
  }

  function config() {
    return new Promise((resolve) => {
      chrome.storage.local.get([CONFIG_KEY], (r) => resolve(r[CONFIG_KEY] || {}));
    });
  }

  /** Modo de teste (popup): mostra que o evento chegou e por que não houve consulta. */
  function diagnostico(texto, msg) {
    const ui = window.SebratelMassivaUI;
    const painel = ui.criar("Modo de teste: evento recebido");
    painel.corpo.replaceChildren(
      ui.linha("detalhe", texto),
      ui.linha("nota", `Evento ${msg.evento}; agente ${digitos(msg.dados.agent) || "?"}.`));
    painel.autoFechar(10000);
  }

  async function aoEvento(msg) {
    const cfg = await config();
    if (cfg.avisoLigacao === false) return; // a pessoa desligou o aviso no popup
    const dados = msg.dados;
    // Se a pessoa informou o código do agente dela (4 dígitos), só as ligações dela; vazio = qualquer uma
    // que o painel mostre (a conta de atendente só recebe as próprias, mas a Native não garante).
    const agente = digitos(cfg.agente);
    if (agente && digitos(dados.agent) !== agente) {
      if (cfg.diagnostico) diagnostico(`Ignorada: é do agente ${digitos(dados.agent) || "?"}, não do ${agente}.`, msg);
      return;
    }

    // Dígitos que o cliente digitou na URA, colados ao número da ligação: só mostra, sem consultar o servidor.
    const digitado = digitadoNaUra(dados);
    if (digitado) {
      const chave = `dig:${digitado}`;
      const agoraDig = Date.now();
      if (agoraDig - (vistos.get(chave) || 0) < JANELA_MS) return;
      vistos.set(chave, agoraDig);
      const ui = window.SebratelMassivaUI;
      const painel = ui.criar("Ligação recebida");
      painel.corpo.replaceChildren(
        ui.linha("status", "Número digitado na URA"),
        ui.linha("detalhe", digitado),
        ui.linha("nota", "Sem consulta automática: use a busca por CPF/CNPJ (atalho) com o documento."));
      return;
    }

    const telefone = telefoneExterno(dados);
    if (!telefone) {
      const quem = digitos(dados.callerNumber ?? dados.callerIdNum ?? dados.calleridnum ?? dados.connectedLineNum);
      const saindo = String(dados.direction || "").toLowerCase() === "outgoing";
      if (cfg.diagnostico) {
        diagnostico(saindo ? "Ignorada: ligação feita por você (saída)."
          : `Ignorada: ${quem ? `"${quem}" não é telefone de cliente (ramal interno?)` : "sem número de quem ligou"}.`, msg);
      }
      return;
    }
    const agora = Date.now();
    if (agora - (vistos.get(telefone) || 0) < JANELA_MS) return;
    vistos.set(telefone, agora);

    await consultar("Ligação recebida", { type: "massivaTelefone", telefone });
  }

  /** Abre o painel, pede a situação ao servidor (via background) e mostra o resultado. */
  async function consultar(rotulo, pedido) {
    const ui = window.SebratelMassivaUI;
    const painel = ui.criar(rotulo);
    ui.carregando(painel, "Consultando massiva…");
    try {
      const resp = await chrome.runtime.sendMessage(pedido);
      if (!resp?.ok) throw new Error(resp?.error || "Sem resposta da extensão.");
      ui.resultado(painel, resp.data);
      // Massiva ou alteração de rede ficam na tela até a pessoa fechar; o resto some sozinho.
      const d = resp.data;
      // Fica na tela se há massiva/evento ativo ou se houve algum nas últimas horas (contexto para o atendente).
      const recente = (d.historico || []).length > 0 || ((d.autoisp && d.autoisp.recentes) || []).length > 0;
      painel.autoFechar(d.status === "em_massiva" || d.em_autoisp || recente ? 0 : 20000);
    } catch (e) {
      ui.erro(painel, e.message || "Tente de novo em instantes.");
      painel.autoFechar(15000);
    }
  }

  window.__sebratelChamadaTeste = { digitadoNaUra, telefoneExterno }; // só para teste/diagnóstico

  window.addEventListener("message", function aoReceber(ev) {
    // Script órfão (extensão atualizada com a aba aberta): o background já injetou uma cópia nova; esta se cala.
    if (!chrome.runtime?.id) {
      window.removeEventListener("message", aoReceber);
      return;
    }
    if (ev.source !== window || ev.origin !== window.location.origin) return;
    const msg = ev.data;
    if (!msg || msg.source !== "sebratel-native-hook" || !msg.dados) return;
    aoEvento(msg).catch(() => {});
  });
})();
