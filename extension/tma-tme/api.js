/*
 * Cliente das rotas /ext/** das APIs de produção (dev_SpringBoot_APIDashboards), autenticado com
 * o token Google da conta @sebratel.com.br logada via chrome.identity. Usado pelo background e pelo
 * popup — content scripts não têm acesso a chrome.identity, por isso o widget pede os dados ao
 * background em vez de chamar a API diretamente.
 *
 * Quem pode ver o quê (usuário comum só os próprios dados, admin qualquer atendente) é decidido
 * pelo servidor; aqui só repassamos o pedido.
 */
const SebratelApi = {
  NATIVE: "http://186.219.134.246:8092",
  MATRIX: "http://186.219.134.246:8091",
  CONFIG_KEY: "sebratelConfig",
  SLA: { tmaSeconds: 240, tmeSeconds: 60 },

  getToken(interactive) {
    return new Promise((resolve) => {
      chrome.identity.getAuthToken({ interactive }, (result) => {
        if (chrome.runtime.lastError || !result) {
          resolve(null);
          return;
        }
        resolve(typeof result === "string" ? result : result.token);
      });
    });
  },

  removeToken(token) {
    return new Promise((resolve) => chrome.identity.removeCachedAuthToken({ token }, resolve));
  },

  /** fetch autenticado; num 401 descarta o token em cache e tenta uma vez com um novo. */
  async request(base, path, { method = "GET", body, interactive = false } = {}) {
    let token = await this.getToken(interactive);
    if (!token) {
      throw Object.assign(new Error("Faça login no popup da extensão."), { status: 401 });
    }
    for (let tentativa = 0; tentativa < 2; tentativa++) {
      const resp = await fetch(`${base}${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          ...(body ? { "Content-Type": "application/json" } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
      });
      if (resp.status === 401 && tentativa === 0) {
        await this.removeToken(token);
        token = await this.getToken(false);
        if (!token) break;
        continue;
      }
      const json = await resp.json().catch(() => ({}));
      if (!resp.ok) {
        throw Object.assign(new Error(json.error || `API respondeu ${resp.status}`), { status: resp.status });
      }
      return json;
    }
    throw Object.assign(new Error("Sessão expirada. Faça login novamente no popup."), { status: 401 });
  },

  getConfig() {
    return new Promise((resolve) => {
      chrome.storage.local.get([this.CONFIG_KEY], (r) => resolve(r[this.CONFIG_KEY] || {}));
    });
  },

  setConfig(patch) {
    return this.getConfig().then(
      (cfg) => new Promise((resolve) => chrome.storage.local.set({ [this.CONFIG_KEY]: { ...cfg, ...patch } }, resolve))
    );
  },

  /** GET /ext/widget: tempos de hoje + horário do último registro ingerido. */
  widget(base, atendente) {
    const q = atendente ? `?atendente=${encodeURIComponent(atendente)}` : "";
    return this.request(base, `/ext/widget${q}`);
  },

  /** GET /ext/widget/detalhe: atendimentos de hoje, um a um, com a formação de cada tempo. */
  detalhe(base, atendente) {
    const q = atendente ? `?atendente=${encodeURIComponent(atendente)}` : "";
    return this.request(base, `/ext/widget/detalhe${q}`);
  },

  async getMetrics() {
    const cfg = await this.getConfig();
    const alvo = cfg.viewingAgent || "";

    let [native, matrix] = await Promise.allSettled([this.widget(this.NATIVE, alvo), this.widget(this.MATRIX, alvo)]);

    // 403: o servidor não reconhece mais este usuário como admin — volta para os próprios dados.
    if (alvo && [native, matrix].some((r) => r.status === "rejected" && r.reason.status === 403)) {
      await this.setConfig({ viewingAgent: "" });
      [native, matrix] = await Promise.allSettled([this.widget(this.NATIVE, ""), this.widget(this.MATRIX, "")]);
    }

    if (native.status === "rejected" && matrix.status === "rejected") {
      throw native.reason;
    }

    const ok = native.status === "fulfilled" ? native.value : matrix.value;

    // Só dados do dia: o widget é diário; períodos maiores ficam com os gestores (dashboards).
    // null = ainda não houve atendimento hoje.
    const metrica = (res, chave) => ({
      hoje: res.hoje[chave] ? Math.round(res.hoje[chave].segundosMedios) : null,
      amostras: res.hoje[chave]?.amostras || 0,
    });
    const secao = (res, chaves) => {
      if (res.status !== "fulfilled") {
        return { available: false, error: res.reason?.message || "Dados indisponíveis" };
      }
      const out = { available: true, ultimoRegistro: res.value.ultimoRegistro };
      for (const [nome, chave] of Object.entries(chaves)) out[nome] = metrica(res.value, chave);
      return out;
    };

    return {
      agent: ok.atendente,
      viewingOther: Boolean(alvo) && ok.atendente === alvo,
      updatedAt: new Date().toISOString(),
      slaTarget: this.SLA,
      // Chaves de app.widget.tempos no servidor: "tma"/"tme" nas duas APIs, + "tmic"/"tmia" na Matrix.
      native: secao(native, { tma: "tma", tme: "tme" }),
      matrix: secao(matrix, { tma: "tma", tme: "tme", tmic: "tmic", tmia: "tmia" }),
    };
  },
};
