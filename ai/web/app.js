// 반납 흐름: ① GPS 위치 확인 -> ② 주차 사진 AI 판정 -> ③ 결제
const $ = (s) => document.querySelector(s);
const state = { token: null, zone: null, stream: null, torch: false, lastPos: null };

function show(id) {
  document.querySelectorAll(".screen").forEach((el) => el.classList.toggle("active", el.id === id));
  if (id === "screen-camera") startCamera(); else stopCamera();
  if (id === "screen-home") setTimeout(() => map.invalidateSize(), 50);
}

async function api(path, opts = {}) {
  const res = await fetch(path, opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.detail || `요청 실패 (${res.status})`);
  return body;
}

function setMsg(el, text, kind = "") { el.textContent = text; el.className = "msg " + kind; }

// ---------- 지도 ----------
const map = L.map("map", { zoomControl: false }).setView([35.8924, 128.6094], 17);
L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(map);
const zoneStyle = {
  service_area: { color: "#1a73ff", weight: 1, fillOpacity: 0.04, dashArray: "4" },
  parking: { color: "#18a058", weight: 2, fillOpacity: 0.35 },
  no_parking: { color: "#e5484d", weight: 2, fillOpacity: 0.35 },
};
let me = null;

function centroid(geom) {
  const ring = (geom.type === "Polygon" ? geom.coordinates : geom.coordinates[0])[0];
  const n = ring.length - 1;
  let x = 0, y = 0;
  for (let i = 0; i < n; i++) { x += ring[i][0]; y += ring[i][1]; }
  return [y / n, x / n];
}

Promise.all([api("/api/zones"), api("/api/config")]).then(([gj, cfg]) => {
  L.geoJSON(gj, {
    style: (f) => zoneStyle[f.properties.type] || {},
    onEachFeature: (f, layer) => layer.bindTooltip(f.properties.name || f.properties.type),
  }).addTo(map);
  if (!cfg.dev) return;
  $("#dev-tools").hidden = false;
  const spots = $("#dev-spots");
  for (const f of gj.features) {
    const [lat, lon] = centroid(f.geometry);
    const b = document.createElement("button");
    b.textContent = { service_area: "🔵", parking: "🟢", no_parking: "🔴" }[f.properties.type] + " " + f.properties.name;
    b.onclick = () => checkLocation(lat, lon, 5);
    spots.appendChild(b);
  }
  const out = document.createElement("button");
  out.textContent = "⚪ 서비스 지역 밖";
  out.onclick = () => checkLocation(35.87, 128.59, 5);
  spots.appendChild(out);
});

// ---------- ① 위치 ----------
async function checkLocation(lat, lon, accuracy) {
  const msg = $("#loc-msg");
  if (me) me.setLatLng([lat, lon]); else me = L.circleMarker([lat, lon], { radius: 8, color: "#fff", weight: 3, fillColor: "#1a73ff", fillOpacity: 1 }).addTo(map);
  map.setView([lat, lon], 18);
  setMsg(msg, "반납 가능 위치인지 확인 중…");
  try {
    const r = await api("/api/return/location", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ vehicle_id: $("#vehicle-id").value, lat, lon, accuracy }),
    });
    if (!r.ok) return setMsg(msg, r.message, "bad");
    state.token = r.token;
    state.zone = r.zone ? r.zone.name : "서비스 지역";
    setMsg(msg, r.message, "ok");
    setTimeout(() => show("screen-camera"), 500);
  } catch (e) { setMsg(msg, e.message, "bad"); }
}

$("#btn-locate").onclick = () => {
  const msg = $("#loc-msg");
  if (!navigator.geolocation) return setMsg(msg, "이 브라우저는 위치를 지원하지 않아요.", "bad");
  if (!window.isSecureContext) return setMsg(msg, "위치 사용은 HTTPS(또는 localhost)에서만 돼요. 아래 테스트 위치를 써보세요.", "bad");
  setMsg(msg, "위치를 가져오는 중…");
  navigator.geolocation.getCurrentPosition(
    (p) => checkLocation(p.coords.latitude, p.coords.longitude, p.coords.accuracy),
    (e) => setMsg(msg, "위치를 가져오지 못했어요: " + e.message, "bad"),
    { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 },
  );
};

// ---------- ② 촬영 ----------
async function startCamera() {
  const fallback = $("#cam-fallback");
  if (!navigator.mediaDevices?.getUserMedia) { fallback.hidden = false; return; }
  try {
    state.stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false,
    });
    $("#video").srcObject = state.stream;
    fallback.hidden = true;
  } catch { fallback.hidden = false; }
}

function stopCamera() {
  state.stream?.getTracks().forEach((t) => t.stop());
  state.stream = null;
  state.torch = false;
  $("#btn-torch").classList.remove("on");
}

$("#btn-torch").onclick = async () => {
  const track = state.stream?.getVideoTracks()[0];
  if (!track?.getCapabilities?.().torch) return;
  state.torch = !state.torch;
  await track.applyConstraints({ advanced: [{ torch: state.torch }] });
  $("#btn-torch").classList.toggle("on", state.torch);
};

$("#btn-shutter").onclick = () => {
  const v = $("#video");
  if (!state.stream || !v.videoWidth) return $("#file-input").click();
  // 파란 틀만이 아니라 화면 전체를 보낸다 (주변 보도 폭 판단에 필요)
  const c = document.createElement("canvas");
  c.width = v.videoWidth; c.height = v.videoHeight;
  c.getContext("2d").drawImage(v, 0, 0);
  c.toBlob((b) => submitPhoto(b), "image/jpeg", 0.9);
};
$("#file-input").onchange = (e) => { const f = e.target.files[0]; e.target.value = ""; if (f) submitPhoto(f); };
$("#btn-cam-back").onclick = () => show("screen-home");

const METRIC_LABELS = {
  residual_cm: ["남은 통행폭 (병목)", (v) => `${v}cm (최소 120cm)`],
  original_cm: ["원래 보도 폭", (v) => `${v}cm`],
  reduced_cm: ["기기 때문에 줄어든 폭", (v) => `${v}cm`],
  residual_cm_est: ["남은 통행폭 (차체 길이로 추정)", (v) => `약 ${v}cm`],
  score: ["침해 점수", (v) => `${v} / 100`],
  width_occupancy: ["보도 폭 점유율", (v) => `${Math.round(v * 100)}% (한도 60%)`],
  road_overlap: ["접지부 주변 차도 비율", (v) => `${Math.round(v * 100)}%`],
  braille_overlap: ["점자블록 비율", (v) => `${Math.round(v * 100)}%`],
  crosswalk_overlap: ["횡단보도 비율", (v) => `${Math.round(v * 100)}%`],
  sidewalk_frac: ["사진 속 보도 면적", (v) => `${Math.round(v * 100)}%`],
  vehicle_count: ["인식된 기기 수", (v) => `${v}대`],
  target_kind: ["대상 기기", (v) => v],
  scale_source: ["실측 기준", (v) => SCALE_LABELS[v] || v],
  elapsed_ms: ["분석 시간", (v) => `${v}ms`],
};
const SCALE_LABELS = { braille_seams: "점자블록 (이음매)", braille_tiles: "점자블록 (블록 단위)", none: "없음 — 비율만 판정" };
const CONF_LABELS = { high: "높음", medium: "보통", low: "낮음" };

async function submitPhoto(blob) {
  show("screen-result");
  $("#analyzing").hidden = false;
  $("#result").hidden = true;
  $("#btn-to-pay").hidden = true;
  const fd = new FormData();
  fd.append("token", state.token);
  fd.append("file", blob, "photo.jpg");
  try {
    const r = await api("/api/return/photo", { method: "POST", body: fd });
    const notice = r.reasons.some((x) => x.status === "INFO");
    const title = { APPROVE: notice ? "✓ 반납 가능 — 안내를 확인해주세요" : "✓ 주차 상태 양호 — 결제를 진행해주세요", REJECT:"✕ 이 위치에는 반납할 수 없어요", RETAKE: "! 다시 촬영해주세요" }[r.status];
    const v = $("#verdict");
    v.className = "verdict " + r.status;
    v.textContent = title;
    $("#overlay").src = "data:image/jpeg;base64," + r.overlay_jpeg;
    const g = $("#grade");
    g.hidden = !r.metrics.grade;
    if (r.metrics.grade) {
      g.innerHTML = `<b class="g-${r.metrics.grade}">${r.metrics.grade}</b><span class="cm">${r.metrics.residual_cm}cm</span>` +
        `<small>남은 통행폭<br>실측 신뢰도 ${CONF_LABELS[r.scale.confidence]} (블록 ${r.scale.n_tiles}장, 오차 ±${r.scale.err_cm}cm)</small>`;
    }
    $("#bev-wrap").hidden = !r.bev_jpeg;
    if (r.bev_jpeg) $("#bev").src = "data:image/jpeg;base64," + r.bev_jpeg;
    $("#reasons").innerHTML = r.reasons.map((x) => `<li class="${x.status}">${x.message}</li>`).join("");
    const m = { ...r.metrics, elapsed_ms: r.elapsed_ms };
    $("#metrics").innerHTML = Object.entries(METRIC_LABELS)
      .filter(([k]) => m[k] !== undefined && !(k.endsWith("_overlap") && m[k] === 0))
      .map(([k, [label, fmt]]) => `<dt>${label}</dt><dd>${fmt(m[k])}</dd>`).join("");
    $("#btn-to-pay").hidden = r.status !== "APPROVE";
    $("#btn-retake").textContent = r.status === "APPROVE" ? "다시 촬영" : "옮긴 뒤 다시 촬영";
  } catch (e) {
    const v = $("#verdict");
    v.className = "verdict RETAKE";
    v.textContent = e.message;
    $("#reasons").innerHTML = "";
    $("#metrics").innerHTML = "";
    $("#overlay").removeAttribute("src");
    $("#grade").hidden = true;
    $("#bev-wrap").hidden = true;
  } finally {
    $("#analyzing").hidden = true;
    $("#result").hidden = false;
  }
}

$("#btn-retake").onclick = () => show("screen-camera");
$("#btn-to-pay").onclick = () => {
  $("#pay-vehicle").textContent = $("#vehicle-id").value;
  $("#pay-zone").textContent = state.zone;
  show("screen-pay");
};

// ---------- ③ 결제 ----------
$("#btn-pay").onclick = async () => {
  const btn = $("#btn-pay");
  btn.disabled = true;
  try {
    const r = await api("/api/return/pay", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token: state.token }),
    });
    $("#done-msg").textContent = `${r.vehicle_id} · ${r.amount.toLocaleString()}원 결제 · 영수증 ${r.receipt_id}`;
    show("screen-done");
  } catch (e) { alert(e.message); } finally { btn.disabled = false; }
};
$("#btn-restart").onclick = () => { state.token = null; setMsg($("#loc-msg"), ""); show("screen-home"); };

// AI 모델 상태
(async function poll() {
  try {
    const h = await api("/api/health");
    if (h.model_loaded) { $("#model-badge").textContent = h.backend; return; }
  } catch {}
  setTimeout(poll, 2000);
})();
