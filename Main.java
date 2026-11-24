package com.smartcanteen;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;

/** Entry point: starts the HTTP server, serves the website and the REST API. */
public class Main {

    public static void main(String[] args) throws Exception {
        Db.init();
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", new Api());
        server.createContext("/", new StaticHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Smart Canteen running at http://localhost:" + port);
    }

    /** Serves HTML, CSS and JavaScript files packaged inside the jar (folder: public). */
    static class StaticHandler implements HttpHandler {
        private static final Map<String, String> TYPES = Map.of(
                "html", "text/html; charset=utf-8",
                "css", "text/css; charset=utf-8",
                "js", "application/javascript; charset=utf-8",
                "ico", "image/x-icon",
                "png", "image/png");

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            InputStream in = path.contains("..") ? null : Main.class.getResourceAsStream("/public" + path);
            if (in == null) {
                byte[] msg = "Not found".getBytes();
                ex.sendResponseHeaders(404, msg.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
                return;
            }
            String ext = path.substring(path.lastIndexOf('.') + 1);
            byte[] data = in.readAllBytes();
            in.close();
            ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        }
    }
}
