/*
 * Painel de massiva da extensão (aviso de chamada e busca por CPF/CNPJ). Roda no "mundo" isolado dos
 * content scripts, dentro de um Shadow DOM: o CSS da página não entra e o nosso não vaza.
 * Só usa textContent com o que vem do servidor — nada de innerHTML com dado de cliente.
 */
(function () {
  if (window.SebratelMassivaUI) return;

  const CSS = `
    :host { all: initial; }
    .caixa {
      position: fixed; top: 16px; left: 50%; transform: translateX(-50%);
      z-index: 2147483647; width: min(420px, calc(100vw - 24px));
      font: 14px/1.45 "DM Sans", -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif;
      color: #2a2a28; background: #fff; border: 1px solid #e0dacb; border-left: 6px solid #b7b2a4;
      border-radius: 10px; box-shadow: 0 8px 16px -4px rgba(40,33,20,.12), 0 12px 28px -6px rgba(40,33,20,.18);
      padding: 12px 14px;
    }
    .caixa.massiva { border-left-color: #dc2626; background: #fff7f7; }
    .caixa.livre { border-left-color: #0f7a37; }
    .caixa.aviso { border-left-color: #b45309; }
    .topo { display: flex; align-items: center; gap: 8px; }
    .titulo { flex: 1; font-size: 12px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; color: #5e5b50; }
    .fechar { all: unset; cursor: pointer; padding: 2px 8px; font-size: 18px; line-height: 1; color: #5e5b50; border-radius: 6px; }
    .fechar:hover { background: #efeadd; }
    .status { margin: 6px 0 2px; font-size: 17px; font-weight: 700; color: #111; }
    .massiva .status { color: #b91c1c; }
    .livre .status { color: #0f7a37; }
    .detalhe { margin: 0; color: #2a2a28; overflow-wrap: anywhere; }
    .nota { margin: 6px 0 0; font-size: 12px; color: #5e5b50; }
    form { display: flex; gap: 8px; margin: 8px 0 4px; }
    input { flex: 1; min-width: 0; font: inherit; padding: 9px 11px; border: 1px solid #e0dacb; border-radius: 6px; outline: none; }
    input:focus { border-color: #ffb000; box-shadow: 0 0 0 3px rgba(255,176,0,.45); }
    button.buscar { font: inherit; font-weight: 600; padding: 9px 14px; border: none; border-radius: 6px; background: #ffb000; color: #111; cursor: pointer; }
    button.buscar:disabled { opacity: .6; cursor: default; }
  `;

  const NOMES = { telefone: "Ligação", documento: "Busca por CPF/CNPJ" };

  function criar(rotulo) {
    document.getElementById("sebratel-massiva-host")?.remove();
    const host = document.createElement("div");
    host.id = "sebratel-massiva-host";
    const raiz = host.attachShadow({ mode: "closed" });
    const estilo = document.createElement("style");
    estilo.textContent = CSS;
    const caixa = document.createElement("div");
    caixa.className = "caixa";
    caixa.setAttribute("role", "alert");
    const topo = document.createElement("div");
    topo.className = "topo";
    const titulo = document.createElement("span");
    titulo.className = "titulo";
    titulo.textContent = rotulo || "Massiva";
    const fechar = document.createElement("button");
    fechar.className = "fechar";
    fechar.type = "button";
    fechar.setAttribute("aria-label", "Fechar");
    fechar.textContent = "×";
    topo.append(titulo, fechar);
    const corpo = document.createElement("div");
    caixa.append(topo, corpo);
    raiz.append(estilo, caixa);
    document.documentElement.appendChild(host);
    const painel = {
      host, caixa, corpo, titulo, raiz, timer: null,
      fechar() {
        clearTimeout(painel.timer);
        host.remove();
      },
      // Fecha sozinho depois de ms (0 = só quando a pessoa fechar).
      autoFechar(ms) {
        clearTimeout(painel.timer);
        if (ms > 0) painel.timer = setTimeout(() => painel.fechar(), ms);
      },
    };
    fechar.addEventListener("click", () => painel.fechar());
    return painel;
  }

  function linha(classe, texto) {
    const p = document.createElement("p");
    p.className = classe;
    p.textContent = texto;
    return p;
  }

  /**
   * O motivo vem do formulário de abertura da massiva (emojis, nome de quem abriu, CTOs, topologia...). O atendente
   * precisa só do relato: pega o trecho "Relato inicial" e, se o formato for outro, corta o texto sem emojis.
   */
  function motivoResumido(texto) {
    const EMOJI = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/gu;
    const bruto = String(texto || "").trim();
    const achado = bruto.match(/Relato inicial:\s*([\s\S]*?)(?=\s*[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}]|$)/u);
    const base = (achado && achado[1].trim()) || bruto.replace(EMOJI, " ").replace(/\s+/g, " ").trim();
    return base.length > 160 ? `${base.slice(0, 157)}…` : base;
  }

  function horas(n) {
    return n === 1 ? "1 hora" : `${n} horas`;
  }

  /**
   * "hoje, por volta das 23h", "amanhã, ..." ou "dd/mm, ...". O servidor manda só as horas que faltam (arredondadas
   * para cima) e a hora do dia; o dia é o que deixa a hora mais perto de agora + horas.
   */
  function quandoPrevisto(horasRestantes, horaDoDia, agora = new Date()) {
    if (horaDoDia == null) return "";
    const alvo = agora.getTime() + horasRestantes * 3600000;
    let melhor = null;
    for (let d = -1; d <= 1; d++) {
      const c = new Date(alvo);
      c.setDate(c.getDate() + d);
      c.setHours(horaDoDia, 0, 0, 0);
      if (!melhor || Math.abs(c - alvo) < Math.abs(melhor - alvo)) melhor = c;
    }
    return diaEHora(melhor, agora, true);
  }

  /** "hoje às 23:00", "amanhã às 08:30" ou "02/11 às 23:00". */
  function diaEHora(data, agora = new Date(), aproximada = false) {
    if (Number.isNaN(data.getTime())) return "";
    const dias = Math.round((new Date(data).setHours(0, 0, 0, 0) - new Date(agora).setHours(0, 0, 0, 0)) / 86400000);
    const dia = dias === 0 ? "hoje" : dias === 1 ? "amanhã" : dias === -1 ? "ontem" : data.toLocaleDateString("pt-BR", { day: "2-digit", month: "2-digit" });
    const hora = data.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });
    return aproximada ? `${dia}, por volta das ${data.getHours()}h` : `${dia} às ${hora}`;
  }

  /** Mostra o resultado de uma consulta (a resposta do servidor) no painel. */
  function resultado(painel, r, { aoFim } = {}) {
    painel.corpo.replaceChildren();
    painel.caixa.className = "caixa";
    const quem = r.telefone ? `Telefone ${r.telefone}` : r.documento ? `Documento ${r.documento}` : "";
    const naoAchou = r.status === "cliente_nao_encontrado" || (!r.status && !r.em_massiva);
    if (r.status === "em_massiva") {
      painel.caixa.classList.add("massiva");
      painel.corpo.append(linha("status", "Cliente em MASSIVA"));
      if (r.motivo) painel.corpo.append(linha("detalhe", `Motivo: ${motivoResumido(r.motivo)}`));
      if (r.previsao_vencida && r.previsao_em) {
        painel.corpo.append(linha("detalhe", `Previsão vencida (era ${diaEHora(new Date(r.previsao_em))}). Sem nova previsão.`));
      } else if (r.previsao_em) {
        painel.corpo.append(linha("detalhe", `Previsão: ${diaEHora(new Date(r.previsao_em))} (em ${horas(r.previsao_horas)})`));
      } else if (r.previsao_horas != null) {
        const quando = quandoPrevisto(r.previsao_horas, r.previsao_hora);
        painel.corpo.append(linha("detalhe", `Previsão: ${horas(r.previsao_horas)}${quando ? ` (${quando})` : ""}`));
      }
      const n = (r.contratos_em_massiva || []).length;
      if (n > 1) painel.corpo.append(linha("nota", `${n} contratos afetados.`));
    } else if (r.status === "sem_massiva" && r.em_autoisp) {
      // Sem massiva, mas com alteração de rede ativa no AutoISP: o atendente precisa saber.
      painel.caixa.classList.add("aviso");
      painel.corpo.append(linha("status", "Alteração de rede (AutoISP)"));
    } else if (r.status === "sem_massiva") {
      painel.caixa.classList.add("livre");
      painel.corpo.append(linha("status", "Sem massiva"));
      painel.corpo.append(linha("detalhe", `Contratos consultados: ${r.contratos_consultados ?? 0}.`));
    } else if (r.status === "telefone_compartilhado") {
      painel.caixa.classList.add("aviso");
      painel.corpo.append(linha("status", "Telefone em vários cadastros"));
      painel.corpo.append(linha("detalhe", "Não identifica o cliente. Confirme o CPF/CNPJ e busque pelo atalho."));
    } else if (naoAchou) {
      painel.corpo.append(linha("status", "Cliente não localizado"));
      painel.corpo.append(linha("detalhe", "Nenhum contrato com autenticação neste cadastro."));
    }
    autoisp(painel, r);
    ultimasHoras(painel, r);
    if (quem) painel.corpo.append(linha("nota", quem));
    aoFim?.();
  }

  /** "olt_down" -> "olt down"; o AutoISP devolve o tipo em snake_case. */
  function legivel(texto) {
    return String(texto || "evento").replace(/_/g, " ");
  }

  function dataCurta(iso) {
    const d = iso ? new Date(iso) : null;
    return d && !Number.isNaN(d.getTime()) ? d.toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" }) : "";
  }

  /** Eventos de rede do AutoISP que atingem o cliente (só para quem foi localizado no cadastro). */
  function autoisp(painel, r) {
    if (r.status === "cliente_nao_encontrado" || r.status === "telefone_compartilhado" || !r.autoisp) return;
    if (r.autoisp.disponivel === false) {
      painel.corpo.append(linha("nota", "AutoISP indisponível no momento."));
      return;
    }
    const eventos = r.autoisp.eventos || [];
    if (!eventos.length) {
      painel.corpo.append(linha("nota", "AutoISP: sem alteração de rede ativa."));
      return;
    }
    for (const e of eventos.slice(0, 3)) {
      const desde = dataCurta(e.inicio);
      painel.corpo.append(linha("detalhe", `AutoISP: ${legivel(e.tipo)} (${e.status || "ativo"})${desde ? `, desde ${desde}` : ""}`));
    }
    if (eventos.length > 3) painel.corpo.append(linha("nota", `+${eventos.length - 3} evento(s) no AutoISP.`));
  }

  /** Massivas e eventos de rede já encerrados nas últimas horas que atingiram o cliente (contexto para o atendente). */
  function ultimasHoras(painel, r) {
    if (r.status === "cliente_nao_encontrado" || r.status === "telefone_compartilhado") return;
    const massivas = r.historico || [];
    const eventos = (r.autoisp && r.autoisp.recentes) || [];
    const h = r.historico_horas || (r.autoisp && r.autoisp.recentes_horas) || 6;
    if (!massivas.length && !eventos.length) {
      // Só afirma "nenhum" se o servidor realmente mandou o histórico (versão antiga do servidor não manda).
      if (r.historico !== undefined) painel.corpo.append(linha("nota", `Não existem outros eventos nas últimas ${h} horas.`));
      return;
    }
    painel.corpo.append(linha("nota", `Eventos anteriores (últimas ${h} horas):`));
    for (const m of massivas.slice(0, 3)) {
      const de = dataCurta(m.aberta_em);
      const ate = m.encerrada_em ? ` até ${dataCurta(m.encerrada_em)}` : "";
      painel.corpo.append(linha("detalhe", `Massiva ${m.protocolo} (${m.status || "?"}): ${de}${ate}`));
    }
    for (const e of eventos.slice(0, 3)) {
      const de = dataCurta(e.inicio);
      const ate = e.fim ? ` até ${dataCurta(e.fim)}` : "";
      painel.corpo.append(linha("detalhe", `AutoISP ${legivel(e.tipo)}: ${de}${ate}`));
    }
    const mais = Math.max(0, massivas.length - 3) + Math.max(0, eventos.length - 3);
    if (mais) painel.corpo.append(linha("nota", `+${mais} registro(s) no período.`));
  }

  function carregando(painel, texto) {
    painel.corpo.replaceChildren(linha("detalhe", texto));
    painel.caixa.className = "caixa";
  }

  function erro(painel, texto) {
    painel.corpo.replaceChildren(linha("status", "Não foi possível consultar"), linha("detalhe", texto));
    painel.caixa.className = "caixa aviso";
  }

  window.SebratelMassivaUI = { criar, resultado, carregando, erro, linha, motivoResumido, ultimasHoras, quandoPrevisto, NOMES };
})();
