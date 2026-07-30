package com.waitfans.backend.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VideoShardCatalogTest {
    @Test
    void mapsKnownCategoryToDedicatedDatabase() {
        VideoShardCatalog catalog = new VideoShardCatalog(
                "waitfans",
                "waitfans_carousel",
                "waitfans_video_"
        );

        assertEquals("`waitfans_video_music`", catalog.videoDatabase("music"));
        assertEquals("`waitfans_carousel`", catalog.carouselDatabase());
    }

    @Test
    void rejectsUnknownCategoryAndUnsafeIdentifiers() {
        VideoShardCatalog catalog = new VideoShardCatalog(
                "waitfans",
                "waitfans_carousel",
                "waitfans_video_"
        );

        assertThrows(IllegalArgumentException.class, () -> catalog.videoDatabase("music;DROP DATABASE waitfans"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VideoShardCatalog("waitfans`", "waitfans_carousel", "waitfans_video_")
        );
    }
}
