(function () {
  // Header gets a hairline once the page scrolls.
  var header = document.querySelector(".header");
  function onScroll() { header.classList.toggle("scrolled", window.scrollY > 8); }
  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  // Reveal the parts of each section as they enter the view.
  document.querySelectorAll(".prose h3, .prose p, .prose .faq-item, .prose .callout, .prose .screen-mock, .prose .stat-row, .doc-head p").forEach(function (el) { el.setAttribute("data-reveal", ""); });
  var items = document.querySelectorAll("[data-reveal]");
  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
      });
    }, { threshold: 0.1, rootMargin: "0px 0px -4% 0px" });
    items.forEach(function (el) {
      var sib = el.parentElement ? Array.prototype.indexOf.call(el.parentElement.children, el) : 0;
      el.style.setProperty("--d", Math.min(sib, 4) * 60 + "ms");
      io.observe(el);
    });
  } else {
    items.forEach(function (el) { el.classList.add("in"); });
  }

  // The connection test: ask the app to reach a server.
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
