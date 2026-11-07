package dev.cogito.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.cogito.CogitoMod;
import dev.cogito.Config;
import dev.cogito.agent.ExternalTools;
import dev.cogito.bot.J;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;

/**
 * MCP over Streamable HTTP on the loopback address, so an external AI can give goals and watch the game.
 * Requests need the token from cogito/mcp_token.txt; browsers are kept out by checking Origin.
 */
public final class McpServer {
    private static final String PROTOCOL = "2025-06-18";

    private McpServer() {}

    public static void start() {
        Config config = Config.get();
        if (!config.mcpEnabled) {
            return;
        }
        try {
            String token = loadToken();
            // Named explicitly: the JVM's default loopback can be ::1, which clients of 127.0.0.1 cannot reach.
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.mcpPort), 16);
            server.setExecutor(Executors.newCachedThreadPool(run -> {
                Thread thread = new Thread(run, "Cogito-MCP");
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/mcp", exchange -> {
                try {
                    handle(exchange, token);
                } catch (RuntimeException e) {
                    CogitoMod.LOG.warn("MCP request failed", e);
                    respond(exchange, 500, "{}");
                } finally {
                    exchange.close();
                }
            });
            server.start();
            CogitoMod.LOG.info("MCP server listening on http://127.0.0.1:{}/mcp (token in cogito/mcp_token.txt)", config.mcpPort);
        } catch (IOException e) {
            CogitoMod.LOG.error("Could not start the MCP server on port {}: {}", config.mcpPort, e.toString());
        }
    }

    private static String loadToken() throws IOException {
        Path file = Config.home().resolve("mcp_token.txt");
        if (Files.exists(file)) {
            return Files.readString(file).trim();
        }
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Files.writeString(file, token);
        return token;
    }

    private static void handle(HttpExchange exchange, String token) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.startsWith("http://127.0.0.1") && !origin.startsWith("http://localhost")) {
            respond(exchange, 403, "{\"error\":\"origin not allowed\"}");
            return;
        }
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.equals("Bearer " + token)) {
            respond(exchange, 401, "{\"error\":\"missing or wrong bearer token\"}");
            return;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            respond(exchange, 405, "{\"error\":\"POST only\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request;
        try {
            request = JsonParser.parseString(body).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            respond(exchange, 400, J.obj("jsonrpc", "2.0", "error", J.obj("code", -32700, "message", "not valid JSON")).toString());
            return;
        }
        if (!request.has("id")) {
            // A notification: nothing to answer.
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        JsonObject response = J.obj("jsonrpc", "2.0", "id", request.get("id"));
        String method = J.str(request, "method", "");
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        switch (method) {
            case "initialize" -> response.add("result", J.obj(
                    "protocolVersion", PROTOCOL,
                    "capabilities", J.obj("tools", new JsonObject()),
                    "serverInfo", J.obj("name", CogitoMod.MOD_ID, "version", version()),
                    "instructions", ExternalTools.INSTRUCTIONS));
            case "ping" -> response.add("result", new JsonObject());
            case "tools/list" -> response.add("result", J.obj("tools", ExternalTools.list()));
            case "tools/call" -> response.add("result", ExternalTools.call(J.str(params, "name"),
                    params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject()));
            default -> response.add("error", J.obj("code", -32601, "message", "unknown method " + method));
        }
        respond(exchange, 200, response.toString());
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** The version from the mod's own metadata, so an MCP client can tell which build it talks to. */
    private static String version() {
        return ModList.get().getModContainerById(CogitoMod.MOD_ID)
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown");
    }
}
