(function () {
  "use strict";
  var data = JSON.parse(document.getElementById("chart-data").textContent);
  var NS = "http://www.w3.org/2000/svg";

  function fmt(kind, v) {
    if (v == null || isNaN(v)) return "–";
    switch (kind) {
      case "bytes": case "rate": {
        var u = ["B", "KiB", "MiB", "GiB", "TiB"], i = 0, a = Math.abs(v);
        while (a >= 1024 && i < u.length - 1) { a /= 1024; i++; }
        return (v < 0 ? "-" : "") + (i === 0 ? a.toFixed(0) : a.toFixed(a >= 100 ? 0 : 1)) + " " + u[i] + (kind === "rate" ? "/s" : "");
      }
      case "pct": return v.toFixed(v >= 10 ? 0 : 1) + "%";
      case "ms": return v >= 1000 ? (v / 1000).toFixed(1) + " s" : v.toFixed(v >= 10 ? 0 : 1) + " ms";
      default: return Math.round(v).toLocaleString("en-US") + (kind === "persec" ? "/s" : "");
    }
  }
  function fmtTime(ms) {
    var s = Math.round(ms / 1000), m = Math.floor(s / 60), h = Math.floor(m / 60);
    s = s % 60; m = m % 60;
    var p = function (n) { return (n < 10 ? "0" : "") + n; };
    return h > 0 ? h + ":" + p(m) + ":" + p(s) : m + ":" + p(s);
  }
  function niceMax(v) {
    if (v <= 0) return 1;
    var e = Math.pow(10, Math.floor(Math.log10(v))), f = v / e;
    return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * e;
  }
  function el(name, attrs, parent) {
    var n = document.createElementNS(NS, name);
    for (var k in attrs) {
      var v = attrs[k];
      // CSS custom properties are reliable in style, not in presentation attributes.
      if ((k === "fill" || k === "stroke") && String(v).indexOf("var(") === 0) n.style[k] = v;
      else n.setAttribute(k, v);
    }
    if (parent) parent.appendChild(n);
    return n;
  }

  function draw(host) {
    var cfg = data[host.getAttribute("data-chart")];
    var svgHost = host.querySelector(".plot");
    svgHost.innerHTML = "";
    var W = Math.max(280, svgHost.clientWidth), H = 240, L = 64, R = 16, T = 12, B = 28;
    var svg = el("svg", { viewBox: "0 0 " + W + " " + H, role: "img", "aria-label": cfg.title }, svgHost);
    var xMax = Math.max(1, cfg.xMax);
    var yMax = cfg.yFixedMax || 0;
    if (!yMax) {
      cfg.series.forEach(function (s) { s.points.forEach(function (p) { if (p[1] > yMax) yMax = p[1]; }); });
      (cfg.refs || []).forEach(function (r) { if (r.y > yMax) yMax = r.y; });
      yMax = niceMax(yMax * 1.05);
    }
    var x = function (v) { return L + (W - L - R) * v / xMax; };
    var y = function (v) { return T + (H - T - B) * (1 - v / yMax); };
    for (var i = 0; i <= 4; i++) {
      var gv = yMax * i / 4, gy = y(gv);
      el("line", { x1: L, x2: W - R, y1: gy, y2: gy, stroke: i === 0 ? "var(--axis)" : "var(--grid)", "stroke-width": 1 }, svg);
      var t = el("text", { x: L - 8, y: gy + 4, "text-anchor": "end", fill: "var(--muted)", "font-size": 11 }, svg);
      t.textContent = fmt(cfg.unit, gv);
    }
    var ticks = Math.min(6, Math.max(2, Math.floor((W - L - R) / 110)));
    for (var j = 0; j <= ticks; j++) {
      var tv = xMax * j / ticks;
      var tx = el("text", { x: x(tv), y: H - 8, "text-anchor": j === 0 ? "start" : j === ticks ? "end" : "middle", fill: "var(--muted)", "font-size": 11 }, svg);
      tx.textContent = fmtTime(tv);
    }
    (cfg.refs || []).forEach(function (r) {
      el("line", { x1: L, x2: W - R, y1: y(r.y), y2: y(r.y), stroke: "var(--ink-2)", "stroke-width": 1, opacity: 0.6 }, svg);
      var rt = el("text", { x: W - R - 4, y: y(r.y) - 4, "text-anchor": "end", fill: "var(--ink-2)", "font-size": 11 }, svg);
      rt.textContent = r.label;
    });
    cfg.series.forEach(function (s) {
      if (!s.points.length) return;
      if (s.kind === "dots") {
        s.points.forEach(function (p) {
          el("circle", { cx: x(p[0]), cy: y(p[1]), r: 4, fill: s.color, stroke: "var(--surface)", "stroke-width": 2 }, svg);
        });
        return;
      }
      var d = s.points.map(function (p, k) { return (k ? "L" : "M") + x(p[0]).toFixed(1) + " " + y(p[1]).toFixed(1); }).join(" ");
      if (s.kind === "area") {
        var first = s.points[0], last = s.points[s.points.length - 1];
        el("path", { d: d + " L" + x(last[0]).toFixed(1) + " " + y(0) + " L" + x(first[0]).toFixed(1) + " " + y(0) + " Z", fill: s.wash || "var(--s1-wash)", stroke: "none" }, svg);
      }
      el("path", { d: d, fill: "none", stroke: s.color, "stroke-width": 2, "stroke-linejoin": "round", "stroke-linecap": "round" }, svg);
    });
    var cross = el("line", { y1: T, y2: H - B, stroke: "var(--axis)", "stroke-width": 1, visibility: "hidden" }, svg);
    var hit = el("rect", { x: L, y: T, width: W - L - R, height: H - T - B, fill: "transparent" }, svg);
    var tip = host.querySelector(".tip");
    function nearest(points, xv) {
      var best = null, bd = Infinity;
      for (var k = 0; k < points.length; k++) {
        var dd = Math.abs(points[k][0] - xv);
        if (dd < bd) { bd = dd; best = points[k]; }
      }
      return best;
    }
    hit.addEventListener("mousemove", function (ev) {
      var box = svg.getBoundingClientRect();
      var px = (ev.clientX - box.left) * W / box.width;
      var xv = (px - L) / (W - L - R) * xMax;
      var rows = [], snapX = null;
      cfg.series.forEach(function (s) {
        var p = nearest(s.points, xv);
        if (!p) return;
        if (snapX == null) snapX = p[0];
        rows.push('<div><span class="key' + (s.kind === "dots" ? " dot" : "") + '" style="background:' + s.color + '"></span>' +
          s.name + ": <b>" + fmt(cfg.unit, p[1]) + "</b></div>");
      });
      if (snapX == null) return;
      cross.setAttribute("x1", x(snapX)); cross.setAttribute("x2", x(snapX)); cross.setAttribute("visibility", "visible");
      tip.innerHTML = "<div>" + fmtTime(snapX) + "</div>" + rows.join("");
      tip.style.display = "block";
      var hostBox = host.getBoundingClientRect();
      var left = ev.clientX - hostBox.left + 14;
      if (left + tip.offsetWidth > hostBox.width) left = ev.clientX - hostBox.left - tip.offsetWidth - 14;
      tip.style.left = Math.max(0, left) + "px";
      tip.style.top = Math.max(0, ev.clientY - hostBox.top - 10) + "px";
    });
    hit.addEventListener("mouseleave", function () { tip.style.display = "none"; cross.setAttribute("visibility", "hidden"); });
  }

  var charts = Array.prototype.slice.call(document.querySelectorAll("[data-chart]"));
  charts.forEach(draw);
  var pending = null;
  window.addEventListener("resize", function () {
    clearTimeout(pending);
    pending = setTimeout(function () { charts.forEach(draw); }, 120);
  });

  document.addEventListener("click", function (ev) {
    var b = ev.target.closest && ev.target.closest("button.copy");
    if (b) {
      ev.preventDefault();
      var text = b.getAttribute("data-copy");
      if (navigator.clipboard) navigator.clipboard.writeText(text).then(function () {
        var old = b.textContent; b.textContent = "copied"; setTimeout(function () { b.textContent = old; }, 900);
      });
      return;
    }
    var t = ev.target.closest && ev.target.closest(".theme-toggle");
    if (t) {
      var root = document.documentElement;
      var dark = root.getAttribute("data-theme") ? root.getAttribute("data-theme") === "dark"
        : window.matchMedia("(prefers-color-scheme: dark)").matches;
      root.setAttribute("data-theme", dark ? "light" : "dark");
      try { localStorage.setItem("jfr-report-theme", root.getAttribute("data-theme")); } catch (e) { /* storage unavailable */ }
      charts.forEach(draw);
    }
  });
  try {
    var saved = localStorage.getItem("jfr-report-theme");
    if (saved) { document.documentElement.setAttribute("data-theme", saved); charts.forEach(draw); }
  } catch (e) { /* storage unavailable */ }
})();
