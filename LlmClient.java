package com.example.aiplayers.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Асинхронный клиент к Anthropic Messages API.
 * Ключ берётся из переменной окружения ANTHROPIC_API_KEY (или -Daiplayers.apikey=...).
 * Модель - AIPLAYERS_MODEL (по умолчанию claude-haiku-5-5: быстрая и дешёвая).
 * Ключ живёт только на сервере (там, где тикают боты), клиентам он не передаётся.
 */
public final class LlmClient {

    /** Не больше стольких запросов одновременно на все боты - защита от лимитов и счёта. */
    public static final int MAX_CONCURRENT = 3;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();
    private static final AtomicInteger INFLIGHT = new AtomicInteger();

    /** Единственный инструмент: модель обязана вернуть действие. */
    private static final JsonObject TOOL = JsonParser.parseString("""
            {
              "name": "act",
              "description": "Выбрать следующее действие игрока.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "thought": {"type": "string", "description": "Короткая мысль, зачем это действие (до 15 слов)."},
                  "action": {"type": "string",
                    "enum": ["wander", "idle", "goto", "follow_player", "mine", "attack_hostile", "hunt", "eat", "sleep", "craft", "smelt", "place", "build_house"]},
                  "x": {"type": "integer"},
                  "y": {"type": "integer"},
                  "z": {"type": "integer"},
                  "player": {"type": "string", "description": "Ник игрока для follow_player."},
                  "block": {"type": "string", "description": "id блока для mine или place, например oak_log, iron_ore, crafting_table, white_bed."},
                  "item": {"type": "string", "description": "id предмета для craft (что скрафтить, например oak_planks) или smelt (что плавить/жарить, например raw_beef)."},
                  "count": {"type": "integer", "description": "Сколько штук: блоков для mine (1-8), раз для craft (1-16), предметов для smelt (1-8)."},
                  "message": {"type": "string", "description": "Сообщение в чат по-русски (необязательно, коротко, как живой игрок)."}
                },
                "required": ["action"]
              }
            }
            """).getAsJsonObject();

    private LlmClient() {}

    public static String apiKey() {
        String k = System.getenv("ANTHROPIC_API_KEY");
        return (k == null || k.isBlank()) ? System.getProperty("aiplayers.apikey") : k;
    }

    public static boolean enabled() {
        String k = apiKey();
        return k != null && !k.isBlank();
    }

    public static String model() {
        String m = System.getenv("AIPLAYERS_MODEL");
        return (m == null || m.isBlank()) ? "claude-haiku-5-5" : m;
    }

    public static int inflight() {
        return INFLIGHT.get();
    }

    public static CompletableFuture<JsonObject> decide(String system, String user) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model());
        body.addProperty("max_tokens", 400);
        body.addProperty("system", system);

        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", user);
        messages.add(msg);
        body.add("messages", messages);

        JsonArray tools = new JsonArray();
        tools.add(TOOL);
        body.add("tools", tools);

        JsonObject choice = new JsonObject();
        choice.addProperty("type", "tool");
        choice.addProperty("name", "act");
        body.add("tool_choice", choice);

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(Duration.ofSeconds(30))
                .header("x-api-key", apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        INFLIGHT.incrementAndGet();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        String b = resp.body();
                        throw new RuntimeException("HTTP " + resp.statusCode() + ": "
                                + (b.length() > 200 ? b.substring(0, 200) : b));
                    }
                    JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                    for (JsonElement el : root.getAsJsonArray("content")) {
                        JsonObject block = el.getAsJsonObject();
                        if ("tool_use".equals(block.get("type").getAsString())) {
                            return block.getAsJsonObject("input");
                        }
                    }
                    throw new RuntimeException("в ответе нет tool_use");
                })
                .whenComplete((r, e) -> INFLIGHT.decrementAndGet());
    }
}
