package com.spotdl.gui.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SpotifyEmbedParserTest {
    @Test public void parsesTrackArtistFromCurrentEmbedPayload() throws Exception {
        String json = "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{" +
                "\"type\":\"track\",\"name\":\"Liomeno Pagoto\",\"artists\":[{\"name\":\"Xilina Spathia\"}]" +
                "}}}}}}";

        SpotifyEmbedParser.Collection result = SpotifyEmbedParser.parseNextData(json, "track");

        assertEquals("Liomeno Pagoto", result.title());
        assertEquals(1, result.tracks().size());
        assertEquals("Xilina Spathia", result.tracks().get(0).artist());
    }

    @Test public void parsesAlbumTrackListFromCurrentEmbedPayload() throws Exception {
        String json = "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{" +
                "\"type\":\"album\",\"title\":\"An Album\",\"subtitle\":\"An Artist\"," +
                "\"trackList\":[{\"title\":\"First\",\"subtitle\":\"An Artist\"}," +
                "{\"title\":\"Second\",\"subtitle\":\"Guest\"}]" +
                "}}}}}}";

        SpotifyEmbedParser.Collection result = SpotifyEmbedParser.parseNextData(json, "album");

        assertEquals("An Album", result.title());
        assertEquals("An Artist", result.artist());
        assertEquals(2, result.tracks().size());
        assertEquals("Second", result.tracks().get(1).title());
        assertEquals("Guest", result.tracks().get(1).artist());
    }
}
