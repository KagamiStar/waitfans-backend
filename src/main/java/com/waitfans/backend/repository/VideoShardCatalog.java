package com.waitfans.backend.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class VideoShardCatalog {
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final Set<String> MAIN_CATEGORIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "anime", "guochuang", "douga", "game", "kichiku", "music", "dance",
            "cinephile", "ent", "knowledge", "tech", "information", "food", "life",
            "car", "fashion", "sports", "animal", "virtual"
    )));

    private final String coreDatabase;
    private final String carouselDatabase;
    private final String videoDatabasePrefix;

    public VideoShardCatalog(
            @Value("${waitfans.database.core:waitfans}") String coreDatabase,
            @Value("${waitfans.database.carousel:waitfans_carousel}") String carouselDatabase,
            @Value("${waitfans.database.video-prefix:waitfans_video_}") String videoDatabasePrefix
    ) {
        this.coreDatabase = validateIdentifier(coreDatabase, "core database");
        this.carouselDatabase = validateIdentifier(carouselDatabase, "carousel database");
        this.videoDatabasePrefix = validateIdentifier(videoDatabasePrefix, "video database prefix");
    }

    public String coreDatabase() {
        return quote(coreDatabase);
    }

    public String carouselDatabase() {
        return quote(carouselDatabase);
    }

    public String videoDatabase(String mainCategoryId) {
        if (!MAIN_CATEGORIES.contains(mainCategoryId)) {
            throw new IllegalArgumentException("Unsupported main video category: " + mainCategoryId);
        }
        return quote(videoDatabasePrefix + mainCategoryId);
    }

    public Set<String> mainCategories() {
        return MAIN_CATEGORIES;
    }

    private static String validateIdentifier(String value, String label) {
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
        return value;
    }

    private static String quote(String value) {
        return "`" + value + "`";
    }
}
