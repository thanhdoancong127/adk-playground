// Deck builder: emits a real .pptx (pptxgenjs) AND an HTML preview from the SAME
// layout spec, so overflow/overlap QA reflects the deck geometry.
//   node deck.js pptx   -> google-adk.pptx
//   node deck.js html   -> preview.html
const fs = require("fs");
const pptxgen = require("pptxgenjs");

const MODE = process.argv[2] || "pptx";

// ---- design tokens ----
const NAVY = "1F2A44", NAVY2 = "27334F", TEAL = "1C7293", MINT = "02C39A";
const ICE = "CADCFC", MUTED = "8FA3B8", INK = "16324A", BODY = "4A5C6B";
const TINT = "EAF3F5", LINE = "D5E6EA", WARM = "FDF3E0", WARML = "F2E2C4", WARMK = "8A5A12";
const F_HEAD = "Cambria", F_BODY = "Calibri", F_MONO = "Courier New";
const W = 13.333, H = 7.5;

// ---- backends ----
let pptx, htmlSlides, S;
function newPptxBackend() {
  pptx = new pptxgen();
  pptx.defineLayout({ name: "W", width: W, height: H });
  pptx.layout = "W";
  pptx.author = "bos"; pptx.company = "OpenViking knowledge";
  pptx.title = "Google ADK for Java";
}
function newHtmlBackend() { htmlSlides = []; }
function px(inch) { return inch * 96; }

function makePptxSlide() {
  const slide = pptx.addSlide();
  return {
    bg(color) { slide.background = { color }; },
    rect(x, y, w, h, o = {}) {
      slide.addShape(o.round ? pptx.ShapeType.roundRect : pptx.ShapeType.rect, {
        x, y, w, h, fill: { color: o.fill, transparency: o.tr || 0 },
        line: o.line ? { color: o.line, width: o.lw || 1 } : { color: o.fill, width: 0 },
        ...(o.round ? { rectRadius: o.r || 0.07 } : {}),
      });
    },
    ellipse(x, y, w, h, o = {}) {
      slide.addShape(pptx.ShapeType.ellipse, { x, y, w, h, fill: { color: o.fill, transparency: o.tr || 0 }, line: { color: o.fill, width: 0 } });
    },
    text(x, y, w, h, paras, o = {}) {
      const arr = paras.map((p, i) => ({
        text: p.t,
        options: {
          fontFace: p.face || o.face || F_BODY, fontSize: p.size || o.size || 14,
          color: p.color || o.color || BODY, bold: !!p.bold, italic: !!p.italic,
          breakLine: true, paraSpaceAfter: p.spaceAfter != null ? p.spaceAfter : (o.spaceAfter || 0),
        },
      }));
      slide.addText(arr, {
        x, y, w, h, align: o.align || "left", valign: o.valign || "top",
        margin: o.margin != null ? o.margin : 0, isTextBox: true,
        lineSpacingMultiple: o.lh || 1.0,
        ...(o.spacing ? { charSpacing: o.spacing } : {}),
      });
    },
    notes(s) { slide.addNotes(s); },
  };
}
function makeHtmlSlide() {
  const kids = [];
  return {
    bg(color) { this._bg = color; },
    rect(x, y, w, h, o = {}) {
      kids.push(`<div class="s" style="left:${px(x)}px;top:${px(y)}px;width:${px(w)}px;height:${px(h)}px;background:#${o.fill};${o.tr ? `opacity:${1 - o.tr / 100};` : ""}${o.round ? `border-radius:${(o.r || 0.07) * 96}px;` : ""}${o.line && o.lw !== 0 ? `border:${o.lw || 1}px solid #${o.line};` : ""}"></div>`);
    },
    ellipse(x, y, w, h, o = {}) {
      kids.push(`<div class="s" style="left:${px(x)}px;top:${px(y)}px;width:${px(w)}px;height:${px(h)}px;background:#${o.fill};border-radius:50%;${o.tr ? `opacity:${1 - o.tr / 100};` : ""}"></div>`);
    },
    text(x, y, w, h, paras, o = {}) {
      const flex = o.valign === "middle" ? "display:flex;align-items:center;" : "";
      const inner = paras.map((p) => {
        const st = `font-family:${p.face || o.face || F_BODY};font-size:${((p.size || o.size || 14) * 4) / 3}px;color:#${p.color || o.color || BODY};${p.bold ? "font-weight:700;" : ""}${p.italic ? "font-style:italic;" : ""}${(p.spaceAfter != null ? p.spaceAfter : o.spaceAfter || 0) ? `margin-bottom:${((p.spaceAfter != null ? p.spaceAfter : o.spaceAfter) * 4) / 3}px;` : ""}`;
        return `<div style="${st}">${p.t}</div>`;
      }).join("");
      kids.push(`<div class="s t" style="left:${px(x)}px;top:${px(y)}px;width:${px(w)}px;height:${px(h)}px;text-align:${o.align || "left"};line-height:${o.lh || 1.0};${o.spacing ? `letter-spacing:${o.spacing * (4 / 3)}px;` : ""}${flex}"><div style="width:100%">${inner}</div></div>`);
    },
    notes() {},
  };
  // store per-slide kids via closure: return slide with push captured
  // (we push into a global by replacing backends per slide)
}
function slidePptx(bg) { const s = makePptxSlide(); if (bg) s.bg(bg); return s; }
function slideHtml() {
  const s = makeHtmlSlide();
  const commit = { s, kids: s._kids || [] };
  return s;
}

// Because HTML slide needs per-slide container, rebuild simply: we collect ops per slide.
// Switch to an approach: each slide creates a fresh op list.
function mkSlide(kind) {
  if (kind === "pptx") { const sl = pptx.addSlide(); return { ops: [], slide: sl }; }
  return { ops: [] };
}
function opPptx(slide, fn) {
  const api = makePptxSlideWith(slide);
  fn(api);
}
function makePptxSlideWith(slide) {
  return {
    bg(color) { slide.background = { color }; },
    rect(x, y, w, h, o = {}) {
      slide.addShape(o.round ? pptx.ShapeType.roundRect : pptx.ShapeType.rect, {
        x, y, w, h, fill: { color: o.fill, transparency: o.tr || 0 },
        line: o.line ? { color: o.line, width: o.lw || 1 } : { color: o.fill, width: 0 },
        ...(o.round ? { rectRadius: o.r || 0.07 } : {}),
      });
    },
    ellipse(x, y, w, h, o = {}) {
      slide.addShape(pptx.ShapeType.ellipse, { x, y, w, h, fill: { color: o.fill, transparency: o.tr || 0 } });
    },
    text(x, y, w, h, paras, o = {}) {
      const arr = paras.map((p) => ({
        text: p.t,
        options: { fontFace: p.face || o.face || F_BODY, fontSize: p.size || o.size || 14, color: p.color || o.color || BODY, bold: !!p.bold, italic: !!p.italic, breakLine: true, paraSpaceAfter: p.spaceAfter != null ? p.spaceAfter : (o.spaceAfter || 0) },
      }));
      slide.addText(arr, { x, y, w, h, align: o.align || "left", valign: o.valign || "top", margin: o.margin != null ? o.margin : 0, isTextBox: true, lineSpacingMultiple: o.lh || 1.0, ...(o.spacing ? { charSpacing: o.spacing } : {}) });
    },
    notes(s2) { slide.addNotes(s2); },
  };
}
function opHtml(slide, fn) {
  const kids = [];
  fn({
    bg(color) { slide.bg = color; },
    rect(x, y, w, h, o = {}) { kids.push(`<div class="s" style="left:${px(x)}px;top:${px(y)}px;width:${px(w)}px;height:${px(h)}px;background:#${o.fill};${o.tr ? `opacity:${1 - o.tr / 100};` : ""}${o.round ? `border-radius:${(o.r || 0.07) * 96}px;` : ""}${o.line && o.lw !== 0 ? `border:${o.lw || 1}px solid #${o.line};` : ""}"></div>`); },
    ellipse(x, y, w, h, o = {}) { kids.push(`<div class="s" style="left:${px(x)}px;top:${px(y)}px;width:${px(w)}px;height:${px(h)}px;background:#${o.fill};border-radius:50%;${o.tr ? `opacity:${1 - o.tr / 100};` : ""}"></div>`); },
    text(x, y, w, h, paras, o = {}) {
      const inner = paras.map((p) => {
        const st = `font-family:${p.face || o.face || F_BODY};font-size:${((p.size || o.size || 14) * 4) / 3}px;color:#${p.color || o.color || BODY};${p.bold ? "font-weight:700;" : ""}${p.italic ? "font-style:italic;" : ""}${(p.spaceAfter != null ? p.spaceAfter : o.spaceAfter || 0) ? `margin-bottom:${((p.spaceAfter != null ? p.spaceAfter : o.spaceAfter) * 4) / 3}px;` : ""}`;
        return `<div style="${st}">${p.t}</div>`;
      }).join("");
      kids.push(`<div class="s t" style="left:${px(x)}px;top:${px(y)}px;width:${px(w)}px;height:${px(h)}px;text-align:${o.align || "left"};line-height:${o.lh || 1.0};${o.valign === "middle" ? "display:flex;align-items:center;" : ""}${o.spacing ? `letter-spacing:${o.spacing * (4 / 3)}px;` : ""}"><div style="width:100%">${inner}</div></div>`);
    },
    notes() {},
  });
  slide.kids = kids;
}

// ---- shared slide builders (defined once, run on either backend) ----
function badge(S, x, y, d, n, fill) {
  S.ellipse(x, y, d, d, { fill: fill || TEAL });
  S.text(x, y, d, d, [{ t: n, color: "FFFFFF", bold: true, size: 12, face: F_BODY }], { align: "center", valign: "middle", lh: 1 });
}

const builders = [];
function slide(fn) { builders.push(fn); }

// ===== Slide 1 — Cover =====
slide((S) => {
  S.bg(NAVY);
  S.ellipse(9.8, -1.8, 5.6, 5.6, { fill: MINT, tr: 90 });
  S.ellipse(11.4, 3.4, 3.4, 3.4, { fill: TEAL, tr: 80 });
  S.ellipse(8.9, 5.5, 2.2, 2.2, { fill: MINT, tr: 86 });
  S.text(0.95, 1.15, 9, 0.4, [{ t: "OPENVIKING KNOWLEDGE  ·  AGENT FRAMEWORKS", color: MINT, bold: true, size: 12 }], { spacing: 3, lh: 1 });
  S.text(0.9, 1.65, 9.5, 2.5, [{ t: "Google ADK", color: "FFFFFF", bold: true, size: 60, face: F_HEAD, spaceAfter: 6 }, { t: "Agent Development Kit — cho Java", color: ICE, size: 23, face: F_HEAD }], { lh: 0.98 });
  S.text(0.95, 4.55, 9.5, 0.5, [{ t: "Giới thiệu framework & kho tài liệu tham chiếu", color: MINT, size: 15 }], { lh: 1 });
  S.text(0.95, 6.65, 11.4, 0.5, [{ t: "Nguồn: adk.dev  ·  github.com/google/adk-java  ·  snapshot adk-java 1.10.1  ·  2026-09-30", color: MUTED, size: 10 }], { lh: 1 });
  S.notes("Google ADK (Agent Development Kit) là framework mã nguồn mở của Google. Bản trong kho là nhánh Java, snapshot adk-java 1.10.1, cập nhật thủ công.");
});

// ===== Slide 2 — What is ADK =====
slide((S) => {
  S.bg("FFFFFF");
  S.text(0.6, 0.5, 12.1, 0.7, [{ t: "Google ADK là gì?", color: INK, bold: true, size: 30, face: F_HEAD }], { lh: 1 });
  S.text(0.6, 1.5, 6.7, 4.5, [
    { t: "Framework mã nguồn mở của Google để xây dựng, đánh giá và triển khai AI agent — theo hướng code-first.", size: 15, color: BODY, spaceAfter: 14 },
    { t: "Hỗ trợ nhiều ngôn ngữ: Python, TypeScript, Go, Java, Kotlin. Kho tham chiếu này chỉ gồm nhánh Java.", size: 15, color: BODY, spaceAfter: 14 },
    { t: "Mỗi agent được ghép từ các khối rời: model, tools, session/state và runner — mở rộng được bằng callback.", size: 15, color: BODY, spaceAfter: 14 },
    { t: "Không phải SDK đóng: bạn viết code, ADK lo phần chạy agent, phiên làm việc và triển khai.", size: 15, color: BODY },
  ], { lh: 1.08 });
  S.rect(7.6, 1.5, 5.13, 4.5, { fill: TINT, line: LINE, round: true, r: 0.09 });
  S.text(7.95, 1.75, 4.4, 0.4, [{ t: "Thông tin nhanh", color: INK, bold: true, size: 15, face: F_HEAD }], { lh: 1 });
  const rows = [
    ["Ngôn ngữ", "Python · TS · Go · Java · Kotlin"],
    ["Bản trong kho", "adk-java 1.10.1 (chỉ Java)"],
    ["Artifact", "com.google.adk:google-adk"],
    ["Yêu cầu", "Java 17+  ·  Maven 3.9+ / Gradle"],
  ];
  let ry = 2.35;
  rows.forEach(([k, v]) => {
    S.text(7.95, ry, 4.4, 0.3, [{ t: k, color: TEAL, bold: true, size: 11 }], { lh: 1 });
    S.text(7.95, ry + 0.3, 4.4, 0.4, [{ t: v, color: INK, size: 13.5, face: k === "Artifact" ? F_MONO : F_BODY }], { lh: 1 });
    ry += 0.86;
  });
  S.text(0.6, 6.55, 12.1, 0.4, [{ t: "Tóm lại: ADK = bộ công cụ build · evaluate · deploy agent, code-first, đa ngôn ngữ.", color: BODY, italic: true, size: 12.5 }], { lh: 1 });
  S.notes("ADK là framework code-first đa ngôn ngữ; kho chỉ chứa nhánh Java (adk-java). Artifact Maven: com.google.adk:google-adk; cần Java 17+ và Maven 3.9+/Gradle.");
});

// ===== Slide 3 — Building blocks =====
slide((S) => {
  S.bg("FFFFFF");
  S.text(0.6, 0.45, 12.1, 0.7, [{ t: "Các khối cấu trúc chính", color: INK, bold: true, size: 30, face: F_HEAD }], { lh: 1 });
  const items = [
    ["1", "Agent", "LLM agent & workflow agents (Sequential · Parallel · Loop)."],
    ["2", "Tools", "Function tools, OpenAPI, MCP, authentication, confirmation."],
    ["3", "Session & State", "Session, State (ngắn hạn) và Memory (dài hạn)."],
    ["4", "Components", "Callbacks, Events, Artifacts, Apps, Plugins."],
    ["5", "Runtime", "Runner, RunConfig, event loop, Web UI (chỉ dev/debug)."],
  ];
  let y = 1.35;
  items.forEach(([n, h, d]) => {
    S.rect(0.6, y, 12.13, 0.9, { fill: TINT, line: LINE, round: true, r: 0.08 });
    badge(S, 0.82, y + 0.23, 0.44, n);
    S.text(1.5, y, 3.0, 0.9, [{ t: h, color: INK, bold: true, size: 15, face: F_HEAD }], { valign: "middle", lh: 1 });
    S.text(4.4, y, 8.1, 0.9, [{ t: d, color: BODY, size: 13.5 }], { valign: "middle", lh: 1 });
    y += 1.04;
  });
  S.notes("Năm khối: Agent (LLM + workflow), Tools, Session/State, Components (callback/event/artifact), Runtime (Runner/RunConfig/Web UI).");
});

// ===== Slide 4 — Lifecycle =====
slide((S) => {
  S.bg("FFFFFF");
  S.text(0.6, 0.5, 12.1, 0.7, [{ t: "Vòng đời: Build → Evaluate → Deploy", color: INK, bold: true, size: 30, face: F_HEAD }], { lh: 1 });
  const steps = [
    ["1", "Build", ["Định nghĩa agent, tools, session.", "Chạy thử local với Runner."]],
    ["2", "Evaluate", ["adk eval với test case.", "Đo chất lượng trước khi ship."]],
    ["3", "Deploy", ["Cloud Run · GKE · Agent Runtime.", "Kèm observability (logs/traces)."]],
  ];
  const cw = 3.68, gap = 0.55, x0 = 0.6, y = 1.55, ch = 2.55;
  steps.forEach(([n, h, lines], i) => {
    const x = x0 + i * (cw + gap);
    S.rect(x, y, cw, ch, { fill: TINT, line: LINE, round: true, r: 0.09 });
    badge(S, x + 0.3, y + 0.3, 0.5, n);
    S.text(x + 0.3, y + 0.95, cw - 0.6, 0.5, [{ t: h, color: INK, bold: true, size: 19, face: F_HEAD }], { lh: 1 });
    S.text(x + 0.3, y + 1.5, cw - 0.6, 0.9, lines.map((l) => ({ t: "• " + l, color: BODY, size: 12.5, spaceAfter: 5 })), { lh: 1.05 });
    if (i < 2) {
      S.ellipse(x + cw + 0.07, y + ch / 2 - 0.2, 0.4, 0.4, { fill: TEAL });
      S.text(x + cw + 0.07, y + ch / 2 - 0.2, 0.4, 0.4, [{ t: "→", color: "FFFFFF", bold: true, size: 14 }], { align: "center", valign: "middle", lh: 1 });
    }
  });
  S.rect(0.6, 4.5, 12.13, 1.35, { fill: WARM, line: WARML, round: true, r: 0.08 });
  S.text(0.9, 4.68, 11.6, 0.4, [{ t: "Lưu ý khi vận hành", color: WARMK, bold: true, size: 13 }], { lh: 1 });
  S.text(0.9, 5.06, 11.6, 0.7, [
    { t: "• ADK Web (AdkWebServer) chỉ dùng cho dev/debug — KHÔNG dùng production.", color: BODY, size: 12.5, spaceAfter: 4 },
    { t: "• Đích deploy gồm Cloud Run, GKE và Agent Runtime; bật observability ngay từ đầu.", color: BODY, size: 12.5 },
  ], { lh: 1.05 });
  S.notes("Ba bước: Build (local) → Evaluate (adk eval) → Deploy (Cloud Run/GKE/Agent Runtime) + observability. ADK Web chỉ để dev/debug.");
});

// ===== Slide 5 — Reference repo & lookup =====
slide((S) => {
  S.bg("FFFFFF");
  S.text(0.6, 0.5, 12.1, 0.7, [{ t: "Kho tham chiếu có gì & tra thế nào", color: INK, bold: true, size: 30, face: F_HEAD }], { lh: 1 });
  S.text(0.6, 1.35, 6.4, 0.4, [{ t: "9 nhóm tài liệu", color: TEAL, bold: true, size: 13 }], { lh: 1 });
  const groups = ["get-started", "agents", "tools", "sessions", "components", "runtime", "ops", "interop", "reference"];
  const gx0 = 0.6, gw = 2.0, gh = 0.62, ggx = 0.2, ggy = 0.2, cols = 3;
  groups.forEach((g, i) => {
    const c = i % cols, r = Math.floor(i / cols);
    const x = gx0 + c * (gw + ggx), yy = 1.85 + r * (gh + ggy);
    S.rect(x, yy, gw, gh, { fill: TINT, line: LINE, round: true, r: 0.1 });
    S.text(x, yy, gw, gh, [{ t: g, color: INK, bold: true, size: 12.5, face: F_MONO }], { align: "center", valign: "middle", lh: 1 });
  });
  S.rect(7.35, 1.35, 5.38, 4.5, { fill: NAVY, round: true, r: 0.09 });
  S.text(7.7, 1.6, 4.7, 0.4, [{ t: "Cách tra cứu", color: MINT, bold: true, size: 15, face: F_HEAD }], { lh: 1 });
  S.text(7.7, 2.15, 4.7, 3.5, [
    { t: "• search với target_uri = …/google-adk", color: ICE, size: 13, spaceAfter: 10 },
    { t: "• grep với uri để tìm chuỗi chính xác", color: ICE, size: 13, spaceAfter: 10 },
    { t: "• KHÔNG đoán đường dẫn — tên file do parser sinh", color: ICE, size: 13, spaceAfter: 10 },
    { t: "• Mỗi trang nhiều ngôn ngữ → lọc snippet bằng 'java'", color: ICE, size: 13, spaceAfter: 10 },
    { t: "Ví dụ: \"FunctionTool java\" trong target_uri …/google-adk", color: MINT, italic: true, size: 12.5 },
  ], { lh: 1.05 });
  S.text(0.6, 6.6, 12.1, 0.4, [{ t: "Nguồn: adk.dev · google/adk-java · snapshot adk-java 1.10.1 · cập nhật thủ công (watch off).", color: MUTED, size: 10.5 }], { lh: 1 });
  S.notes("Kho gồm 9 nhóm: get-started, agents, tools, sessions, components, runtime, ops, interop, reference. Tra bằng search(target_uri)/grep(uri), không đoán path, lọc snippet bằng 'java'.");
});

// ===== Slide 6 — Main features =====
slide((S) => {
  S.bg("FFFFFF");
  S.text(0.6, 0.45, 12.1, 0.7, [{ t: "Tính năng chính", color: INK, bold: true, size: 30, face: F_HEAD }], { lh: 1 });
  const feats = [
    ["1", "Multi-agent", "Workflow agents (Sequential · Parallel · Loop) + LLM tự định tuyến sub-agent."],
    ["2", "Tools phong phú", "Function · OpenAPI · MCP · tool built-in (Google Search, code exec)."],
    ["3", "Model-agnostic", "Gemini (Vertex AI), Ollama chạy local, và provider khác."],
    ["4", "Session & Memory", "State, events, artifacts; bộ nhớ dài hạn; session service cắm được."],
    ["5", "Streaming & Multimodal", "Live/voice (bidi streaming), đầu vào ảnh/âm thanh."],
    ["6", "Đánh giá & Observability", "adk eval + test case; logs/traces/metrics; Dev UI để debug."],
  ];
  const cw = 3.844, ch = 2.15, gx = 0.3, gy = 0.25, x0 = 0.6, y0 = 1.35;
  feats.forEach(([n, h, d], i) => {
    const c = i % 3, r = Math.floor(i / 3);
    const x = x0 + c * (cw + gx), y = y0 + r * (ch + gy);
    S.rect(x, y, cw, ch, { fill: TINT, line: LINE, round: true, r: 0.09 });
    badge(S, x + 0.28, y + 0.28, 0.46, n);
    S.text(x + 0.28, y + 0.92, cw - 0.56, 0.5, [{ t: h, color: INK, bold: true, size: 14.5, face: F_HEAD }], { lh: 1 });
    S.text(x + 0.28, y + 1.45, cw - 0.56, 0.62, [{ t: d, color: BODY, size: 11.5 }], { lh: 1.05 });
  });
  S.text(0.6, 6.35, 12.1, 0.4, [{ t: "Đa ngôn ngữ (Python · TS · Go · Java · Kotlin) và hỗ trợ A2A để phối hợp agent từ xa.", color: BODY, italic: true, size: 12 }], { lh: 1 });
  S.notes("6 tính năng chính: multi-agent, tools phong phú, model-agnostic, session/memory, streaming+multimodal, eval+observability.");
});

// ===== Slide 7 — Applications =====
slide((S) => {
  S.bg("FFFFFF");
  S.text(0.6, 0.45, 12.1, 0.7, [{ t: "Ứng dụng thực tế", color: INK, bold: true, size: 30, face: F_HEAD }], { lh: 1 });
  const apps = [
    ["Trợ lý khách hàng", "CSKH, FAQ, xử lý ticket tự động."],
    ["Multi-agent research", "Nhiều agent phối hợp nghiên cứu & tổng hợp."],
    ["Voice / live agent", "Trợ lý giọng nói thời gian thực."],
    ["Tự động hoá workflow", "Quy trình doanh nghiệp, tích hợp hệ thống."],
    ["Agent dữ liệu", "Truy vấn BigQuery/analytics, tạo báo cáo."],
    ["RAG / trợ lý tài liệu", "Tìm & trả lời trên kho tài liệu nội bộ."],
  ];
  const cw = 3.844, ch = 1.55, gx = 0.3, gy = 0.25, x0 = 0.6, y0 = 1.45;
  apps.forEach(([h, d], i) => {
    const c = i % 3, r = Math.floor(i / 3);
    const x = x0 + c * (cw + gx), y = y0 + r * (ch + gy);
    S.rect(x, y, cw, ch, { fill: TINT, line: LINE, round: true, r: 0.09 });
    S.rect(x + 0.28, y + 0.28, 0.5, 0.5, { fill: TEAL, round: true, r: 0.12 });
    S.text(x + 0.28, y + 0.28, 0.5, 0.5, [{ t: "\u25c6", color: "FFFFFF", size: 12 }], { align: "center", valign: "middle", lh: 1 });
    S.text(x + 0.95, y + 0.3, cw - 1.2, 0.5, [{ t: h, color: INK, bold: true, size: 14, face: F_HEAD }], { lh: 1 });
    S.text(x + 0.28, y + 0.92, cw - 0.56, 0.5, [{ t: d, color: BODY, size: 11.5 }], { lh: 1.05 });
  });
  S.rect(0.6, 4.85, 12.13, 0.9, { fill: NAVY, round: true, r: 0.08 });
  S.text(0.9, 4.85, 11.6, 0.9, [{ t: "Điểm mạnh chung: mô tả agent bằng code → dễ versioning, test và tái sử dụng giữa các ứng dụng.", color: ICE, size: 13 }], { valign: "middle", lh: 1.05 });
  S.notes("Ứng dụng tiêu biểu: CSKH, research đa agent, voice/live, tự động hoá quy trình, agent dữ liệu, RAG tài liệu.");
});

// ===== Slide 8 — Closing / gotchas =====
slide((S) => {
  S.bg(NAVY);
  S.text(0.6, 0.55, 12.1, 0.7, [{ t: "Lưu ý & tra cứu nhanh", color: "FFFFFF", bold: true, size: 30, face: F_HEAD }], { lh: 1 });
  S.text(0.6, 1.2, 12.1, 0.4, [{ t: "Ba điểm dễ sai khi dùng tài liệu ADK", color: MINT, size: 13.5 }], { lh: 1 });
  const warns = [
    ["Version drift", "Quickstart adk.dev ghim 1.6.0, README GitHub 1.9.0, kho chụp 1.10.1 — đừng copy mù, đối chiếu version."],
    ["Gemini & phiên bản", "ADK Java 1.x dùng được Gemini 3 Pro Preview; bản ≤ 0.3.0 thì không. Kho đang là dòng 1.x."],
    ["ADK 2.0 chưa cho Java", "2.0 mới chỉ Python/Go → Java vẫn dùng template workflows; ADK Web chỉ để dev."],
  ];
  const cw = 3.88, gap = 0.25, x0 = 0.6, y = 1.85, ch = 2.5;
  warns.forEach(([h, d], i) => {
    const x = x0 + i * (cw + gap);
    S.rect(x, y, cw, ch, { fill: NAVY2, round: true, r: 0.09 });
    S.text(x + 0.3, y + 0.3, cw - 0.6, 0.5, [{ t: h, color: MINT, bold: true, size: 15, face: F_HEAD }], { lh: 1 });
    S.text(x + 0.3, y + 0.95, cw - 0.6, 1.3, [{ t: d, color: ICE, size: 12.5 }], { lh: 1.08 });
  });
  S.text(0.6, 4.85, 12.1, 0.6, [{ t: "Tra cứu: search / grep theo target_uri = viking://resources/knowledge/agent-frameworks/google-adk", color: "FFFFFF", size: 13.5 }], { lh: 1.1 });
  S.text(0.6, 6.6, 12.1, 0.4, [{ t: "Kết thúc  ·  Google ADK for Java — kho tham chiếu OpenViking", color: MUTED, size: 10.5 }], { lh: 1 });
  S.notes("Ba gotcha: version drift (1.6.0/1.9.0/1.10.1), Gemini 3 chỉ với ADK 1.x, ADK 2.0 chưa hỗ trợ Java (vẫn template workflows).");
});

// ---- run ----
if (MODE === "pptx") {
  newPptxBackend();
  builders.forEach((fn) => { const sl = pptx.addSlide(); fn(makePptxSlideWith(sl)); });
  pptx.writeFile({ fileName: "google-adk.pptx" }).then((f) => console.log("WROTE", f));
} else {
  const slides = builders.map((fn) => { const sl = {}; opHtml(sl, fn); return sl; });
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{background:#888;font-family:Calibri,Carlito,Arial,sans-serif}
  .slide{position:relative;width:1280px;height:720px;background:#fff;margin:0 auto 24px;overflow:hidden}
  .s{position:absolute;overflow:hidden}
  .t{white-space:pre-wrap}
  </style></head><body>` +
    slides.map((s) => `<div class="slide" style="background:#${s.bg}">${s.kids.join("")}</div>`).join("") +
    `</body></html>`;
  fs.writeFileSync("preview.html", html);
  console.log("WROTE preview.html", slides.length, "slides");
}
