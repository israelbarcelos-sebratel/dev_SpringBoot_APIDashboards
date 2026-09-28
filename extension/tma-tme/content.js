(function () {
  const REFRESH_MS = 15000;
  const STORAGE_KEY = "sebratelWidgetState";

  function fmt(seconds) {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${String(s).padStart(2, "0")}`;
  }

  /** Regra de produtividade: dentro do limite verde, acima dele vermelho (tempo excedido). */
  function levelClass(value, target) {
    return value <= target ? "ok" : "bad";
  }

  function loadState() {
    return new Promise((resolve) => {
      chrome.storage.local.get([STORAGE_KEY], (result) => {
        resolve(result[STORAGE_KEY] || {});
      });
    });
  }

  function saveState(partial) {
    loadState().then((current) => {
      chrome.storage.local.set({ [STORAGE_KEY]: { ...current, ...partial } });
    });
  }

  // Ícones (lucide, stroke = currentColor) dos botões do cabeçalho.
  const ICON_PIN =
    '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="12" x2="12" y1="17" y2="22"/><path d="M5 17h14v-1.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1v4.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24Z"/></svg>';
  const ICON_TABELA =
    '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect width="18" height="18" x="3" y="3" rx="2"/><path d="M3 9h18"/><path d="M3 15h18"/><path d="M12 3v18"/></svg>';

  /** Fixado = sempre opaco; senão fica translúcido e só fica opaco com o mouse em cima. */
  function aplicarPin(el, pinned) {
    el.classList.toggle("pinned", Boolean(pinned));
    const btn = el.querySelector(".pin-btn");
    btn.title = pinned ? "Desafixar (voltar a ficar translúcido)" : "Fixar opaco";
    btn.setAttribute("aria-pressed", pinned ? "true" : "false");
  }

  function buildWidget() {
    if (document.getElementById("sebratel-tma-widget")) return;

    const el = document.createElement("div");
    el.id = "sebratel-tma-widget";
    el.innerHTML = `
      <div class="header">
        <span><span class="status-dot"></span><span class="brand">Sebratel</span><span class="brand-system"> · TMA/TME</span></span>
        <span class="header-actions">
          <button type="button" class="hbtn table-btn" title="Tabela completa de atendimentos de hoje">${ICON_TABELA}</button>
          <button type="button" class="hbtn pin-btn" aria-pressed="false">${ICON_PIN}</button>
          <button type="button" class="hbtn close-btn" title="Fechar">&times;</button>
        </span>
      </div>
      <div class="body">
        <div class="section" id="sebratel-section-native">
          <div class="section-title"><span class="marker">Native · hoje</span><span class="section-meta" id="sebratel-native-meta"></span></div>
          <div class="metric-row">
            <span class="metric-label">TMA</span>
            <span class="metric-value" id="sebratel-native-tma">--:--</span>
          </div>
          <div class="metric-row">
            <span class="metric-label">TME</span>
            <span class="metric-value" id="sebratel-native-tme">--:--</span>
          </div>
          <div class="metric-row">
            <span class="metric-label">TMEA<span class="metric-sub" id="sebratel-native-tmea-ref"></span></span>
            <span class="metric-value" id="sebratel-native-tmea">--:--</span>
          </div>
          <div class="count-row" id="sebratel-native-count"></div>
          <div class="section-error" id="sebratel-native-error" hidden></div>
        </div>
        <div class="section" id="sebratel-section-matrix">
          <div class="section-title"><span class="marker">Matrix · hoje</span><span class="section-meta" id="sebratel-matrix-meta"></span></div>
          <div class="metric-row">
            <span class="metric-label">TMA</span>
            <span class="metric-value" id="sebratel-matrix-tma">--:--</span>
          </div>
          <div class="metric-row">
            <span class="metric-label">TME</span>
            <span class="metric-value" id="sebratel-matrix-tme">--:--</span>
          </div>
          <div class="metric-row">
            <span class="metric-label">TMIC</span>
            <span class="metric-value" id="sebratel-matrix-tmic">--:--</span>
          </div>
          <div class="metric-row">
            <span class="metric-label">TMIA</span>
            <span class="metric-value" id="sebratel-matrix-tmia">--:--</span>
          </div>
          <div class="metric-row">
            <span class="metric-label">TMEA<span class="metric-sub" id="sebratel-matrix-tmea-ref"></span></span>
            <span class="metric-value" id="sebratel-matrix-tmea">--:--</span>
          </div>
          <div class="count-row" id="sebratel-matrix-count"></div>
          <div class="section-error" id="sebratel-matrix-error" hidden></div>
        </div>
      </div>
      <div class="footer">
        <span id="sebratel-agent-name">--</span>
        <span id="sebratel-updated-at">--</span>
      </div>
    `;
    document.body.appendChild(el);

    el.querySelector(".close-btn").addEventListener("click", () => {
      el.style.display = "none";
      saveState({ hidden: true });
    });

    el.querySelector(".pin-btn").addEventListener("click", () => {
      const pinned = !el.classList.contains("pinned");
      aplicarPin(el, pinned);
      saveState({ pinned });
    });

    el.querySelector(".table-btn").addEventListener("click", () => {
      if (!extensaoValida()) return;
      try {
        chrome.runtime.sendMessage({ type: "openDetalhes" });
      } catch {
        /* script órfão: o aviso de F5 já está no widget */
      }
    });

    makeDraggable(el, el.querySelector(".header"));
    return el;
  }

  /** Se o widget ficou fora da tela (arrastado demais, janela redimensionada), volta ao canto. */
  function garantirVisivel(el) {
    if (el.style.display === "none") return;
    const r = el.getBoundingClientRect();
    const fora = r.right < 40 || r.left > window.innerWidth - 40 || r.bottom < 30 || r.top > window.innerHeight - 30;
    if (!fora) return;
    el.style.left = "auto";
    el.style.top = "auto";
    el.style.right = "20px";
    el.style.bottom = "20px";
    saveState({ left: null, top: null });
  }

  function makeDraggable(el, handle) {
    let dragging = false;
    let offsetX = 0;
    let offsetY = 0;

    handle.addEventListener("mousedown", (e) => {
      if (e.target.closest(".hbtn")) return; // clique nos botões do cabeçalho não arrasta
      dragging = true;
      el.classList.add("dragging");
      offsetX = e.clientX - el.offsetLeft;
      offsetY = e.clientY - el.offsetTop;
    });

    document.addEventListener("mousemove", (e) => {
      if (!dragging) return;
      el.style.left = `${e.clientX - offsetX}px`;
      el.style.top = `${e.clientY - offsetY}px`;
      el.style.right = "auto";
      el.style.bottom = "auto";
    });

    document.addEventListener("mouseup", () => {
      if (!dragging) return;
      dragging = false;
      el.classList.remove("dragging");
      saveState({ left: el.style.left, top: el.style.top });
    });
  }

  /**
   * m = { hoje, amostras, meta } vindo de /ext/widget (só dados do dia); null/undefined = sem dado.
   * Acima da meta o valor fica vermelho com "▲" e o título diz quanto passou.
   */
  function setMetric(el, id, m) {
    const node = el.querySelector(id);
    const seconds = m && typeof m === "object" ? m.hoje : null;
    if (seconds === null || seconds === undefined) {
      node.textContent = "--:--";
      node.className = "metric-value empty";
      node.title = m?.meta ? `Sem atendimentos hoje · limite ${fmt(m.meta)}` : "Sem atendimentos hoje";
      return;
    }
    const meta = m.meta;
    const excedeu = meta && seconds > meta;
    node.textContent = excedeu ? `▲ ${fmt(seconds)}` : fmt(seconds);
    node.className = `metric-value${meta ? " " + levelClass(seconds, meta) : ""}`;
    node.title =
      `Média de hoje · ${m.amostras} atendimento(s)` +
      (meta ? (excedeu ? ` · acima do limite de ${fmt(meta)} (+${fmt(seconds - meta)})` : ` · dentro do limite de ${fmt(meta)}`) : "");
  }

  /** TMEA comparado com a média do setor: abaixo/igual verde, acima âmbar (é referência, não limite). */
  function setTmea(el, sis, m) {
    const node = el.querySelector(`#sebratel-${sis}-tmea`);
    const refEl = el.querySelector(`#sebratel-${sis}-tmea-ref`);
    const ref = m?.referencia;
    const grupo = ref ? (ref.setor ? `setor ${ref.setor}` : "operação") : null;
    refEl.textContent = ref ? ` ${ref.setor ? "setor" : "operação"} ${fmt(Math.round(ref.segundosMedios))}` : "";
    const seconds = m ? m.hoje : null;
    if (seconds === null || seconds === undefined) {
      node.textContent = "--:--";
      node.className = "metric-value empty";
      node.title = "Tempo médio entre atendimentos: ainda sem dois atendimentos hoje";
      return;
    }
    node.textContent = fmt(seconds);
    if (!ref) {
      node.className = "metric-value";
      node.title = `Tempo médio entre atendimentos hoje · ${m.amostras} intervalo(s)`;
      return;
    }
    const media = Math.round(ref.segundosMedios);
    const dif = seconds - media;
    node.className = `metric-value ${dif <= 0 ? "ok" : "warn"}`;
    node.title =
      `Tempo médio entre atendimentos hoje · ${m.amostras} intervalo(s)\n` +
      `Média de ${ref.atendentes} colega(s) do ${grupo} nos últimos ${ref.dias} dias: ${fmt(media)} ` +
      `(${dif <= 0 ? "você está " + fmt(-dif) + " abaixo" : "você está " + fmt(dif) + " acima"})`;
  }

  /** "Atendimentos: 23 hoje · 412 no mês". */
  function setCount(el, sis, a) {
    const node = el.querySelector(`#sebratel-${sis}-count`);
    if (!a) {
      node.textContent = "";
      return;
    }
    const mes = a.mes === null || a.mes === undefined ? "…" : a.mes.toLocaleString("pt-BR");
    node.innerHTML = "";
    const label = document.createElement("span");
    label.className = "metric-label";
    label.textContent = "Atendimentos";
    const val = document.createElement("span");
    val.className = "count-value";
    val.textContent = `hoje ${a.hoje.toLocaleString("pt-BR")} · mês ${mes}`;
    node.title = "Hoje e do dia 1º do mês até agora";
    node.append(label, val);
  }

  /** "2026-09-28 09:13:33" -> "dados até 09:13" (horário do último registro que chegou ao banco). */
  function setMeta(el, id, ultimoRegistro) {
    const node = el.querySelector(id);
    node.textContent = ultimoRegistro ? `dados até ${ultimoRegistro.slice(11, 16)}` : "";
    node.title = "Horário do último atendimento recebido pelo banco hoje";
  }

  function renderMetrics(el, data) {
    const agentEl = el.querySelector("#sebratel-agent-name");
    const updatedEl = el.querySelector("#sebratel-updated-at");
    // Widget de outra versão da extensão na mesma página (ou mexido pelo site): não renderiza por cima.
    if (!agentEl || !updatedEl || !data) return;

    const nativeErrorEl = el.querySelector("#sebratel-native-error");
    if (data.native?.available) {
      nativeErrorEl.hidden = true;
      setMeta(el, "#sebratel-native-meta", data.native.ultimoRegistro);
      setMetric(el, "#sebratel-native-tma", data.native.tma);
      setMetric(el, "#sebratel-native-tme", data.native.tme);
      setTmea(el, "native", data.native.tmea);
      setCount(el, "native", data.native.atendimentos);
    } else {
      nativeErrorEl.hidden = false;
      nativeErrorEl.textContent = data.native?.error || "Indisponível";
      setMeta(el, "#sebratel-native-meta", null);
      setMetric(el, "#sebratel-native-tma", null);
      setMetric(el, "#sebratel-native-tme", null);
      setTmea(el, "native", null);
      setCount(el, "native", null);
    }

    const matrixErrorEl = el.querySelector("#sebratel-matrix-error");
    if (data.matrix?.available) {
      matrixErrorEl.hidden = true;
      setMeta(el, "#sebratel-matrix-meta", data.matrix.ultimoRegistro);
      setMetric(el, "#sebratel-matrix-tma", data.matrix.tma);
      setMetric(el, "#sebratel-matrix-tme", data.matrix.tme);
      setMetric(el, "#sebratel-matrix-tmic", data.matrix.tmic);
      setMetric(el, "#sebratel-matrix-tmia", data.matrix.tmia);
      setTmea(el, "matrix", data.matrix.tmea);
      setCount(el, "matrix", data.matrix.atendimentos);
    } else {
      matrixErrorEl.hidden = false;
      matrixErrorEl.textContent = data.matrix?.error || "Indisponível";
      setMeta(el, "#sebratel-matrix-meta", null);
      setMetric(el, "#sebratel-matrix-tma", null);
      setMetric(el, "#sebratel-matrix-tme", null);
      setMetric(el, "#sebratel-matrix-tmic", null);
      setMetric(el, "#sebratel-matrix-tmia", null);
      setTmea(el, "matrix", null);
      setCount(el, "matrix", null);
    }

    agentEl.textContent = (data.viewingOther ? `${data.agent} (admin)` : data.agent) || "--";
    const updatedAt = new Date(data.updatedAt);
    updatedEl.textContent = Number.isNaN(updatedAt.getTime()) ? "--" : updatedAt.toLocaleTimeString("pt-BR");
  }

  const RECARREGUE = "Extensão atualizada — recarregue a página (F5).";

  /** Depois de recarregar a extensão, o script que já estava nesta aba fica órfão (sem chrome.runtime). */
  function extensaoValida() {
    try {
      return Boolean(chrome.runtime?.id);
    } catch {
      return false;
    }
  }

  function requestMetrics() {
    return new Promise((resolve) => {
      if (!extensaoValida()) {
        resolve({ ok: false, error: RECARREGUE, orfao: true });
        return;
      }
      try {
        chrome.runtime.sendMessage({ type: "getMetrics" }, (resp) => {
          if (chrome.runtime.lastError || !resp) {
            resolve({ ok: false, error: "Extensão indisponível (recarregue a página)." });
            return;
          }
          resolve(resp);
        });
      } catch {
        resolve({ ok: false, error: RECARREGUE, orfao: true });
      }
    });
  }

  let timer = null;

  async function refresh(el) {
    const resp = await requestMetrics();
    if (resp.orfao && timer) {
      // Script órfão: para de consultar (cada tentativa só geraria erro) e deixa o aviso na tela.
      clearInterval(timer);
      timer = null;
    }
    if (!resp.ok) {
      renderMetrics(el, {
        agent: "(faça login no popup)",
        updatedAt: new Date().toISOString(),
        native: { available: false, error: resp.error },
        matrix: { available: false, error: resp.error },
      });
      return;
    }
    renderMetrics(el, resp.data);
    saveState({ lastMetrics: resp.data });
  }

  async function init() {
    const state = await loadState();
    const el = buildWidget();
    if (!el) return; // já existia (ex.: SPA re-injetando)

    if (state.hidden) {
      el.style.display = "none";
    }
    aplicarPin(el, state.pinned);
    if (state.left && state.top) {
      el.style.left = state.left;
      el.style.top = state.top;
      el.style.right = "auto";
      el.style.bottom = "auto";
    }
    garantirVisivel(el);
    window.addEventListener("resize", () => garantirVisivel(el));

    // Mostra o último dado conhecido imediatamente, sem esperar o fetch,
    // para não "piscar" ao trocar de página.
    if (state.lastMetrics) {
      renderMetrics(el, state.lastMetrics);
    }

    timer = setInterval(() => refresh(el).catch(() => {}), REFRESH_MS);
    refresh(el).catch(() => {});

    chrome.storage.onChanged.addListener((changes, area) => {
      if (area !== "local" || !changes[STORAGE_KEY]) return;
      const newVal = changes[STORAGE_KEY].newValue || {};
      el.style.display = newVal.hidden ? "none" : "block";
      aplicarPin(el, newVal.pinned); // fixar numa aba vale para todas
      garantirVisivel(el);
    });
  }

  init();
})();
