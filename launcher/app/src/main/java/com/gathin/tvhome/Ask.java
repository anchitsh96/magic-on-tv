package com.gathin.tvhome;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a spoken request means, from Claude: a title to look up ("Stranger Things"), or a description to turn into
 * JustWatch filters ("a thriller above 8.5 on IMDb I haven't seen"), with a heading for its row and the few words
 * the screen shows as what it understood. Only the words said are sent. Call off the main thread.
 */
final class Ask {
    /** Fast and cheap enough to answer while the screen opens: about a second, a fraction of a rupee a request. */
    static final String MODEL = "claude-haiku-5-5";

    /** What the request means. */
    static final class Meaning {
        /** A specific film or show to find; empty for a description. */
        String title = "";
        Discover.Filter filter = new Discover.Filter();
        /** Specific titles to look up for requests like "something like Dark", where filters can't say it. */
        final List<String> titles = new ArrayList<>();
        /** The row's heading ("Thrillers above 8.5 you haven't seen"). */
        String label = "";
        /** What it understood, a few words each ("Film", "Thriller", "8.5+ on IMDb"). */
        final List<String> tags = new ArrayList<>();
    }

    private Ask() { }

    static Meaning understand(String words, String apiKey) throws JSONException {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(15))
                .build();
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(1024L)
                    .system(system())
                    .outputConfig(OutputConfig.builder()
                            .effort(OutputConfig.Effort.LOW)
                            .format(JsonOutputFormat.builder().schema(schema()).build())
                            .build())
                    .addUserMessage(words)
                    .build();
            Message reply = client.messages().create(params);
            if (reply.stopReason().map(r -> r.equals(StopReason.REFUSAL) || r.equals(StopReason.MAX_TOKENS)).orElse(false)) {
                throw new JSONException("no answer: " + reply.stopReason());
            }
            StringBuilder text = new StringBuilder();
            for (ContentBlock b : reply.content()) b.text().ifPresent(t -> text.append(t.text()));
            return parse(text.toString());
        } finally {
            client.close();
        }
    }

    /** The answer, as the schema shapes it. */
    static Meaning parse(String json) throws JSONException {
        JSONObject j = new JSONObject(json);
        Meaning m = new Meaning();
        if ("title".equals(j.optString("mode"))) m.title = j.optString("title").trim();
        Discover.Filter f = m.filter;
        String kind = j.optString("kind");
        f.kind = "film".equals(kind) ? "MOVIE" : "show".equals(kind) ? "SHOW" : "";
        strings(j.optJSONArray("genres"), f.genres);
        strings(j.optJSONArray("exclude_genres"), f.excludeGenres);
        List<String> apps = new ArrayList<>();
        strings(j.optJSONArray("apps"), apps);
        for (String a : apps) {
            String pkg = Discover.APP_WORDS.get(a);
            if (pkg != null) f.apps.add(pkg);
        }
        strings(j.optJSONArray("countries"), f.countries);
        f.minImdb = Math.max(0, j.optDouble("min_imdb", 0));
        if (Double.isNaN(f.minImdb)) f.minImdb = 0;
        f.yearFrom = Math.max(0, j.optInt("year_from"));
        f.yearTo = Math.max(0, j.optInt("year_to"));
        String sort = j.optString("sort");
        f.sort = "rating".equals(sort) ? "IMDB_SCORE" : "newest".equals(sort) ? "RELEASE_YEAR" : "POPULAR";
        f.unseen = j.optBoolean("unseen");
        strings(j.optJSONArray("titles"), m.titles);
        m.label = j.optString("label").trim();
        strings(j.optJSONArray("tags"), m.tags);
        return m;
    }

    private static String system() {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        return "You turn what someone said to their TV's search into a search of the streaming catalogue in India. "
                + "It is " + year + ". "
                + "If they name a specific film or show to watch, answer mode \"title\" with its name as said, corrected "
                + "to the real title. Otherwise answer mode \"discover\" and describe what they want with the filters: "
                + "kind (film, show or any), JustWatch genre codes (act action, ani animation, cmy comedy, crm crime, "
                + "doc documentary, drm drama, fml family, fnt fantasy, hrr horror, hst history, msc music, rly reality, "
                + "rma romance, scf sci-fi, spt sport, trl thriller, war war, wsn western), genres to leave out (for "
                + "\"feel-good\", leave out crm, trl and hrr), the lowest IMDb rating they asked for (0 for none), release "
                + "years (0 for none; \"new\" or \"recent\" means from " + (year - 1) + "), production countries as two "
                + "letters (\"Indian\" or \"Hindi\" means IN, \"Korean\" KR), the apps they named, whether to leave out what "
                + "they have already seen, and the order (\"rating\" for best or highest rated, \"newest\", otherwise "
                + "\"popular\"). For a request filters can't say, like \"something like Dark\" or \"what's the one with the "
                + "time loop\", also list up to 10 real titles that fit, best first; otherwise leave titles empty. "
                + "Write label as a short row heading in sentence case, like \"Thrillers above 8.5 you haven't seen\", "
                + "and tags as two to five words or short phrases of what you understood, like \"Film\", \"Thriller\", "
                + "\"8.5+ on IMDb\", \"Not seen yet\".";
    }

    private static JsonOutputFormat.Schema schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mode", Map.of("type", "string", "enum", Arrays.asList("title", "discover")));
        props.put("title", Map.of("type", "string"));
        props.put("kind", Map.of("type", "string", "enum", Arrays.asList("film", "show", "any")));
        Map<String, Object> genre = Map.of("type", "string", "enum", Arrays.asList(Discover.GENRES));
        props.put("genres", Map.of("type", "array", "items", genre));
        props.put("exclude_genres", Map.of("type", "array", "items", genre));
        props.put("min_imdb", Map.of("type", "number"));
        props.put("year_from", Map.of("type", "integer"));
        props.put("year_to", Map.of("type", "integer"));
        props.put("countries", Map.of("type", "array", "items", Map.of("type", "string")));
        props.put("apps", Map.of("type", "array", "items",
                Map.of("type", "string", "enum", new ArrayList<>(Discover.APP_WORDS.keySet()))));
        props.put("unseen", Map.of("type", "boolean"));
        props.put("sort", Map.of("type", "string", "enum", Arrays.asList("popular", "rating", "newest")));
        props.put("titles", Map.of("type", "array", "items", Map.of("type", "string")));
        props.put("label", Map.of("type", "string"));
        props.put("tags", Map.of("type", "array", "items", Map.of("type", "string")));
        return JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(props))
                .putAdditionalProperty("required", JsonValue.from(new ArrayList<>(props.keySet())))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
    }

    private static void strings(JSONArray a, List<String> out) {
        for (int i = 0; a != null && i < a.length(); i++) {
            String s = a.optString(i).trim();
            if (!s.isEmpty()) out.add(s);
        }
    }
}
