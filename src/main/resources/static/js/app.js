/* Locus 관제 — 진입점. devices Map을 단일 진실원으로 마커·사이드바·패널·HUD를 구동. */
(function () {
  "use strict";
  const U = window.Locus.util;
  const L_ = window.L;

  // 조직 스코프 — URL ?org=org-3, 기본 org-0.
  const ORG = new URLSearchParams(location.search).get("org") || "org-0";

  // 지도 = 컬러 OSM 한 장. 다크는 색을 죽이지 않고 CSS 필터로 톤만 낮춤(라벨은 밝게 반전돼 읽힘),
  // 라이트는 필터 off. 테마는 body.light 클래스만 토글(팔레트·지도톤·마커 외곽선은 CSS가 처리).
  const map = L_.map("map", { zoomControl: true }).setView([37.5, 127.0], 13);
  L_.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: "&copy; OpenStreetMap",
  }).addTo(map);

  const themeBtn = document.getElementById("theme-toggle");
  function applyTheme(theme) {
    const light = theme === "light";
    document.body.classList.toggle("light", light);
    themeBtn.textContent = light ? "다크" : "라이트";
    try {
      localStorage.setItem("locus-theme", theme);
    } catch (e) {}
  }
  themeBtn.addEventListener("click", () =>
    applyTheme(document.body.classList.contains("light") ? "dark" : "light")
  );
  let initTheme = "dark";
  try {
    initTheme = new URLSearchParams(location.search).get("theme") || localStorage.getItem("locus-theme") || "dark";
  } catch (e) {}
  applyTheme(initTheme);

  const devices = new Map(); // deviceId -> 최신 TelemetryResponse (단일 진실원)
  let selectedId = null;
  const filterState = { types: new Set(["PHONE", "AMR"]), onlyOffline: false, onlyEstop: false, onlyLowBattery: false };

  const detailEl = document.getElementById("detail");
  const listEl = document.getElementById("sidebar-list");
  const renderer = window.Locus.createMarkerRenderer(map, { onSelect: select });
  window.Locus.renderLegend(document.getElementById("legend"));
  window.Locus.clearDetail(detailEl);

  // ── 지오펜스 (M5): 존 원 + 이벤트 피드 ──
  const eventsEl = document.getElementById("events");
  const geofenceEvents = [];
  let zoneLayers = new Map();
  window.Locus.renderEvents(eventsEl, geofenceEvents);

  async function loadZones() {
    const res = await fetch("/api/geofences?org=" + encodeURIComponent(ORG), { cache: "no-store" });
    if (!res.ok) return;
    zoneLayers = window.Locus.drawZones(map, await res.json());
  }

  function onGeofenceEvent(ev) {
    geofenceEvents.unshift(ev);
    if (geofenceEvents.length > 30) geofenceEvents.pop();
    window.Locus.renderEvents(eventsEl, geofenceEvents);
    window.Locus.pulseZone(zoneLayers.get(ev.geofenceId), ev.type);
    renderer.flash(ev.deviceId);
  }

  // ── 필터: 데이터가 아니라 가시성만 바꿈 ──────────────────────
  function visible(t) {
    if (!filterState.types.has(t.deviceType)) return false;
    if (filterState.onlyOffline && U.isOnline(t)) return false;
    if (filterState.onlyEstop && !U.isEstop(t)) return false;
    if (filterState.onlyLowBattery && !U.isLowBattery(t)) return false;
    return true;
  }

  function applyFilters() {
    devices.forEach((t) => renderer.render(t, visible(t)));
    renderSidebar();
  }

  // ── 사이드바 (rAF 디바운스로 push 폭주 시 재그리기 억제) ──────
  let sbScheduled = false;
  function scheduleSidebar() {
    if (sbScheduled) return;
    sbScheduled = true;
    requestAnimationFrame(() => {
      sbScheduled = false;
      renderSidebar();
    });
  }
  function renderSidebar() {
    window.Locus.renderList(listEl, devices, { selectedId, visible });
  }

  // ── 선택 ─────────────────────────────────────────────────────
  function select(id) {
    selectedId = id;
    const t = devices.get(id);
    if (t) {
      window.Locus.renderDetail(detailEl, t);
      const m = renderer.marker(id);
      if (m) map.panTo(m.getLatLng());
    }
    renderer.setSelected(id);
    renderSidebar();
  }

  // 사이드바 행 클릭(위임)
  listEl.addEventListener("click", (e) => {
    const row = e.target.closest("[data-id]");
    if (row) select(row.getAttribute("data-id"));
  });

  // ── upsert: 스냅샷·push 공용 ──────────────────────────────────
  let rateCount = 0;
  function upsert(t) {
    devices.set(t.deviceId, t);
    rateCount++;
    renderer.render(t, visible(t));
    if (t.deviceId === selectedId) window.Locus.renderDetail(detailEl, t);
    scheduleSidebar();
  }

  // ── HUD ──────────────────────────────────────────────────────
  const hud = {
    total: document.getElementById("hud-total"),
    amr: document.getElementById("hud-amr"),
    phone: document.getElementById("hud-phone"),
    warn: document.getElementById("hud-warn"),
    rate: document.getElementById("hud-rate"),
    dot: document.getElementById("hud-dot"),
    conn: document.getElementById("hud-conn"),
  };
  let rate = 0;
  function updateHud() {
    let amr = 0,
      phone = 0,
      warn = 0;
    devices.forEach((t) => {
      if (t.deviceType === "AMR") amr++;
      else if (t.deviceType === "PHONE") phone++;
      if (U.isWarning(t)) warn++;
    });
    hud.total.textContent = devices.size;
    hud.amr.textContent = amr;
    hud.phone.textContent = phone;
    hud.warn.textContent = warn;
    hud.rate.textContent = rate;
  }
  function setConn(up, msg) {
    hud.dot.className = "dot " + (up ? "up" : "down");
    hud.conn.textContent = msg;
  }

  // ── 스테일/오프라인 sweep ─────────────────────────────────────
  setInterval(() => {
    const now = Date.now();
    devices.forEach((t, id) => {
      const age = now - Date.parse(t.recordedAt);
      renderer.setStale(id, age > window.Locus.cfg.STALE_MS, age > window.Locus.cfg.DEAD_MS);
    });
    scheduleSidebar();
  }, 1000);

  // ── 레이트 (초당 수신) ────────────────────────────────────────
  setInterval(() => {
    rate = rateCount;
    rateCount = 0;
    updateHud();
  }, 1000);

  // ── 필터 버튼 배선 ────────────────────────────────────────────
  function bindToggle(id, apply) {
    const btn = document.getElementById(id);
    btn.addEventListener("click", () => {
      apply(btn);
      btn.classList.toggle("on");
      applyFilters();
    });
  }
  bindToggle("f-amr", () => {
    filterState.types.has("AMR") ? filterState.types.delete("AMR") : filterState.types.add("AMR");
  });
  bindToggle("f-phone", () => {
    filterState.types.has("PHONE") ? filterState.types.delete("PHONE") : filterState.types.add("PHONE");
  });
  bindToggle("f-offline", () => (filterState.onlyOffline = !filterState.onlyOffline));
  bindToggle("f-estop", () => (filterState.onlyEstop = !filterState.onlyEstop));
  bindToggle("f-lowbat", () => (filterState.onlyLowBattery = !filterState.onlyLowBattery));
  // 타입 필터는 기본 on 표기
  document.getElementById("f-amr").classList.add("on");
  document.getElementById("f-phone").classList.add("on");

  document.getElementById("hud-org").textContent = "(" + ORG + ")";

  // ── 접속: 스냅샷 1회 → STOMP push. 스냅샷 실패해도 push는 시도 ──
  async function snapshot() {
    const res = await fetch("/api/telemetry/latest?org=" + encodeURIComponent(ORG), { cache: "no-store" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    for (const t of await res.json()) upsert(t);
  }

  function connect() {
    const client = new StompJs.Client({
      webSocketFactory: () => new SockJS("/ws"),
      reconnectDelay: 2000,
      onConnect: () => {
        client.subscribe("/topic/org/" + ORG, (msg) => upsert(JSON.parse(msg.body)));
        client.subscribe("/topic/org/" + ORG + "/geofence", (msg) => onGeofenceEvent(JSON.parse(msg.body)));
        setConn(true, "실시간 연결됨 · " + new Date().toLocaleTimeString());
      },
      onStompError: (f) => setConn(false, "STOMP 오류: " + f.headers["message"]),
      onWebSocketClose: () => setConn(false, "연결 끊김 — 재접속 중"),
    });
    client.activate();
  }

  loadZones().catch(() => {});
  updateHud();
  snapshot()
    .catch((e) => setConn(false, "스냅샷 실패: " + e.message + " (push 대기)"))
    .finally(connect);
})();
