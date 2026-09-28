(function () {
  const REFRESH_MS = 15000;
  const STORAGE_KEY = "sebratelWidgetState";

  function fmt(seconds) {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${String(s).padStart(2, "0")}`;
  }

  function levelClass(value, target) {
    if (value <= target) return "ok";
    if (value <= target * 1.2) return "warn";
    return "bad";
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

  function buildWidget() {
    if (document.getElementById("sebratel-tma-widget")) return;

    const el = document.createElement("div");
    el.id = "sebratel-tma-widget";
    el.innerHTML = `
      <div class="header">
        <span><span class="status-dot"></span><span class="brand">Sebratel</span><span class="brand-system"> · TMA/TME</span></span>
        <span class="close-btn" title="Fechar">&times;</span>
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

  /** m = { hoje, amostras } vindo de /ext/widget (só dados do dia); null/undefined = sem dado. */
  function setMetric(el, id, m, target) {
    const node = el.querySelector(id);
    const seconds = m && typeof m === "object" ? m.hoje : null;
    if (seconds === null || seconds === undefined) {
      node.textContent = "--:--";
      node.className = "metric-value empty";
      node.title = "Sem atendimentos hoje";
      return;
    }
    node.textContent = fmt(seconds);
    node.className = `metric-value${target ? " " + levelClass(seconds, target) : ""}`;
    node.title = `Média de hoje · ${m.amostras} atendimento(s)`;
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
      setMetric(el, "#sebratel-native-tma", data.native.tma, data.slaTarget.tmaSeconds);
      setMetric(el, "#sebratel-native-tme", data.native.tme, data.slaTarget.tmeSeconds);
    } else {
      nativeErrorEl.hidden = false;
      nativeErrorEl.textContent = data.native?.error || "Indisponível";
      setMeta(el, "#sebratel-native-meta", null);
      setMetric(el, "#sebratel-native-tma", null);
      setMetric(el, "#sebratel-native-tme", null);
    }

    const matrixErrorEl = el.querySelector("#sebratel-matrix-error");
    if (data.matrix?.available) {
      matrixErrorEl.hidden = true;
      setMeta(el, "#sebratel-matrix-meta", data.matrix.ultimoRegistro);
      setMetric(el, "#sebratel-matrix-tma", data.matrix.tma, data.slaTarget.tmaSeconds);
      setMetric(el, "#sebratel-matrix-tme", data.matrix.tme, data.slaTarget.tmeSeconds);
      setMetric(el, "#sebratel-matrix-tmic", data.matrix.tmic);
      setMetric(el, "#sebratel-matrix-tmia", data.matrix.tmia);
    } else {
      matrixErrorEl.hidden = false;
      matrixErrorEl.textContent = data.matrix?.error || "Indisponível";
      setMeta(el, "#sebratel-matrix-meta", null);
      setMetric(el, "#sebratel-matrix-tma", null);
      setMetric(el, "#sebratel-matrix-tme", null);
      setMetric(el, "#sebratel-matrix-tmic", null);
      setMetric(el, "#sebratel-matrix-tmia", null);
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
        slaTarget: { tmaSeconds: 240, tmeSeconds: 60 },
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
      garantirVisivel(el);
    });
  }

  init();
})();
