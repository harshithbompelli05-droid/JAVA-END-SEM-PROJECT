(function () {
  const API = (window.API_BASE || "").replace(/\/$/, "");
  const $ = (id) => document.getElementById(id);
  const money = (n) => "\u20B9" + Number(n || 0).toFixed(2);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  let token = sessionStorage.getItem("token");
  let user = JSON.parse(sessionStorage.getItem("user") || "null");
  let authMode = "login";
  let menu = [];
  let cart = {}; // menuId -> quantity

  // ---------- helpers ----------
  async function api(path, data) {
    const opts = { method: data ? "POST" : "GET", headers: {} };
    if (token) opts.headers.Authorization = "Bearer " + token;
    if (data) {
      opts.headers["Content-Type"] = "application/x-www-form-urlencoded";
      opts.body = new URLSearchParams(data).toString();
    }
    let res, json;
    try {
      res = await fetch(API + path, opts);
      json = await res.json();
    } catch (e) {
      throw new Error("Cannot reach the Java backend. Set window.API_BASE in config.js to your deployed backend URL.");
    }
    if (!res.ok) {
      if (res.status === 401 && token) logout();
      throw new Error(json.error || "Request failed");
    }
    return json;
  }
  function toast(msg, ok = true) {
    const t = $("toast");
    t.textContent = msg;
    t.className = "toast " + (ok ? "ok" : "err");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.add("hidden"), 3000);
  }
  async function run(fn) {
    try { await fn(); } catch (e) { toast(e.message, false); }
  }
  function show(group, key, attr) {
    document.querySelectorAll("[data-" + attr + "]").forEach((b) => b.classList.toggle("active", b.dataset[attr] === key));
    document.querySelectorAll("[id^='" + group + "-']").forEach((s) => s.classList.toggle("hidden", s.id !== group + "-" + key));
  }

  // ---------- authentication ----------
  function setAuthMode(mode) {
    authMode = mode;
    document.querySelectorAll("[data-auth]").forEach((b) => b.classList.toggle("active", b.dataset.auth === mode));
    $("nameRow").classList.toggle("hidden", mode !== "signup");
    $("authBtn").textContent = mode === "signup" ? "Create Account" : "Sign In";
    updateCodeRow();
  }
  function updateCodeRow() {
    $("codeRow").classList.toggle("hidden", !(authMode === "signup" && $("role").value === "ADMIN"));
  }
  document.querySelectorAll("[data-auth]").forEach((b) => b.addEventListener("click", () => setAuthMode(b.dataset.auth)));
  $("role").addEventListener("change", updateCodeRow);
  document.querySelectorAll("[data-fill]").forEach((b) => b.addEventListener("click", () => {
    const [r, e, p] = b.dataset.fill.split("|");
    setAuthMode("login");
    $("role").value = r; $("email").value = e; $("password").value = p;
  }));

  $("authForm").addEventListener("submit", (ev) => {
    ev.preventDefault();
    run(async () => {
      const base = { email: $("email").value, password: $("password").value, role: $("role").value };
      if (authMode === "signup") {
        const r = await api("/api/signup", { ...base, name: $("name").value, adminCode: $("adminCode").value });
        toast(r.message);
        setAuthMode("login");
      } else {
        const r = await api("/api/login", base);
        token = r.token; user = { name: r.name, role: r.role };
        sessionStorage.setItem("token", token);
        sessionStorage.setItem("user", JSON.stringify(user));
        $("authForm").reset();
        start();
      }
    });
  });
  function logout() {
    token = null; user = null; cart = {};
    sessionStorage.clear();
    start();
  }
  $("logoutBtn").addEventListener("click", logout);

  function start() {
    const signedIn = !!(token && user);
    $("authView").classList.toggle("hidden", signedIn);
    $("userbar").classList.toggle("hidden", !signedIn);
    $("studentView").classList.toggle("hidden", !(signedIn && user.role === "STUDENT"));
    $("adminView").classList.toggle("hidden", !(signedIn && user.role === "ADMIN"));
    if (!signedIn) return;
    $("who").textContent = user.name + " (" + (user.role === "ADMIN" ? "Admin" : "Student") + ")";
    if (user.role === "STUDENT") { show("st", "menu", "st"); run(loadStudentMenu); }
    else { show("ad", "orders", "ad"); run(loadAdminOrders); }
  }

  // ---------- student ----------
  document.querySelectorAll("[data-st]").forEach((b) => b.addEventListener("click", () => {
    show("st", b.dataset.st, "st");
    run({ menu: loadStudentMenu, cart: async () => renderCart(), orders: loadMyOrders }[b.dataset.st]);
  }));

  async function loadStudentMenu() {
    menu = await api("/api/menu");
    const cats = [...new Set(menu.map((m) => m.category))];
    $("st-menu").innerHTML = cats.map((c) =>
      "<h3>" + esc(c) + "</h3><div class='grid'>" +
      menu.filter((m) => m.category === c).map((m) =>
        "<div class='item'><strong>" + esc(m.name) + "</strong><span class='price'>" + money(m.price) + "</span>" +
        (m.available ? "<button class='btn primary small' style='margin-top:6px' data-add='" + m.id + "'>Add to Cart</button>"
                     : "<span class='pill Cancelled'>Not available</span>") + "</div>").join("") + "</div>").join("");
    $("st-menu").querySelectorAll("[data-add]").forEach((b) => b.addEventListener("click", () => {
      const id = b.dataset.add; cart[id] = (cart[id] || 0) + 1; updateCount(); toast("Added to cart");
    }));
    updateCount();
  }
  function updateCount() { $("cartCount").textContent = Object.values(cart).reduce((a, b) => a + b, 0); }

  function renderCart() {
    const ids = Object.keys(cart);
    if (!ids.length) { $("st-cart").innerHTML = "<div class='card empty'>Your cart is empty. Add items from the Menu tab.</div>"; return; }
    let total = 0;
    const rows = ids.map((id) => {
      const m = menu.find((x) => String(x.id) === id); if (!m) return "";
      total += m.price * cart[id];
      return "<tr><td>" + esc(m.name) + "</td><td>" + money(m.price) + "</td><td><span class='qty'><button data-dec='" + id + "'>-</button>" +
        cart[id] + "<button data-inc='" + id + "'>+</button></span></td><td>" + money(m.price * cart[id]) + "</td></tr>";
    }).join("");
    $("st-cart").innerHTML = "<div class='card'><div class='tablewrap'><table><tr><th>Item</th><th>Price</th><th>Quantity</th><th>Amount</th></tr>" + rows +
      "<tr><td colspan='3'><strong>Total</strong></td><td><strong>" + money(total) + "</strong></td></tr></table></div>" +
      "<label>Payment Method</label><select id='payMethod'><option>Cash</option><option>UPI</option><option>Card</option></select>" +
      "<button id='placeBtn' class='btn primary'>Place Order</button></div>";
    const change = (id, d) => { cart[id] += d; if (cart[id] < 1) delete cart[id]; updateCount(); renderCart(); };
    $("st-cart").querySelectorAll("[data-inc]").forEach((b) => b.addEventListener("click", () => change(b.dataset.inc, 1)));
    $("st-cart").querySelectorAll("[data-dec]").forEach((b) => b.addEventListener("click", () => change(b.dataset.dec, -1)));
    $("placeBtn").addEventListener("click", () => run(async () => {
      const items = ids.map((id) => id + ":" + cart[id]).join(",");
      const r = await api("/api/order", { items, method: $("payMethod").value });
      toast("Order #" + r.orderId + " placed. Total " + money(r.total));
      cart = {}; updateCount();
      show("st", "orders", "st"); await loadMyOrders();
    }));
  }

  function itemsText(o) { return o.items.map((i) => esc(i.name) + " x" + i.qty).join(", "); }

  async function loadMyOrders() {
    const list = await api("/api/orders/my");
    if (!list.length) { $("st-orders").innerHTML = "<div class='card empty'>You have not placed any orders yet.</div>"; return; }
    $("st-orders").innerHTML = "<div class='card tablewrap'><table><tr><th>Order</th><th>Date</th><th>Items</th><th>Total</th><th>Payment</th><th>Status</th><th></th></tr>" +
      list.map((o) => "<tr><td>#" + o.id + "</td><td>" + esc(o.created_at) + "</td><td>" + itemsText(o) + "</td><td>" + money(o.total) + "</td><td>" +
        esc(o.method) + " <span class='pill " + esc(o.payment) + "'>" + esc(o.payment) + "</span></td><td><span class='pill " + o.status + "'>" + o.status + "</span></td><td>" +
        (o.status === "Placed" ? "<button class='btn danger small' data-cancel='" + o.id + "'>Cancel</button>" : "") + "</td></tr>").join("") + "</table></div>";
    $("st-orders").querySelectorAll("[data-cancel]").forEach((b) => b.addEventListener("click", () => run(async () => {
      if (!confirm("Cancel this order?")) return;
      toast((await api("/api/orders/cancel", { id: b.dataset.cancel })).message);
      await loadMyOrders();
    })));
  }

  // ---------- admin ----------
  document.querySelectorAll("[data-ad]").forEach((b) => b.addEventListener("click", () => {
    show("ad", b.dataset.ad, "ad");
    run({ orders: loadAdminOrders, menu: loadAdminMenu, finance: loadFinance }[b.dataset.ad]);
  }));

  async function loadAdminOrders() {
    const list = await api("/api/orders/all");
    if (!list.length) { $("ad-orders").innerHTML = "<div class='card empty'>No orders have been placed yet.</div>"; return; }
    const statuses = ["Placed", "Preparing", "Ready", "Completed", "Cancelled"];
    $("ad-orders").innerHTML = "<div class='card tablewrap'><table><tr><th>Order</th><th>Customer</th><th>Date</th><th>Items</th><th>Total</th><th>Payment</th><th>Status</th></tr>" +
      list.map((o) => "<tr><td>#" + o.id + "</td><td>" + esc(o.customer) + "</td><td>" + esc(o.created_at) + "</td><td>" + itemsText(o) + "</td><td>" + money(o.total) + "</td><td>" +
        esc(o.method) + " <span class='pill " + esc(o.payment) + "'>" + esc(o.payment) + "</span></td><td><select data-status='" + o.id + "'>" +
        statuses.map((s) => "<option" + (s === o.status ? " selected" : "") + ">" + s + "</option>").join("") + "</select></td></tr>").join("") + "</table></div>";
    $("ad-orders").querySelectorAll("[data-status]").forEach((s) => s.addEventListener("change", () => run(async () => {
      toast((await api("/api/orders/status", { id: s.dataset.status, status: s.value })).message);
      await loadAdminOrders();
    })));
  }

  async function loadAdminMenu() {
    menu = await api("/api/menu");
    $("ad-menu").innerHTML =
      "<div class='card'><h3 id='formTitle' style='margin-top:0'>Add Menu Item</h3><div class='row'>" +
      "<div><label>Name</label><input id='mName'></div><div><label>Category</label><input id='mCat' list='cats'></div>" +
      "<datalist id='cats'>" + [...new Set(menu.map((m) => m.category))].map((c) => "<option value='" + esc(c) + "'>").join("") + "</datalist>" +
      "<div><label>Price (\u20B9)</label><input id='mPrice' type='number' min='1' step='0.5'></div>" +
      "<div><label>Availability</label><select id='mAvail'><option value='1'>Available</option><option value='0'>Not available</option></select></div></div>" +
      "<input type='hidden' id='mId'><button id='saveItem' class='btn primary'>Save Item</button> <button id='clearItem' class='btn light' style='margin-top:16px'>Clear</button></div>" +
      "<div class='card tablewrap'><table><tr><th>Name</th><th>Category</th><th>Price</th><th>Status</th><th></th></tr>" +
      menu.map((m) => "<tr><td>" + esc(m.name) + "</td><td>" + esc(m.category) + "</td><td>" + money(m.price) + "</td><td>" +
        (m.available ? "<span class='pill Paid'>Available</span>" : "<span class='pill Cancelled'>Unavailable</span>") + "</td><td>" +
        "<button class='btn light small' data-edit='" + m.id + "'>Edit</button> <button class='btn danger small' data-del='" + m.id + "'>Delete</button></td></tr>").join("") + "</table></div>";
    const clear = () => { ["mName", "mCat", "mPrice", "mId"].forEach((i) => ($(i).value = "")); $("mAvail").value = "1"; $("formTitle").textContent = "Add Menu Item"; };
    $("clearItem").addEventListener("click", clear);
    $("saveItem").addEventListener("click", () => run(async () => {
      toast((await api("/api/menu/save", { id: $("mId").value, name: $("mName").value, category: $("mCat").value, price: $("mPrice").value, available: $("mAvail").value })).message);
      await loadAdminMenu();
    }));
    $("ad-menu").querySelectorAll("[data-edit]").forEach((b) => b.addEventListener("click", () => {
      const m = menu.find((x) => String(x.id) === b.dataset.edit);
      $("mId").value = m.id; $("mName").value = m.name; $("mCat").value = m.category; $("mPrice").value = m.price; $("mAvail").value = m.available;
      $("formTitle").textContent = "Edit Menu Item"; window.scrollTo({ top: 0, behavior: "smooth" });
    }));
    $("ad-menu").querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", () => run(async () => {
      if (!confirm("Delete this menu item?")) return;
      toast((await api("/api/menu/delete", { id: b.dataset.del })).message);
      await loadAdminMenu();
    })));
  }

  async function loadFinance() {
    const f = await api("/api/finance"), s = f.summary;
    $("ad-finance").innerHTML =
      "<div class='stats'><div class='stat'><span>Revenue (Paid)</span><strong>" + money(s.revenue) + "</strong></div>" +
      "<div class='stat'><span>Pending Payments</span><strong>" + money(s.pending) + "</strong></div>" +
      "<div class='stat'><span>Total Orders</span><strong>" + s.totalOrders + "</strong></div>" +
      "<div class='stat'><span>Completed / Cancelled</span><strong>" + (s.completed || 0) + " / " + (s.cancelled || 0) + "</strong></div></div>" +
      "<div class='card'><h3 style='margin-top:0'>Daily Revenue (last 7 days)</h3>" +
      (f.daily.length ? "<table><tr><th>Date</th><th>Orders</th><th>Revenue</th></tr>" + f.daily.map((d) => "<tr><td>" + d.day + "</td><td>" + d.orders + "</td><td>" + money(d.amount) + "</td></tr>").join("") + "</table>" : "<p class='empty'>No paid orders yet. Mark an order as Completed to record revenue.</p>") + "</div>" +
      "<div class='card'><h3 style='margin-top:0'>Top Selling Items</h3>" +
      (f.topItems.length ? "<table><tr><th>Item</th><th>Quantity Sold</th><th>Sales</th></tr>" + f.topItems.map((d) => "<tr><td>" + esc(d.name) + "</td><td>" + d.qty + "</td><td>" + money(d.amount) + "</td></tr>").join("") + "</table>" : "<p class='empty'>No completed orders yet.</p>") + "</div>";
  }

  start();
})();
