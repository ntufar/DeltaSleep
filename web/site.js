(function () {
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // Header takes the ink of the chapter under it.
  var header = document.querySelector(".header");
  var chapters = document.querySelectorAll("[data-chapter]");
  function setHeader() {
    var y = header.offsetHeight / 2;
    var on = "stone";
    chapters.forEach(function (c) {
      var r = c.getBoundingClientRect();
      if (r.top <= y && r.bottom > y) on = c.getAttribute("data-chapter");
    });
    header.setAttribute("data-on", on);
  }

  // Reveal the parts of each section, one after the other.
  document.querySelectorAll(".prose h3, .prose p, .prose .faq-item, .prose .callout, .prose .screen-mock, .prose .stat-row, .doc-head p").forEach(function (el) { el.setAttribute("data-reveal", ""); });
  var items = document.querySelectorAll("[data-reveal]");
  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -6% 0px" });
    items.forEach(function (el, i) {
      var sib = el.parentElement ? Array.prototype.indexOf.call(el.parentElement.children, el) : i;
      el.style.setProperty("--d", Math.min(sib, 4) * 70 + "ms");
      io.observe(el);
    });
  } else {
    items.forEach(function (el) { el.classList.add("in"); });
  }

  // Slow scroll-linked drift on the feature pictures.
  var pics = document.querySelectorAll(".row figure img");
  function drift() {
    var vh = window.innerHeight;
    pics.forEach(function (img) {
      var r = img.parentElement.getBoundingClientRect();
      var t = (r.top + r.height / 2 - vh / 2) / vh;
      img.style.transform = "translateY(" + (t * -6).toFixed(2) + "%)";
    });
  }

  var ticking = false;
  function onScroll() {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(function () {
      setHeader();
      if (!reduce) drift();
      ticking = false;
    });
  }
  window.addEventListener("scroll", onScroll, { passive: true });
  window.addEventListener("resize", onScroll);
  setHeader();
  if (!reduce) drift();

  // The live thing: ask the app to reach a server.
  var input = document.getElementById("dest");
  if (input) {
    var out = {
      dest: document.getElementById("o-dest"),
      res: document.getElementById("o-res")
    };
    var update = function () {
      var v = input.value.trim();
      out.dest.textContent = v || "nowhere";
      out.res.textContent = v ? "Refused by Android" : "Nothing to send";
      out.res.className = v ? "refused" : "";
    };
    input.addEventListener("input", update);
    document.querySelectorAll(".examples button").forEach(function (b) {
      b.addEventListener("click", function () {
        input.value = b.textContent;
        update();
        input.focus();
      });
    });
    update();
  }
})();
