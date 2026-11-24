package com.smartcanteen;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** REST API: login, menu, orders and finance. All database access uses JDBC. */
public class Api implements HttpHandler {

    static final String ADMIN_CODE = System.getenv().getOrDefault("ADMIN_CODE", "CANTEEN-ADMIN-2026");
    static final List<String> STATUSES = List.of("Placed", "Preparing", "Ready", "Completed", "Cancelled");

    record Session(int userId, String name, String role) {}

    static class ApiException extends RuntimeException {
        final int code;
        ApiException(int code, String msg) { super(msg); this.code = code; }
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    @Override
    public void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        if (ex.getRequestMethod().equals("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        try {
            Map<String, String> p = params(ex);
            Session s = session(ex);
            Object result;
            synchronized (LOCK) {
                result = route(ex.getRequestURI().getPath(), p, s);
            }
            send(ex, 200, result);
        } catch (ApiException e) {
            send(ex, e.code, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Map.of("error", "Internal server error"));
        }
    }

    private Object route(String path, Map<String, String> p, Session s) throws Exception {
        switch (path) {
            case "/api/signup": return signup(p);
            case "/api/login": return login(p);
            case "/api/logout": return Map.of("ok", true);
            case "/api/menu": need(s, null); return menu();
            case "/api/menu/save": need(s, "ADMIN"); return saveMenu(p);
            case "/api/menu/delete": need(s, "ADMIN"); return deleteMenu(p);
            case "/api/order": need(s, "STUDENT"); return placeOrder(s, p);
            case "/api/orders/my": need(s, "STUDENT"); return orders(s.userId());
            case "/api/orders/cancel": need(s, "STUDENT"); return cancelOrder(s, p);
            case "/api/orders/all": need(s, "ADMIN"); return orders(null);
            case "/api/orders/status": need(s, "ADMIN"); return updateStatus(p);
            case "/api/finance": need(s, "ADMIN"); return finance();
            default: throw new ApiException(404, "Unknown endpoint");
        }
    }

    // ---------- Authentication ----------

    private Object signup(Map<String, String> p) throws SQLException {
        String name = val(p, "name"), email = val(p, "email").toLowerCase(), pw = val(p, "password");
        String role = val(p, "role").toUpperCase();
        if (name.isEmpty() || !email.contains("@") || pw.length() < 6)
            throw new ApiException(400, "Enter a name, a valid email and a password of at least 6 characters.");
        if (!role.equals("STUDENT") && !role.equals("ADMIN")) throw new ApiException(400, "Invalid role.");
        if (role.equals("ADMIN") && !ADMIN_CODE.equals(val(p, "adminCode")))
            throw new ApiException(403, "Incorrect admin code.");
        try (Connection c = Db.get()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE email=?")) {
                ps.setString(1, email);
                if (ps.executeQuery().next()) throw new ApiException(409, "This email is already registered.");
            }
            Db.addUser(c, name, email, pw, role);
        }
        return Map.of("message", "Account created. Please sign in.");
    }

    private Object login(Map<String, String> p) throws SQLException {
        String email = val(p, "email").toLowerCase(), role = val(p, "role").toUpperCase();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE email=? AND role=?")) {
            ps.setString(1, email);
            ps.setString(2, role);
            ResultSet rs = ps.executeQuery();
            if (!rs.next() || !rs.getString("password_hash").equals(Db.hash(val(p, "password"), rs.getString("salt"))))
                throw new ApiException(401, "Invalid email, password or role.");
            String token = UUID.randomUUID().toString();
            Session s = new Session(rs.getInt("id"), rs.getString("name"), role);
            SESSIONS.put(token, s);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", token);
            m.put("name", s.name());
            m.put("role", role);
            return m;
        }
    }

    private Session session(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? SESSIONS.get(h.substring(7)) : null;
    }

    private void need(Session s, String role) {
        if (s == null) throw new ApiException(401, "Please sign in.");
        if (role != null && !s.role().equals(role)) throw new ApiException(403, "Access denied.");
    }

    // ---------- Menu ----------

    private Object menu() throws SQLException {
        return query("SELECT id,name,category,price,available FROM menu ORDER BY category,name");
    }

    private Object saveMenu(Map<String, String> p) throws SQLException {
        String name = val(p, "name"), category = val(p, "category");
        double price;
        try { price = Double.parseDouble(val(p, "price")); } catch (NumberFormatException e) { price = -1; }
        if (name.isEmpty() || category.isEmpty() || price <= 0)
            throw new ApiException(400, "Enter a name, category and a valid price.");
        int available = val(p, "available").equals("0") ? 0 : 1;
        try (Connection c = Db.get()) {
            String sql = val(p, "id").isEmpty()
                    ? "INSERT INTO menu(name,category,price,available) VALUES(?,?,?,?)"
                    : "UPDATE menu SET name=?,category=?,price=?,available=? WHERE id=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, category);
                ps.setDouble(3, price);
                ps.setInt(4, available);
                if (!val(p, "id").isEmpty()) ps.setInt(5, Integer.parseInt(val(p, "id")));
                ps.executeUpdate();
            }
        }
        return Map.of("message", "Menu saved.");
    }

    private Object deleteMenu(Map<String, String> p) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("DELETE FROM menu WHERE id=?")) {
            ps.setInt(1, Integer.parseInt(val(p, "id")));
            ps.executeUpdate();
        }
        return Map.of("message", "Item deleted.");
    }

    // ---------- Orders ----------

    /** items format: "menuId:qty,menuId:qty" */
    private Object placeOrder(Session s, Map<String, String> p) throws SQLException {
        String method = val(p, "method").isEmpty() ? "Cash" : val(p, "method");
        if (val(p, "items").isEmpty()) throw new ApiException(400, "Your cart is empty.");
        try (Connection c = Db.get()) {
            c.setAutoCommit(false);
            try {
                double total = 0;
                List<Object[]> lines = new ArrayList<>();
                for (String part : val(p, "items").split(",")) {
                    String[] kv = part.split(":");
                    int id = Integer.parseInt(kv[0]), qty = Integer.parseInt(kv[1]);
                    if (qty < 1 || qty > 20) throw new ApiException(400, "Invalid quantity.");
                    try (PreparedStatement ps = c.prepareStatement("SELECT name,price,available FROM menu WHERE id=?")) {
                        ps.setInt(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next() || rs.getInt("available") == 0)
                            throw new ApiException(400, "An item in your cart is no longer available.");
                        lines.add(new Object[]{id, rs.getString("name"), rs.getDouble("price"), qty});
                        total += rs.getDouble("price") * qty;
                    }
                }
                int orderId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO orders(user_id,total,status,created_at) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, s.userId());
                    ps.setDouble(2, total);
                    ps.setString(3, "Placed");
                    ps.setString(4, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                    ps.executeUpdate();
                    ResultSet k = ps.getGeneratedKeys();
                    k.next();
                    orderId = k.getInt(1);
                }
                for (Object[] l : lines) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO order_items(order_id,menu_id,name,price,qty) VALUES(?,?,?,?,?)")) {
                        ps.setInt(1, orderId);
                        ps.setInt(2, (Integer) l[0]);
                        ps.setString(3, (String) l[1]);
                        ps.setDouble(4, (Double) l[2]);
                        ps.setInt(5, (Integer) l[3]);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO payments(order_id,method,amount,status) VALUES(?,?,?,'Pending')")) {
                    ps.setInt(1, orderId);
                    ps.setString(2, method);
                    ps.setDouble(3, total);
                    ps.executeUpdate();
                }
                c.commit();
                return Map.of("message", "Order placed successfully.", "orderId", orderId, "total", total);
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Returns orders (with items and payment). userId == null returns every order (admin). */
    private Object orders(Integer userId) throws SQLException {
        String sql = "SELECT o.id,o.total,o.status,o.created_at,u.name AS customer,p.method,p.status AS payment "
                + "FROM orders o JOIN users u ON u.id=o.user_id LEFT JOIN payments p ON p.order_id=o.id "
                + (userId == null ? "" : "WHERE o.user_id=" + userId + " ")
                + "ORDER BY o.id DESC";
        List<Map<String, Object>> list = query(sql);
        for (Map<String, Object> o : list)
            o.put("items", query("SELECT name,price,qty FROM order_items WHERE order_id=" + o.get("id")));
        return list;
    }

    private Object cancelOrder(Session s, Map<String, String> p) throws SQLException {
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE orders SET status='Cancelled' WHERE id=? AND user_id=? AND status='Placed'")) {
            ps.setInt(1, Integer.parseInt(val(p, "id")));
            ps.setInt(2, s.userId());
            if (ps.executeUpdate() == 0)
                throw new ApiException(400, "Only orders that are still 'Placed' can be cancelled.");
            syncPayment(c, Integer.parseInt(val(p, "id")), "Cancelled");
        }
        return Map.of("message", "Order cancelled.");
    }

    private Object updateStatus(Map<String, String> p) throws SQLException {
        String status = val(p, "status");
        if (!STATUSES.contains(status)) throw new ApiException(400, "Invalid status.");
        int id = Integer.parseInt(val(p, "id"));
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("UPDATE orders SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setInt(2, id);
            ps.executeUpdate();
            syncPayment(c, id, status);
        }
        return Map.of("message", "Order status updated.");
    }

    /** Payment becomes Paid when the order is Completed. */
    private void syncPayment(Connection c, int orderId, String orderStatus) throws SQLException {
        String pay = orderStatus.equals("Completed") ? "Paid" : orderStatus.equals("Cancelled") ? "Cancelled" : "Pending";
        try (PreparedStatement ps = c.prepareStatement("UPDATE payments SET status=? WHERE order_id=?")) {
            ps.setString(1, pay);
            ps.setInt(2, orderId);
            ps.executeUpdate();
        }
    }

    // ---------- Finance ----------

    private Object finance() throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", query("SELECT "
                + "COALESCE(SUM(CASE WHEN p.status='Paid' THEN p.amount END),0) AS revenue,"
                + "COALESCE(SUM(CASE WHEN p.status='Pending' THEN p.amount END),0) AS pending,"
                + "COUNT(*) AS totalOrders,"
                + "SUM(CASE WHEN o.status='Completed' THEN 1 ELSE 0 END) AS completed,"
                + "SUM(CASE WHEN o.status='Cancelled' THEN 1 ELSE 0 END) AS cancelled "
                + "FROM orders o JOIN payments p ON p.order_id=o.id").get(0));
        m.put("daily", query("SELECT substr(o.created_at,1,10) AS day, SUM(p.amount) AS amount, COUNT(*) AS orders "
                + "FROM orders o JOIN payments p ON p.order_id=o.id WHERE p.status='Paid' "
                + "GROUP BY day ORDER BY day DESC LIMIT 7"));
        m.put("topItems", query("SELECT i.name, SUM(i.qty) AS qty, SUM(i.qty*i.price) AS amount "
                + "FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.status='Completed' "
                + "GROUP BY i.name ORDER BY qty DESC LIMIT 5"));
        return m;
    }

    // ---------- Helpers ----------

    private List<Map<String, Object>> query(String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = Db.get(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String val(Map<String, String> p, String k) {
        String v = p.get(k);
        return v == null ? "" : v.trim();
    }

    private Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String src : new String[]{q, body}) {
            if (src == null || src.isEmpty()) continue;
            for (String pair : src.split("&")) {
                String[] kv = pair.split("=", 2);
                m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return m;
    }

    private void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] data = Json.of(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
