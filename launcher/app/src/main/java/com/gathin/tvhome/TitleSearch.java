package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * title_search: which of the TV's apps has a title, from JustWatch's catalogue for one country, and how to open each.
 * The search words are the only thing sent; the lookup runs only when someone searches. Each app opens as far as it
 * was found to allow on a Sony BRAVIA (Android TV 12): the show itself, its own search with the words filled in, or
 * only the app.
 */
final class TitleSearch {
    static final int SHOW = 0, SEARCH = 1, APP = 2;

    static final String YOUTUBE = "com.google.android.youtube.tv";
    static final String NETFLIX = "com.netflix.ninja";
    static final String PRIME = "com.amazon.amazonvideo.livingroom";
    static final String APPLE_TV = "com.apple.atve.sony.appletv";
    static final String APPLE_TV_ANDROID = "com.apple.atve.androidtv.appletv";
    static final String HOTSTAR = "in.startv.hotstar";
    static final String SONY_LIV = "com.sonyliv";

    private static final String API = "https://apis.justwatch.com/graphql";
    private static final String IMAGES = "https://images.justwatch.com";
    private static final int TIMEOUT_MS = 8000;

    /** One app that has the title: where it opens it, and on what terms ("Included with your plan"). */
    static final class Offer {
        final String pkg;
        final int how;
        final String url;
        final int terms;

        Offer(String pkg, int how, String url, int terms) {
            this.pkg = pkg;
            this.how = how;
            this.url = url;
            this.terms = terms;
        }
    }

    /** A film or show, and the TV's apps that have it, best terms first. */
    static final class Title {
        String title = "", kind = "", description = "", poster = "";
        int year;
        final List<Offer> offers = new ArrayList<>();
    }

    /** Terms an app offers a title on, best first; the index into R.array.title_search_terms. */
    static final int INCLUDED = 0, FREE = 1, ADS = 2, RENT = 3, BUY = 4;

    private TitleSearch() { }

    /** JustWatch's name for a streaming service, to the TV app that plays it; null for one the TV has no app for. */
    static String packageFor(String technicalName) {
        switch (technicalName) {
            case "netflix":
            case "netflixbasicwithads":
                return NETFLIX;
            case "amazonprimevideo":
            case "amazonprimevideowithads":
            case "amazonprime":
                return PRIME;
            case "appletvplus":
            case "appletv":
                return APPLE_TV;
            case "jiohotstar":
            case "hotstar":
                return HOTSTAR;
            case "sonyliv":
                return SONY_LIV;
            case "zee5":
                return "com.graymatrix.did";
            case "crunchyroll":
                return "com.crunchyroll.crunchyroid";
            case "mxplayer":
                return "com.mxtech.videoplayer.television";
            default:
                return null;
        }
    }

    /** How far an app opens a title: Sony LIV ignores links, and the rest open the title's own page. */
    static int howFor(String pkg) {
        if (SONY_LIV.equals(pkg)) return APP;
        if (YOUTUBE.equals(pkg)) return SEARCH;
        return pkg.equals(NETFLIX) || pkg.equals(PRIME) || pkg.equals(APPLE_TV) || pkg.equals(HOTSTAR) ? SHOW : APP;
    }

    static int termsFor(String monetizationType) {
        switch (monetizationType) {
            case "FLATRATE":
                return INCLUDED;
            case "FREE":
                return FREE;
            case "ADS":
                return ADS;
            case "RENT":
                return RENT;
            default:
                return BUY;
        }
    }

    /**
     * Apple TV links from JustWatch point at the first episode (with the show in a showId parameter), which plays
     * straight away; the show's own page is the better place to land.
     */
    static String appleShowUrl(String url) {
        int at = url.indexOf("showId=");
        if (at < 0) return url;
        int end = url.indexOf('&', at);
        String id = url.substring(at + 7, end < 0 ? url.length() : end);
        int country = url.indexOf("tv.apple.com/");
        String cc = country < 0 ? "" : url.substring(country + 13, Math.min(url.length(), country + 15));
        return "https://tv.apple.com/" + (cc.matches("[a-z]{2}") ? cc + "/" : "") + "show/" + id;
    }

    /** JustWatch poster paths carry {profile} and {format} slots; s332 is about 332px wide, enough for the panel. */
    static String posterUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        return IMAGES + path.replace("{profile}", "s332").replace("{format}", "jpg");
    }

    static String youtubeSearchUrl(String words) {
        return "https://www.youtube.com/results?search_query=" + encode(words);
    }

    static String primeSearchUrl(String words) {
        return "https://app.primevideo.com/search?phrase=" + encode(words);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    static String requestBody(String words, String country, int first) throws JSONException {
        String query = "query($country:Country!,$language:Language!,$first:Int!,$filter:TitleFilter){"
                + "popularTitles(country:$country,first:$first,filter:$filter){edges{node{objectType "
                + "content(country:$country,language:$language){title originalReleaseYear shortDescription posterUrl} "
                + "offers(country:$country,platform:WEB){monetizationType standardWebURL package{technicalName}}}}}}";
        JSONObject vars = new JSONObject()
                .put("country", country).put("language", "en").put("first", first)
                .put("filter", new JSONObject().put("searchQuery", words));
        return new JSONObject().put("query", query).put("variables", vars).toString();
    }

    /** Titles from a JustWatch answer, each with the installed apps that have it, one offer per app on its best terms. */
    static List<Title> parse(String json, PackageManager pm) throws JSONException {
        List<Title> out = new ArrayList<>();
        JSONArray edges = new JSONObject(json).getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.getJSONObject(i).getJSONObject("node");
            JSONObject content = node.getJSONObject("content");
            Title t = new Title();
            t.title = content.optString("title");
            t.year = content.optInt("originalReleaseYear");
            t.description = content.optString("shortDescription");
            t.poster = posterUrl(content.optString("posterUrl"));
            t.kind = "MOVIE".equals(node.optString("objectType")) ? "MOVIE" : "SHOW";
            Map<String, Offer> best = new LinkedHashMap<>();
            JSONArray offers = node.optJSONArray("offers");
            for (int k = 0; offers != null && k < offers.length(); k++) {
                JSONObject o = offers.getJSONObject(k);
                String pkg = packageFor(o.getJSONObject("package").optString("technicalName"));
                if (pkg == null) continue;
                pkg = installed(pm, pkg);
                if (pkg == null) continue;
                int terms = termsFor(o.optString("monetizationType"));
                Offer have = best.get(pkg);
                if (have != null && have.terms <= terms) continue;
                String url = o.optString("standardWebURL");
                if (pkg.startsWith("com.apple.atve")) url = appleShowUrl(url);
                best.put(pkg, new Offer(pkg, url.isEmpty() ? APP : howFor(pkg), url, terms));
            }
            t.offers.addAll(best.values());
            t.offers.sort((a, b) -> a.terms != b.terms ? Integer.compare(a.terms, b.terms) : Integer.compare(a.how, b.how));
            if (!t.title.isEmpty()) out.add(t);
        }
        return out;
    }

    /** The package as installed, or its other name (Apple TV ships under two), or null when neither is. */
    private static String installed(PackageManager pm, String pkg) {
        if (isInstalled(pm, pkg)) return pkg;
        if (pkg.equals(APPLE_TV) && isInstalled(pm, APPLE_TV_ANDROID)) return APPLE_TV_ANDROID;
        return null;
    }

    static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Looks the words up; call off the main thread. */
    static List<Title> search(String words, String country, PackageManager pm) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(requestBody(words, country, 6).getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return parse(new String(read(c.getInputStream()), StandardCharsets.UTF_8), pm);
        } finally {
            c.disconnect();
        }
    }

    /** A poster, scaled down on decode to about the size it is shown at; null when it cannot be fetched. */
    static Bitmap poster(String url, int width) {
        if (url == null || url.isEmpty()) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                byte[] bytes = read(c.getInputStream());
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            for (int n; (n = is.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * The intent that opens an offer. Netflix follows a title link only when it comes the way TV makers' home
     * screens send it (its own activity, the launcher category, a fresh task and source 30); a plain link leaves
     * it on its home screen. Others take the link as is, and an app with no usable link just opens.
     */
    static Intent intentFor(Offer o, String words, PackageManager pm) {
        if (o.how == SHOW && NETFLIX.equals(o.pkg)) {
            return new Intent(Intent.ACTION_VIEW, Uri.parse(o.url))
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(NETFLIX, NETFLIX + ".MainActivity"))
                    .putExtra("source", "30")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        }
        if (o.how == SHOW || o.how == SEARCH) {
            String url = o.how == SEARCH && YOUTUBE.equals(o.pkg) ? youtubeSearchUrl(words)
                    : o.how == SEARCH && PRIME.equals(o.pkg) ? primeSearchUrl(words) : o.url;
            return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(o.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        Intent i = pm.getLeanbackLaunchIntentForPackage(o.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(o.pkg);
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** YouTube's search, offered under every result and when nothing matches. */
    static Offer youtube() {
        return new Offer(YOUTUBE, SEARCH, "", FREE);
    }

    /** Prime Video's search, offered when nothing matches: it takes the words as they were said. */
    static Offer primeSearch() {
        return new Offer(PRIME, SEARCH, "", INCLUDED);
    }
}
