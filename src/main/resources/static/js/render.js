/* Locus 관제 — 렌더링 계층. 전역 네임스페이스 window.Locus 에 부착(빌드 스텝 없음). */
(function () {
  "use strict";
  const Locus = (window.Locus = window.Locus || {});

  // ── 설정/임계 ────────────────────────────────────────────────
  const cfg = (Locus.cfg = {
    STALE_MS: 15000, // 이 시간 넘게 갱신 없으면 흐림
    DEAD_MS: 60000, // 이 시간 넘으면 더 흐림
    LOW_BATTERY: 20,
    // 범례 = 상태색 정의의 단일 출처
    legend: [
      { cls: "ok", label: "정상 / 폰 온라인" },
      { cls: "driving", label: "AMR 주행" },
      { cls: "warn", label: "경고(WARN)" },
      { cls: "alert", label: "긴급정지 / 치명 / 저배터리" },
      { cls: "offline", label: "폰 오프라인" },
      { cls: "idle", label: "대기 / 미상" },
    ],
  });

  // ── 타입 인지 접근자 (함정: 폰 metrics 중첩, AMR 평탄) ─────────
  const U = (Locus.util = {});

  U.battery = function (t) {
    const m = t.metrics || {};
    if (t.deviceType === "AMR") return typeof m.batteryPercent === "number" ? m.batteryPercent : null;
    return m.battery && typeof m.battery.level === "number" ? m.battery.level : null;
  };

  U.isCharging = function (t) {
    const m = t.metrics || {};
    if (t.deviceType === "AMR") return m.batteryStatus === "CHARGING";
    return !!(m.battery && m.battery.charging);
  };

  U.isOnline = function (t) {
    // 폰만 명시적 online 필드. AMR은 개념상 온라인(수집되면 연결됨).
    const m = t.metrics || {};
    if (t.deviceType === "PHONE") return !(m.network && m.network.online === false);
    return true;
  };

  U.isEstop = function (t) {
    return t.deviceType === "AMR" && (t.metrics || {}).estopState === "ESTOPPED";
  };

  U.isLowBattery = function (t) {
    const b = U.battery(t);
    return b != null && b < cfg.LOW_BATTERY;
  };

  U.hasLocation = function (t) {
    return t.location && t.location.lat != null && t.location.lng != null;
  };

  // 마커/칩 색을 정하는 1차 상태 키
  U.state = function (t) {
    const m = t.metrics || {};
    if (t.deviceType === "AMR") {
      if (m.estopState === "ESTOPPED" || m.faultLevel === "FATAL") return "alert";
      if (m.faultLevel === "WARN") return "warn";
      if (m.driving === true) return "driving";
      return "idle";
    }
    if (m.network && m.network.online === false) return "offline";
    if (U.isLowBattery(t)) return "lowbat";
    return "ok";
  };

  // 경고 판정(HUD 카운트·정렬용)
  U.isWarning = function (t) {
    const s = U.state(t);
    return s === "alert" || s === "warn" || U.isLowBattery(t);
  };

  U.fmtRelTime = function (iso) {
    const ms = Date.now() - Date.parse(iso);
    if (!isFinite(ms)) return "-";
    const s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + "초 전";
    const mnt = Math.floor(s / 60);
    if (mnt < 60) return mnt + "분 전";
    return Math.floor(mnt / 60) + "시간 전";
  };

  function num(v, d) {
    return typeof v === "number" ? v.toFixed(d == null ? 1 : d) : "-";
  }

  // 아이콘을 재생성해야 하는 상태 조합(위치만 바뀌면 재생성 안 함)
  function iconSignature(t) {
    const b = U.battery(t);
    const bucket = b == null ? "-" : Math.round(b / 10);
    const h = U.hasLocation(t) && t.location.heading != null ? Math.round(t.location.heading / 15) : "-";
    return [t.deviceType, U.state(t), bucket, h, U.isCharging(t) ? "c" : ""].join("|");
  }

  // ── divIcon 마커 빌더 (인라인 SVG, 새 의존성 0) ────────────────
  function buildIcon(t) {
    const type = t.deviceType;
    const st = U.state(t);
    const pct = U.battery(t);
    const heading = U.hasLocation(t) ? t.location.heading : null;
    const charging = U.isCharging(t);
    const size = 34,
      c = size / 2,
      r = 13;
    const circ = 2 * Math.PI * r;
    const dash = pct == null ? 0 : (circ * pct) / 100;
    const ringCls = pct == null ? "" : pct < 20 ? "bat-low" : pct < 50 ? "bat-warn" : "bat-ok";
    const body =
      type === "PHONE"
        ? `<circle cx="${c}" cy="${c}" r="7" class="body"/>`
        : `<rect x="${c - 6}" y="${c - 6}" width="12" height="12" rx="2" class="body"/>`;
    const pointer =
      heading == null
        ? ""
        : `<polygon points="${c},1.5 ${c - 3.5},8 ${c + 3.5},8" class="pointer" transform="rotate(${heading} ${c} ${c})"/>`;
    const badge = charging
      ? `<path class="charging" d="M${c + 5},${c - 6} l-4,6 h3 l-2,6 6,-8 h-3 z"/>`
      : "";
    const halo = `<circle cx="${c}" cy="${c}" r="${r + 3}" class="halo"/>`;
    const html =
      `<div class="mk mk-${type.toLowerCase()} state-${st}">` +
      `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">` +
      halo +
      `<circle cx="${c}" cy="${c}" r="${r}" class="ring-bg"/>` +
      `<circle cx="${c}" cy="${c}" r="${r}" class="ring ${ringCls}" stroke-dasharray="${dash} ${circ}" transform="rotate(-90 ${c} ${c})"/>` +
      body +
      pointer +
      badge +
      `</svg></div>`;
    return L.divIcon({ html, className: "mk-wrap", iconSize: [size, size], iconAnchor: [c, c] });
  }

  function tooltip(t) {
    const s = U.state(t);
    const b = U.battery(t);
    return `${t.deviceId} · ${t.deviceType}${b == null ? "" : " · " + b + "%"} · ${s}`;
  }

  // ── 마커 렌더러 (교체 이음새: 나중 CanvasRenderer로 대체 가능) ──
  Locus.createMarkerRenderer = function (map, opts) {
    const onSelect = (opts && opts.onSelect) || function () {};
    const markers = new Map(); // deviceId -> L.marker (+ _sig/_stale/_dead/_selected)

    function applyClasses(m) {
      const el = m.getElement && m.getElement();
      if (!el) return;
      el.classList.toggle("stale", !!m._stale && !m._dead);
      el.classList.toggle("dead", !!m._dead);
      el.classList.toggle("selected", !!m._selected);
    }

    function render(t, show) {
      const has = U.hasLocation(t);
      let m = markers.get(t.deviceId);
      if (!has) {
        if (m && map.hasLayer(m)) map.removeLayer(m); // 위치 없음(프라이버시 게이트) → 지도서 빼되 리스트엔 유지
        return;
      }
      const ll = [t.location.lat, t.location.lng];
      if (!m) {
        m = L.marker(ll, { icon: buildIcon(t) });
        m._sig = iconSignature(t);
        m.bindTooltip(tooltip(t), { direction: "top", offset: [0, -14] });
        m.on("click", () => onSelect(t.deviceId));
        markers.set(t.deviceId, m);
      } else {
        m.setLatLng(ll);
        const sig = iconSignature(t);
        if (sig !== m._sig) {
          m.setIcon(buildIcon(t));
          m._sig = sig;
        }
        m.setTooltipContent(tooltip(t));
      }
      if (show) {
        if (!map.hasLayer(m)) m.addTo(map);
      } else if (map.hasLayer(m)) {
        map.removeLayer(m);
      }
      applyClasses(m); // setIcon이 엘리먼트를 재생성하므로 클래스 재적용
    }

    return {
      render,
      marker: (id) => markers.get(id),
      remove(id) {
        const m = markers.get(id);
        if (m) {
          map.removeLayer(m);
          markers.delete(id);
        }
      },
      setSelected(id) {
        markers.forEach((m, mid) => {
          m._selected = mid === id;
          applyClasses(m);
        });
      },
      setStale(id, stale, dead) {
        const m = markers.get(id);
        if (!m) return;
        m._stale = stale;
        m._dead = dead;
        applyClasses(m);
      },
    };
  };

  // ── 상세 패널 (타입별 필드셋) ──────────────────────────────────
  function kv(k, v) {
    return `<div class="k">${k}</div><div class="v">${v == null || v === "" ? "-" : v}</div>`;
  }
  function chip(label, cls) {
    return `<span class="chip ${cls || ""}">${label}</span>`;
  }

  Locus.renderDetail = function (el, t) {
    const loc = t.location;
    const b = U.battery(t);
    const header =
      `<h2>${t.deviceId}</h2>` +
      `<div class="sub">${chip(t.deviceType, t.deviceType === "AMR" ? "driving" : "ok")} · ${U.fmtRelTime(
        t.recordedAt
      )}</div>`;
    const locSec = U.hasLocation(t)
      ? `<div class="kv">${kv("좌표", num(loc.lat, 5) + ", " + num(loc.lng, 5))}${kv(
          "정확도",
          loc.accuracy == null ? "-" : num(loc.accuracy, 0) + " m"
        )}${kv("속도", loc.speed == null ? "-" : num(loc.speed, 1) + " m/s")}${kv(
          "방향",
          loc.heading == null ? "-" : num(loc.heading, 0) + "°"
        )}</div>`
      : `<div class="noloc sec">위치 미공유 (프라이버시 게이트 — permission=DENIED 또는 공유 off)</div>`;

    let typed = "";
    const m = t.metrics || {};
    if (t.deviceType === "AMR") {
      const est = m.estopState === "ESTOPPED" ? chip("ESTOPPED", "alert") : chip("정상", "ok");
      const fault =
        m.faultLevel === "FATAL"
          ? chip("FATAL", "alert")
          : m.faultLevel === "WARN"
          ? chip("WARN", "warn")
          : chip("OK", "ok");
      typed =
        `<div class="sec"><div class="sec-title">AMR 상태</div><div class="kv">` +
        kv("배터리", (b == null ? "-" : b + "%") + " · " + (m.batteryStatus || "-")) +
        kv("운영모드", m.operatingMode) +
        kv("주행", m.driving === true ? "주행 중" : m.driving === false ? "정지" : "-") +
        kv("긴급정지", est) +
        kv("장애", fault) +
        kv("odom", `x ${num(m.odomX, 2)} · y ${num(m.odomY, 2)} · θ ${num(m.odomTheta, 2)} rad`) +
        kv("맵", m.mapId) +
        `</div></div>`;
    } else {
      const net = m.network || {};
      typed =
        `<div class="sec"><div class="sec-title">폰 상태</div><div class="kv">` +
        kv("배터리", (b == null ? "-" : b + "%") + (U.isCharging(t) ? " · 충전 중" : "")) +
        kv("네트워크", (net.type || "-") + " · " + (net.online === false ? "오프라인" : "온라인")) +
        kv("활동", m.activity) +
        kv("앱 상태", m.appState) +
        kv("권한", m.permission) +
        kv("위치공유", m.sharingEnabled === true ? "on" : m.sharingEnabled === false ? "off" : "-") +
        `</div></div>`;
    }
    el.innerHTML = header + `<div class="sec"><div class="sec-title">위치</div>${locSec}</div>` + typed;
  };

  Locus.clearDetail = function (el) {
    el.innerHTML = `<div class="empty">디바이스를 선택하면 상세가 표시됩니다.</div>`;
  };

  // ── 사이드바 리스트 (제자리 갱신 — 구조 변화 때만 재생성해 호버 깜빡임 방지) ──
  function swatchColor(t) {
    const s = U.state(t);
    return "var(--" + (s === "lowbat" ? "alert" : s) + ")";
  }
  function rowMarkup(t, selectedId, now) {
    const age = now - Date.parse(t.recordedAt);
    const staleCls = age > cfg.DEAD_MS ? "dead" : age > cfg.STALE_MS ? "stale" : "";
    const b = U.battery(t);
    const noloc = U.hasLocation(t) ? "" : " · 위치없음";
    return (
      `<div class="row ${t.deviceId === selectedId ? "selected" : ""} ${staleCls}" data-id="${t.deviceId}">` +
      `<span class="swatch" style="background:${swatchColor(t)}"></span>` +
      `<span class="rid">${t.deviceId}${noloc}</span>` +
      `<span class="rbat">${b == null ? "" : b + "%"}</span>` +
      `</div>`
    );
  }
  function updateRow(row, t, selectedId, now) {
    const age = now - Date.parse(t.recordedAt);
    const cls = age > cfg.DEAD_MS ? " dead" : age > cfg.STALE_MS ? " stale" : "";
    row.className = "row" + (t.deviceId === selectedId ? " selected" : "") + cls;
    const b = U.battery(t);
    row.children[0].style.background = swatchColor(t);
    row.children[1].textContent = t.deviceId + (U.hasLocation(t) ? "" : " · 위치없음");
    row.children[2].textContent = b == null ? "" : b + "%";
  }

  Locus.renderList = function (el, devices, o) {
    const now = Date.now();
    const groups = { AMR: [], PHONE: [] };
    devices.forEach((t) => {
      if (o.visible && !o.visible(t)) return;
      (groups[t.deviceType] || (groups[t.deviceType] = [])).push(t);
    });
    const seq = [];
    ["AMR", "PHONE"].forEach((type) => {
      const list = groups[type] || [];
      if (!list.length) return;
      list.sort((a, b) => (U.isWarning(b) ? 1 : 0) - (U.isWarning(a) ? 1 : 0));
      seq.push({ kind: "head", key: "h:" + type + ":" + list.length, label: type + " · " + list.length });
      list.forEach((t) => seq.push({ kind: "row", key: "r:" + t.deviceId, t }));
    });
    // 키 시퀀스(멤버십·순서·카운트)가 그대로면 재생성 안 함 → 호버 중 DOM 파괴 없음
    const keys = seq.map((s) => s.key).join("|");
    if (el._keys !== keys) {
      el.innerHTML =
        seq
          .map((s) =>
            s.kind === "head" ? `<div class="group-head">${s.label}</div>` : rowMarkup(s.t, o.selectedId, now)
          )
          .join("") || `<div class="group-head">표시할 디바이스 없음</div>`;
      el._keys = keys;
    } else {
      seq.forEach((s) => {
        if (s.kind !== "row") return;
        const row = el.querySelector('[data-id="' + s.t.deviceId + '"]');
        if (row) updateRow(row, s.t, o.selectedId, now);
      });
    }
  };

  // ── 범례 ──────────────────────────────────────────────────────
  Locus.renderLegend = function (el) {
    el.innerHTML = cfg.legend
      .map((l) => `<div class="li"><span class="sw" style="background:var(--${l.cls})"></span>${l.label}</div>`)
      .join("");
  };
})();
