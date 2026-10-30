/*
 * Aviso de massiva para quem está recebendo a ligação. Roda só na página da Native: o native-hook.js (world
 * MAIN) manda por postMessage o telefone de quem liga; aqui pedimos a situação ao background (que consulta o
 * servidor da Sebratel com o token Google) e mostramos o resultado num banner no topo da tela.
 */
(function () {
  if (window.__sebratelChamadaAtiva) return;
  window.__sebratelChamadaAtiva = true;

  const CONFIG_KEY = "sebratelMassiva";
  const JANELA_MS = 60000; // mesmo telefone dentro deste prazo = mesma ligação (AgentCalled + AgentConnect)
  const vistos = new Map(); // telefone -> instante da última exibição

  function digitos(valor) {
    return String(valor ?? "").replace(/\D/g, "");
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

  async function aoEvento(msg) {
    const cfg = await config();
    if (cfg.avisoLigacao === false) return; // a pessoa desligou o aviso no popup
    const dados = msg.dados;
    // Se a pessoa informou o código do agente dela (4 dígitos), só as ligações dela; vazio = qualquer uma
    // que o painel mostre (a conta de atendente só recebe as próprias, mas a Native não garante).
    const agente = digitos(cfg.agente);
    if (agente && digitos(dados.agent) !== agente) return;

    const telefone = telefoneExterno(dados);
    if (!telefone) return;
    const agora = Date.now();
    if (agora - (vistos.get(telefone) || 0) < JANELA_MS) return;
    vistos.set(telefone, agora);

    const ui = window.SebratelMassivaUI;
    const painel = ui.criar("Ligação recebida");
    ui.carregando(painel, "Consultando massiva…");
    try {
      const resp = await chrome.runtime.sendMessage({ type: "massivaTelefone", telefone });
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

  window.addEventListener("message", (ev) => {
    if (ev.source !== window || ev.origin !== window.location.origin) return;
    const msg = ev.data;
    if (!msg || msg.source !== "sebratel-native-hook" || !msg.dados) return;
    aoEvento(msg).catch(() => {});
  });
})();
