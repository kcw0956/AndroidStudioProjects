package com.example.lyrics_mobile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** IDs preserve alignment even if the model reorders JSON or omits stanza separators. */
final class LyricLines {
    static String join(String[] lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) { if (i > 0) out.append('\n'); out.append(lines[i]); }
        return out.toString();
    }
    static String input(String[] lines, List<Integer> indices) {
        JsonArray rows = new JsonArray();
        for (int id : indices) {
            JsonObject row = new JsonObject(); row.addProperty("id", id); row.addProperty("text", lines[id]); rows.add(row);
        }
        return rows.toString();
    }
    static String[] parse(String text, List<Integer> expected, int count) {
        String json = text.trim();
        if (json.startsWith("```")) json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        JsonArray rows = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("lines");
        if (rows == null || rows.size() != expected.size()) throw new IllegalArgumentException("LINE_MISMATCH");
        String[] output = new String[count];
        for (JsonElement item : rows) {
            JsonObject row = item.getAsJsonObject();
            int id = row.get("id").getAsBigDecimal().intValueExact();
            if (id < 0 || id >= count || !expected.contains(id) || output[id] != null
                    || !row.get("text").isJsonPrimitive() || !row.get("text").getAsJsonPrimitive().isString()) throw new IllegalArgumentException("LINE_MISMATCH");
            String value = row.get("text").getAsString().trim();
            if (value.isEmpty() || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("LINE_MISMATCH");
            output[id] = value;
        }
        return output;
    }
}
