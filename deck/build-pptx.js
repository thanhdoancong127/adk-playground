const pptxgen = require("pptxgenjs");

const pptx = new pptxgen();
pptx.defineLayout({ name: "W", width: 13.333, height: 7.5 });
pptx.layout = "W";
pptx.author = "bos";
pptx.company = "OpenViking knowledge";
pptx.title = "Google ADK for Java — reference";

const NAVY = "1F2A44", TEAL = "1C7293", MINT = "02C39A";
const TINT = "EAF3F5", TINT_LINE = "D5E6EA", INK = "16324A", BODY = "4A5C6B";
const WARM = "FDF3E0", WARM_LINE = "F2E2C4", WARM_INK = "8A5A12", WARM_BODY = "5A4420";

const s = pptx.addSlide();
s.background = { color: "FFFFFF" };

// ---- Left half-bleed panel ----
s.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: 4.6, h: 7.5, fill: { color: NAVY }, line: { color: NAVY } });
s.addText("KHO THAM CHI\u1ebeU \u00b7 OPENVIKING", {
  x: 0.55, y: 0.5, w: 3.7, h: 0.35, fontFace: "Calibri", fontSize: 10, bold: true,
  color: MINT, charSpacing: 2, margin: 0, isTextBox: true,
});
s.addText("Google ADK\ncho Java", {
  x: 0.55, y: 0.92, w: 3.7, h: 1.5, fontFace: "Cambria", fontSize: 31, bold: true,
  color: "FFFFFF", lineSpacingMultiple: 0.96, margin: 0, isTextBox: true,
});
s.addText([
  { text: "Framework m\u00e3 ngu\u1ed3n m\u1edf c\u1ee7a Google \u0111\u1ec3 build \u00b7 evaluate \u00b7 deploy AI agent.", options: { breakLine: true, paraSpaceAfter: 8 } },
  { text: "ADK c\u00f3 nhi\u1ec1u ng\u00f4n ng\u1eef (Python, TS, Go, Java, Kotlin) \u2014 kho n\u00e0y ch\u1ec9 ch\u1ee9a nh\u00e1nh Java (Java 17+; Maven 3.9+/Gradle).", options: { breakLine: true, paraSpaceAfter: 8 } },
  { text: "com.google.adk:google-adk", options: { fontFace: "Courier New", fontSize: 11, color: "9FD3DD" } },
], {
  x: 0.55, y: 2.8, w: 3.7, h: 3.3, fontFace: "Calibri", fontSize: 12.5, color: "CADCFC",
  margin: 0, isTextBox: true, lineSpacingMultiple: 1.06,
});
s.addText(
  "Ngu\u1ed3n: adk.dev \u00b7 github.com/google/adk-java\nSnapshot adk-java 1.10.1 \u00b7 c\u1eadp nh\u1eadt th\u1ee7 c\u00f4ng (watch off)",
  { x: 0.55, y: 6.55, w: 3.7, h: 0.7, fontFace: "Calibri", fontSize: 8.5, color: "8FA3B8", margin: 0, isTextBox: true, lineSpacingMultiple: 1.05 }
);

// ---- Right content ----
s.addText("Kho c\u00f3 g\u00ec", {
  x: 5.05, y: 0.45, w: 8, h: 0.45, fontFace: "Cambria", fontSize: 19, bold: true,
  color: NAVY, margin: 0, isTextBox: true,
});

const cards = [
  ["01", "B\u1eaft \u0111\u1ea7u", "get-started \u00b7 quickstart \u00b7 agents-cli"],
  ["02", "Agent & Model", "LLM/workflow/loop/parallel agents \u00b7 Gemini, Ollama"],
  ["03", "Tools", "function \u00b7 OpenAPI \u00b7 MCP \u00b7 auth \u00b7 confirmation"],
  ["04", "Tr\u1ea1ng th\u00e1i & B\u1ed9 nh\u1edb", "sessions (state/memory) \u00b7 components (callbacks/events/artifacts)"],
  ["05", "Ch\u1ea1y & V\u1eadn h\u00e0nh", "runtime \u00b7 deploy (Cloud Run/GKE) \u00b7 evaluate \u00b7 observability \u00b7 A2A \u00b7 reference"],
];
const x0 = 5.05, y0 = 1.02, cw = 3.72, ch = 1.32, gx = 0.3, gy = 0.22;
cards.forEach((c, i) => {
  const col = i % 2, row = Math.floor(i / 2);
  const x = x0 + col * (cw + gx), y = y0 + row * (ch + gy);
  s.addShape(pptx.ShapeType.roundRect, { x, y, w: cw, h: ch, fill: { color: TINT }, line: { color: TINT_LINE, width: 1 }, rectRadius: 0.07 });
  s.addShape(pptx.ShapeType.ellipse, { x: x + 0.2, y: y + 0.2, w: 0.44, h: 0.44, fill: { color: TEAL } });
  s.addText(c[0], { x: x + 0.2, y: y + 0.2, w: 0.44, h: 0.44, align: "center", valign: "middle", fontFace: "Calibri", fontSize: 11, bold: true, color: "FFFFFF", margin: 0, isTextBox: true });
  s.addText(c[1], { x: x + 0.76, y: y + 0.16, w: cw - 0.92, h: 0.35, fontFace: "Calibri", fontSize: 12.5, bold: true, color: INK, margin: 0, isTextBox: true });
  s.addText(c[2], { x: x + 0.76, y: y + 0.54, w: cw - 0.92, h: 0.68, fontFace: "Calibri", fontSize: 9.5, color: BODY, margin: 0, isTextBox: true, lineSpacingMultiple: 1.0 });
});

// ---- Gotcha panel ----
s.addShape(pptx.ShapeType.roundRect, { x: 5.05, y: 5.62, w: 7.74, h: 1.4, fill: { color: WARM }, line: { color: WARM_LINE, width: 1 }, rectRadius: 0.07 });
s.addText("L\u01b0u \u00fd & c\u00e1ch tra", { x: 5.27, y: 5.74, w: 7.4, h: 0.3, fontFace: "Calibri", fontSize: 11, bold: true, color: WARM_INK, margin: 0, isTextBox: true });
s.addText([
  { text: "\u25aa Docs l\u1ec7ch version: quickstart ghim 1.6.0, README GitHub 1.9.0, kho ch\u1ee5p 1.10.1 \u2014 \u0111\u1eebng copy m\u00f9.", options: { breakLine: true, paraSpaceAfter: 3 } },
  { text: "\u25aa ADK 2.0 m\u1edbi ch\u1ec9 Python/Go \u2192 Java v\u1eabn d\u00f9ng template workflows; ADK Web ch\u1ec9 \u0111\u1ec3 dev/debug.", options: { breakLine: true, paraSpaceAfter: 3 } },
  { text: "\u25aa Tra: search (target_uri) / grep (uri), KH\u00d4NG \u0111o\u00e1n path; l\u1ecdc snippet b\u1eb1ng 'java'.", options: {} },
], { x: 5.27, y: 6.04, w: 7.4, h: 0.95, fontFace: "Calibri", fontSize: 9.5, color: WARM_BODY, margin: 0, isTextBox: true, lineSpacingMultiple: 1.02 });

s.addNotes(
  "Google ADK for Java \u2014 kho tham chi\u1ebfu OpenViking.\n" +
  "\u2022 ADK l\u00e0 framework m\u00e3 ngu\u1ed3n m\u1edf c\u1ee7a Google \u0111\u1ec3 build/evaluate/deploy agent; c\u00f3 b\u1ea3n Python/TS/Go/Java/Kotlin. Kho n\u00e0y ch\u1ec9 mirror nh\u00e1nh Java (adk.dev + google/adk-java), snapshot adk-java 1.10.1; c\u1eadp nh\u1eadt th\u1ee7 c\u00f4ng, watch OFF.\n" +
  "\u2022 Version drift: quickstart adk.dev ghim 1.6.0, README GitHub 1.9.0, kho ch\u1ee5p 1.10.1 \u2014 ng\u01b0\u1eddi m\u1edbi d\u1ec5 copy nh\u1ea7m.\n" +
  "\u2022 Gotcha Gemini: ADK Java <=0.3.0 kh\u00f4ng h\u1ee3p Gemini 3 Pro Preview; d\u00f2ng 1.x \u0111\u00e3 x\u1eed l\u00fd \u0111\u01b0\u1ee3c (n\u00ean d\u00f9ng ADK 1.x).\n" +
  "\u2022 ADK 2.0 hi\u1ec7n ch\u1ec9 Python/Go \u2192 Java v\u1eabn d\u00f9ng template workflows (Sequential/Parallel/Loop). ADK Web ch\u1ec9 dev/debug.\n" +
  "\u2022 Tra c\u1ee9u: m\u1ed7i trang ch\u1ee9a nhi\u1ec1u ng\u00f4n ng\u1eef \u2014 grep 'java'; t\u00ean file do parser sinh n\u00ean d\u00f9ng search/grep, \u0111\u1eebng \u0111o\u00e1n path. V\u00ed d\u1ee5: 'FunctionTool java' trong target_uri=.../google-adk."
);

pptx.writeFile({ fileName: "/tmp/opencode/pptx-build/google-adk-for-java.pptx" }).then((f) => console.log("WROTE", f));
