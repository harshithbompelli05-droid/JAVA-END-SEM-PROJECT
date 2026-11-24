package com.smartcanteen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Database layer: JDBC connection to SQLite, schema creation and demo data. */
public class Db {

    private static final String URL =
            "jdbc:sqlite:" + System.getenv().getOrDefault("DB_PATH", "canteen.db");

    public static Connection get() throws SQLException {
        Connection c = DriverManager.getConnection(URL);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }

    public static void init() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = get(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT NOT NULL, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, "
                    + "salt TEXT NOT NULL, role TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS menu(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT NOT NULL, category TEXT NOT NULL, price REAL NOT NULL, available INTEGER NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE IF NOT EXISTS orders(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "user_id INTEGER NOT NULL, total REAL NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS order_items(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "order_id INTEGER NOT NULL, menu_id INTEGER, name TEXT NOT NULL, price REAL NOT NULL, qty INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS payments(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "order_id INTEGER NOT NULL, method TEXT NOT NULL, amount REAL NOT NULL, status TEXT NOT NULL)");

            if (!exists(c, "SELECT 1 FROM users LIMIT 1")) {
                addUser(c, "Demo Student", "student@canteen.com", "student123", "STUDENT");
                addUser(c, "Canteen Admin", "admin@canteen.com", "admin123", "ADMIN");
            }
            if (!exists(c, "SELECT 1 FROM menu LIMIT 1")) {
                Object[][] items = {
                    {"Masala Dosa", "Breakfast", 50}, {"Idli (3 pcs)", "Breakfast", 30},
                    {"Poori with Curry", "Breakfast", 45}, {"Veg Fried Rice", "Meals", 70},
                    {"Veg Meals", "Meals", 80}, {"Chicken Biryani", "Meals", 120},
                    {"Samosa", "Snacks", 15}, {"Veg Puff", "Snacks", 20},
                    {"Tea", "Beverages", 10}, {"Filter Coffee", "Beverages", 15},
                    {"Cold Coffee", "Beverages", 40}};
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO menu(name,category,price) VALUES(?,?,?)")) {
                    for (Object[] i : items) {
                        ps.setString(1, (String) i[0]);
                        ps.setString(2, (String) i[1]);
                        ps.setDouble(3, ((Number) i[2]).doubleValue());
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    private static boolean exists(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { return rs.next(); }
    }

    public static void addUser(Connection c, String name, String email, String password, String role) throws SQLException {
        String salt = newSalt();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users(name,email,password_hash,salt,role) VALUES(?,?,?,?,?)")) {
            ps.setString(1, name);
            ps.setString(2, email.toLowerCase());
            ps.setString(3, hash(password, salt));
            ps.setString(4, salt);
            ps.setString(5, role);
            ps.executeUpdate();
        }
    }

    public static String newSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return toHex(b);
    }

    public static String hash(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest((salt + password).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
