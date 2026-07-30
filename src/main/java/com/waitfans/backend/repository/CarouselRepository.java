package com.waitfans.backend.repository;

import com.waitfans.backend.pojo.CarouselItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class CarouselRepository {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VideoShardCatalog shardCatalog;

    public List<CarouselItem> findEnabled() {
        return jdbcTemplate.query(
                "SELECT `id`, `image_url`, `title`, `theme_color`, `target_url` FROM " +
                        shardCatalog.carouselDatabase() +
                        ".`carousel` WHERE `enabled` = 1 ORDER BY `sort_order`, `id`",
                (resultSet, rowNum) -> new CarouselItem(
                        resultSet.getInt("id"),
                        resultSet.getString("image_url"),
                        resultSet.getString("title"),
                        resultSet.getString("theme_color"),
                        resultSet.getString("target_url")
                )
        );
    }
}
