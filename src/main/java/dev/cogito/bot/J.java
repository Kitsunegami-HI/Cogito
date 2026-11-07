package dev.cogito.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Reading arguments and building results as JSON. */
public final class J {
    private J() {}

    public static JsonObject obj(Object... keysAndValues) {
        JsonObject out = new JsonObject();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            put(out, (String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }

    public static void put(JsonObject out, String key, Object value) {
        switch (value) {
            case null -> { }
            case JsonElement element -> out.add(key, element);
            case Number number -> out.addProperty(key, number);
            case Boolean bool -> out.addProperty(key, bool);
            default -> out.addProperty(key, value.toString());
        }
    }

    public static JsonArray nums(double... values) {
        JsonArray out = new JsonArray();
        for (double value : values) {
            out.add(Math.round(value * 100.0) / 100.0);
        }
        return out;
    }

    private static JsonElement need(JsonObject args, String key) {
        JsonElement value = args.get(key);
        if (value == null || value.isJsonNull()) {
            throw new ApiError("bad_argument", "missing argument '" + key + "'");
        }
        return value;
    }

    public static String str(JsonObject args, String key) {
        return need(args, key).getAsString();
    }

    public static String str(JsonObject args, String key, String fallback) {
        JsonElement value = args.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    public static int integer(JsonObject args, String key) {
        try {
            return (int) Math.floor(need(args, key).getAsDouble());
        } catch (NumberFormatException | IllegalStateException e) {
            throw new ApiError("bad_argument", "'" + key + "' must be a number");
        }
    }

    public static int integer(JsonObject args, String key, int fallback) {
        JsonElement value = args.get(key);
        return value == null || value.isJsonNull() ? fallback : integer(args, key);
    }

    public static double number(JsonObject args, String key) {
        try {
            return need(args, key).getAsDouble();
        } catch (NumberFormatException | IllegalStateException e) {
            throw new ApiError("bad_argument", "'" + key + "' must be a number");
        }
    }

    public static double number(JsonObject args, String key, double fallback) {
        JsonElement value = args.get(key);
        return value == null || value.isJsonNull() ? fallback : number(args, key);
    }

    public static boolean bool(JsonObject args, String key, boolean fallback) {
        JsonElement value = args.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    /** A string or an array of strings. */
    public static List<String> strings(JsonObject args, String key) {
        JsonElement value = need(args, key);
        List<String> out = new ArrayList<>();
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(e -> out.add(e.getAsString()));
        } else {
            out.add(value.getAsString());
        }
        return out;
    }
}
