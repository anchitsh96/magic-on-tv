package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TitleSearchTest {
    @Test
    public void servicesMapToTheirTvApps() {
        assertEquals(TitleSearch.NETFLIX, TitleSearch.packageFor("netflix"));
        assertEquals(TitleSearch.PRIME, TitleSearch.packageFor("amazonprimevideowithads"));
        assertEquals(TitleSearch.APPLE_TV, TitleSearch.packageFor("appletvplus"));
        assertEquals(TitleSearch.HOTSTAR, TitleSearch.packageFor("jiohotstar"));
        assertEquals(TitleSearch.SONY_LIV, TitleSearch.packageFor("sonyliv"));
        assertNull(TitleSearch.packageFor("vimoviesandtv"));
    }

    @Test
    public void eachAppOpensAsFarAsItAllows() {
        assertEquals(TitleSearch.SHOW, TitleSearch.howFor(TitleSearch.NETFLIX));
        assertEquals(TitleSearch.SHOW, TitleSearch.howFor(TitleSearch.APPLE_TV));
        assertEquals(TitleSearch.SEARCH, TitleSearch.howFor(TitleSearch.YOUTUBE));
        assertEquals(TitleSearch.APP, TitleSearch.howFor(TitleSearch.SONY_LIV));
        assertEquals(TitleSearch.APP, TitleSearch.howFor("com.example.unknown"));
    }

    @Test
    public void includedBeatsAdsBeatsRentBeatsBuy() {
        assertTrue(TitleSearch.termsFor("FLATRATE") < TitleSearch.termsFor("ADS"));
        assertTrue(TitleSearch.termsFor("ADS") < TitleSearch.termsFor("RENT"));
        assertTrue(TitleSearch.termsFor("RENT") < TitleSearch.termsFor("BUY"));
    }

    @Test
    public void appleEpisodeLinkBecomesTheShowPage() {
        String episode = "https://tv.apple.com/in/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf"
                + "?at=1000l3V2&ct=app_tvplus&playableId=tvs.sbd.4000%3AVSEVR0560101&showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx";
        assertEquals("https://tv.apple.com/in/show/umc.cmc.1srk2goyh2q2zdxcx605w8vtx", TitleSearch.appleShowUrl(episode));
        String movie = "https://tv.apple.com/in/movie/wolfs/umc.cmc.1x2y";
        assertEquals(movie, TitleSearch.appleShowUrl(movie));
    }

    @Test
    public void posterPathFillsProfileAndFormat() {
        assertEquals("https://images.justwatch.com/poster/301444843/s332/stranger-things.jpg",
                TitleSearch.posterUrl("/poster/301444843/{profile}/stranger-things.{format}"));
        assertEquals("", TitleSearch.posterUrl(""));
    }

    @Test
    public void searchWordsAreEncoded() {
        assertEquals("https://www.youtube.com/results?search_query=india%20vs%20new%20zealand",
                TitleSearch.youtubeSearchUrl("india vs new zealand"));
        assertEquals("https://app.primevideo.com/search?phrase=kota%20factory%20%26%20more",
                TitleSearch.primeSearchUrl("kota factory & more"));
    }

    @Test
    public void theTitleAskedForIsKnownDespitePunctuationAndCase() {
        assertTrue(TitleSearch.matches("Hanuman Ansh", "hanuman ansh"));
        assertTrue(TitleSearch.matches("Hanu-Man", "hanuman"));
        assertTrue(TitleSearch.matches("Kota Factory", "kota factory season 3"));
        assertFalse(TitleSearch.matches("The Legend of Hanuman", "hanuman ansh"));
        assertFalse(TitleSearch.matches("", "anything"));
    }

    @Test
    public void ticketingServicesMeanCinemas() {
        assertTrue(TitleSearch.isCinema("district"));
        assertTrue(TitleSearch.isCinema("cinepolisindia"));
        assertFalse(TitleSearch.isCinema("sonyliv"));
    }
}
