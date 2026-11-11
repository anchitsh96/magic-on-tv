package com.gathin.tvhome;

import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The search screen's rows: what a row asks JustWatch for (a Filter), the rows every screen has, and the asks a
 * viewer pinned. A filter is kept as JSON in the launcher's preferences, so a pinned row asks again each time the
 * screen opens and picks up what the apps have added.
 */
final class Discover {
    /** JustWatch's short names for the services the TV's apps play, by app. */
    static final Map<String, String[]> SERVICES = new LinkedHashMap<>();

    static {
        SERVICES.put(TitleSearch.NETFLIX, new String[]{"nfx"});
        SERVICES.put(TitleSearch.PRIME, new String[]{"prv", "pva"});
        SERVICES.put(TitleSearch.HOTSTAR, new String[]{"jhs"});
        SERVICES.put(TitleSearch.APPLE_TV, new String[]{"atp"});
        SERVICES.put(TitleSearch.SONY_LIV, new String[]{"snl"});
        SERVICES.put("com.graymatrix.did", new String[]{"zee"});
        SERVICES.put("com.crunchyroll.crunchyroid", new String[]{"cru"});
    }

    /** The words Claude may use for an app (Ask's schema), to the app's package. */
    static final Map<String, String> APP_WORDS = new LinkedHashMap<>();

    static {
        APP_WORDS.put("netflix", TitleSearch.NETFLIX);
        APP_WORDS.put("prime", TitleSearch.PRIME);
        APP_WORDS.put("hotstar", TitleSearch.HOTSTAR);
        APP_WORDS.put("appletv", TitleSearch.APPLE_TV);
        APP_WORDS.put("sonyliv", TitleSearch.SONY_LIV);
    }

    /** JustWatch's genre codes, which Ask's schema allows. */
    static final String[] GENRES = {"act", "ani", "cmy", "crm", "doc", "drm", "fml", "fnt", "hrr", "hst", "msc", "rly", "rma", "scf", "spt", "trl", "war", "wsn"};

    private Discover() { }

    /** What a row asks for. Zero and empty mean "any". */
    static final class Filter {
        /** "MOVIE", "SHOW", or empty for both. */
        String kind = "";
        final List<String> genres = new ArrayList<>();
        final List<String> excludeGenres = new ArrayList<>();
        /** Packages of the TV's apps to keep to; empty for all of them. */
        final List<String> apps = new ArrayList<>();
        /** Two-letter production countries ("IN"). */
        final List<String> countries = new ArrayList<>();
        double minImdb;
        int minVotes, yearFrom, yearTo;
        /** JustWatch's sort: POPULAR, IMDB_SCORE or RELEASE_YEAR. */
        String sort = "POPULAR";
        /** Leave out what the viewer has seen. */
        boolean unseen;

        /** The filter as JustWatch takes it, kept to the TV's apps that play streams (installedServices). */
        JSONObject toJustWatch(List<String> installedServices, PackageManager pm) throws JSONException {
            JSONObject f = new JSONObject();
            if (!kind.isEmpty()) f.put("objectTypes", new JSONArray().put(kind));
            if (!genres.isEmpty()) f.put("genres", new JSONArray(genres));
            if (!excludeGenres.isEmpty()) f.put("excludeGenres", new JSONArray(excludeGenres));
            if (!countries.isEmpty()) f.put("productionCountries", new JSONArray(countries));
            if (minImdb > 0) f.put("imdbScore", new JSONObject().put("min", minImdb));
            // Titles rated by a handful of people top any score; a floor keeps a 9.3 from 74 votes off the rows.
            int votes = minVotes > 0 ? minVotes : minImdb >= 7 ? 5000 : 0;
            if (votes > 0) f.put("imdbVotes", new JSONObject().put("min", votes));
            if (yearFrom > 0 || yearTo > 0) {
                JSONObject y = new JSONObject();
                if (yearFrom > 0) y.put("min", yearFrom);
                if (yearTo > 0) y.put("max", yearTo);
                f.put("releaseYear", y);
            }
            List<String> services = new ArrayList<>();
            if (apps.isEmpty()) services.addAll(installedServices);
            else for (String a : apps) {
                String[] s = SERVICES.get(a);
                if (s != null && TitleSearch.isInstalled(pm, a)) for (String x : s) services.add(x);
            }
            if (services.isEmpty()) services.addAll(installedServices);
            f.put("packages", new JSONArray(services));
            f.put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"));
            return f;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("kind", kind).put("genres", new JSONArray(genres))
                    .put("exclude_genres", new JSONArray(excludeGenres)).put("apps", new JSONArray(apps))
                    .put("countries", new JSONArray(countries)).put("min_imdb", minImdb).put("min_votes", minVotes)
                    .put("year_from", yearFrom).put("year_to", yearTo).put("sort", sort).put("unseen", unseen);
        }

        static Filter fromJson(JSONObject j) {
            Filter f = new Filter();
            f.kind = j.optString("kind");
            strings(j.optJSONArray("genres"), f.genres);
            strings(j.optJSONArray("exclude_genres"), f.excludeGenres);
            strings(j.optJSONArray("apps"), f.apps);
            strings(j.optJSONArray("countries"), f.countries);
            f.minImdb = j.optDouble("min_imdb", 0);
            if (Double.isNaN(f.minImdb)) f.minImdb = 0;
            f.minVotes = j.optInt("min_votes");
            f.yearFrom = j.optInt("year_from");
            f.yearTo = j.optInt("year_to");
            f.sort = j.optString("sort", "POPULAR");
            f.unseen = j.optBoolean("unseen");
            return f;
        }
    }

    /** A row of the screen: its heading, what it asks for, and whether the viewer pinned it from an ask. */
    static final class Row {
        final String label;
        final Filter filter;
        final boolean pinned;

        Row(String label, Filter filter, boolean pinned) {
            this.label = label;
            this.filter = filter;
            this.pinned = pinned;
        }
    }

    /** JustWatch's names for the TV's streaming apps, in SERVICES order. */
    static List<String> installedServices(PackageManager pm) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : SERVICES.entrySet()) {
            String pkg = e.getKey();
            boolean have = TitleSearch.isInstalled(pm, pkg)
                    || (pkg.equals(TitleSearch.APPLE_TV) && TitleSearch.isInstalled(pm, TitleSearch.APPLE_TV_ANDROID));
            if (have) for (String s : e.getValue()) out.add(s);
        }
        return out;
    }

    /**
     * The rows every screen has, after Continue watching and the pinned asks: new this year, the best rated, and
     * what's popular on each of the big apps the TV has.
     */
    static List<Row> standardRows(android.content.Context ctx, PackageManager pm) {
        List<Row> rows = new ArrayList<>();
        Filter fresh = new Filter();
        fresh.yearFrom = Calendar.getInstance().get(Calendar.YEAR);
        fresh.minVotes = 500;
        fresh.minImdb = 6.5;
        rows.add(new Row(ctx.getString(R.string.search_row_new), fresh, false));
        Filter best = new Filter();
        best.minVotes = 200000;
        best.sort = "IMDB_SCORE";
        best.unseen = true;
        rows.add(new Row(ctx.getString(R.string.search_row_top), best, false));
        for (String pkg : new String[]{TitleSearch.NETFLIX, TitleSearch.PRIME, TitleSearch.HOTSTAR}) {
            if (!TitleSearch.isInstalled(pm, pkg)) continue;
            Filter f = new Filter();
            f.apps.add(pkg);
            f.minVotes = 5000;
            rows.add(new Row(ctx.getString(R.string.search_row_popular_on, appLabel(pm, pkg)), f, false));
        }
        return rows;
    }

    static String appLabel(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** "Prime Video +1 | ★ 8.8": the first app that has it, how many others do, and its IMDb score. */
    static String footFor(TitleSearch.Title t, PackageManager pm) {
        String app = t.offers.isEmpty() ? "" : shortLabel(appLabel(pm, t.offers.get(0).pkg));
        if (t.offers.size() > 1) app += " +" + (t.offers.size() - 1);
        return app;
    }

    /** "Prime Video" rather than "Amazon Prime Video", and "JioHotstar" as it is. */
    static String shortLabel(String label) {
        return label.replaceFirst("^Amazon ", "").trim();
    }

    private static void strings(JSONArray a, List<String> out) {
        for (int i = 0; a != null && i < a.length(); i++) {
            String s = a.optString(i).trim();
            if (!s.isEmpty()) out.add(s.length() == 2 ? s.toUpperCase(Locale.ROOT) : s);
        }
    }
}
