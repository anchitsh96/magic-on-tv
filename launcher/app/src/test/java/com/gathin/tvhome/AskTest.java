package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class AskTest {
    private static final String THRILLER = "{\"mode\":\"discover\",\"title\":\"\",\"kind\":\"film\",\"genres\":[\"trl\"],"
            + "\"exclude_genres\":[],\"min_imdb\":8.5,\"year_from\":0,\"year_to\":0,\"countries\":[],\"apps\":[\"netflix\",\"zee\"],"
            + "\"unseen\":true,\"sort\":\"rating\",\"titles\":[],\"label\":\"Thrillers above 8.5 you haven't seen\","
            + "\"tags\":[\"Film\",\"Thriller\",\"8.5+ on IMDb\",\"Not seen yet\"]}";

    @Test
    public void describesARequestAsFilters() throws Exception {
        Ask.Meaning m = Ask.parse(THRILLER);
        assertEquals("", m.title);
        assertEquals("MOVIE", m.filter.kind);
        assertEquals("[trl]", m.filter.genres.toString());
        assertEquals(8.5, m.filter.minImdb, 0.001);
        assertEquals("IMDB_SCORE", m.filter.sort);
        assertTrue(m.filter.unseen);
        // An app Claude isn't offered is dropped; a known one becomes its package.
        assertEquals("[" + TitleSearch.NETFLIX + "]", m.filter.apps.toString());
        assertEquals("Thrillers above 8.5 you haven't seen", m.label);
        assertEquals(4, m.tags.size());
    }

    @Test
    public void namesATitle() throws Exception {
        Ask.Meaning m = Ask.parse("{\"mode\":\"title\",\"title\":\" Panchayat \",\"kind\":\"any\",\"genres\":[],\"exclude_genres\":[],"
                + "\"min_imdb\":0,\"year_from\":0,\"year_to\":0,\"countries\":[],\"apps\":[],\"unseen\":false,\"sort\":\"popular\","
                + "\"titles\":[],\"label\":\"\",\"tags\":[]}");
        assertEquals("Panchayat", m.title);
        assertEquals("", m.filter.kind);
        assertEquals("POPULAR", m.filter.sort);
    }

    @Test
    public void filterSurvivesBeingPinned() throws Exception {
        Ask.Meaning m = Ask.parse(THRILLER);
        Discover.Filter back = Discover.Filter.fromJson(new JSONObject(m.filter.toJson().toString()));
        assertEquals(m.filter.toJson().toString(), back.toJson().toString());
    }
}
