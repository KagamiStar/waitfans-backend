-- Waitfans logical database split.
-- Run after database/waitfans.sql. The legacy video tables are migrated into
-- one database per main category and replaced with read-only UNION ALL views.

CREATE DATABASE IF NOT EXISTS `waitfans_carousel`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `waitfans_carousel`.`carousel` (
    `id` int NOT NULL AUTO_INCREMENT,
    `image_url` varchar(500) NOT NULL,
    `title` varchar(100) NOT NULL,
    `theme_color` varchar(20) NOT NULL DEFAULT '#334b61',
    `target_url` varchar(500) NOT NULL DEFAULT '/',
    `sort_order` int NOT NULL DEFAULT 0,
    `enabled` tinyint NOT NULL DEFAULT 1,
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_carousel_enabled_sort` (`enabled`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Homepage carousel data; deliberately isolated from business data';

INSERT INTO `waitfans_carousel`.`carousel`
    (`id`, `image_url`, `title`, `theme_color`, `target_url`, `sort_order`, `enabled`)
VALUES
    (1, '/assets/bilibili-home/25730189dbdc345f.avif', '欢迎来到 Waitfans', '#52562d', '/', 10, 1),
    (2, '/assets/bilibili-home/1ee5e6839982ef47.avif', '发现值得分享的视频', '#4f82b8', '/', 20, 1),
    (3, '/assets/bilibili-home/33e4812a979891c9.avif', '记录每一个精彩瞬间', '#2b2230', '/', 30, 1),
    (4, '/assets/bilibili-home/371cadd4ec071563.avif', '在分区中探索更多内容', '#285b72', '/', 40, 1),
    (5, '/assets/bilibili-home/53557e1f70f25688.avif', '创作、交流、共同成长', '#6f4072', '/', 50, 1)
ON DUPLICATE KEY UPDATE
    `image_url` = VALUES(`image_url`),
    `title` = VALUES(`title`),
    `theme_color` = VALUES(`theme_color`),
    `target_url` = VALUES(`target_url`),
    `sort_order` = VALUES(`sort_order`),
    `enabled` = VALUES(`enabled`);

CREATE TABLE `waitfans`.`video_locator` (
    `vid` int NOT NULL AUTO_INCREMENT,
    `mc_id` varchar(20) NOT NULL,
    `uid` int NOT NULL,
    `status` tinyint NOT NULL DEFAULT 0,
    `upload_date` datetime NOT NULL,
    `delete_date` datetime DEFAULT NULL,
    PRIMARY KEY (`vid`),
    KEY `idx_video_locator_category` (`mc_id`, `vid`),
    KEY `idx_video_locator_status` (`status`, `vid`),
    KEY `idx_video_locator_user` (`uid`, `vid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Global video ID and shard routing index; no video content is stored here';

INSERT INTO `waitfans`.`video_locator`
    (`vid`, `mc_id`, `uid`, `status`, `upload_date`, `delete_date`)
SELECT `vid`, `mc_id`, `uid`, `status`, `upload_date`, `delete_date`
FROM `waitfans`.`video`;

RENAME TABLE
    `waitfans`.`video` TO `waitfans`.`video_legacy`,
    `waitfans`.`video_stats` TO `waitfans`.`video_stats_legacy`;

DELIMITER $$
CREATE PROCEDURE `waitfans`.`create_video_shard`(
    IN shard_database varchar(64),
    IN category_id varchar(20)
)
BEGIN
    SET @create_database_sql = CONCAT(
        'CREATE DATABASE IF NOT EXISTS `', shard_database,
        '` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'
    );
    PREPARE statement_handle FROM @create_database_sql;
    EXECUTE statement_handle;
    DEALLOCATE PREPARE statement_handle;

    SET @create_video_sql = CONCAT(
        'CREATE TABLE `', shard_database, '`.`video` LIKE `waitfans`.`video_legacy`'
    );
    PREPARE statement_handle FROM @create_video_sql;
    EXECUTE statement_handle;
    DEALLOCATE PREPARE statement_handle;

    SET @create_stats_sql = CONCAT(
        'CREATE TABLE `', shard_database, '`.`video_stats` LIKE `waitfans`.`video_stats_legacy`'
    );
    PREPARE statement_handle FROM @create_stats_sql;
    EXECUTE statement_handle;
    DEALLOCATE PREPARE statement_handle;

    SET @copy_video_sql = CONCAT(
        'INSERT INTO `', shard_database, '`.`video` ',
        'SELECT * FROM `waitfans`.`video_legacy` WHERE `mc_id` = ?'
    );
    SET @category_parameter = category_id;
    PREPARE statement_handle FROM @copy_video_sql;
    EXECUTE statement_handle USING @category_parameter;
    DEALLOCATE PREPARE statement_handle;

    SET @copy_stats_sql = CONCAT(
        'INSERT INTO `', shard_database, '`.`video_stats` ',
        'SELECT stats.* FROM `waitfans`.`video_stats_legacy` stats ',
        'INNER JOIN `waitfans`.`video_legacy` video ON video.`vid` = stats.`vid` ',
        'WHERE video.`mc_id` = ?'
    );
    PREPARE statement_handle FROM @copy_stats_sql;
    EXECUTE statement_handle USING @category_parameter;
    DEALLOCATE PREPARE statement_handle;
END$$
DELIMITER ;

CALL `waitfans`.`create_video_shard`('waitfans_video_anime', 'anime');
CALL `waitfans`.`create_video_shard`('waitfans_video_guochuang', 'guochuang');
CALL `waitfans`.`create_video_shard`('waitfans_video_douga', 'douga');
CALL `waitfans`.`create_video_shard`('waitfans_video_game', 'game');
CALL `waitfans`.`create_video_shard`('waitfans_video_kichiku', 'kichiku');
CALL `waitfans`.`create_video_shard`('waitfans_video_music', 'music');
CALL `waitfans`.`create_video_shard`('waitfans_video_dance', 'dance');
CALL `waitfans`.`create_video_shard`('waitfans_video_cinephile', 'cinephile');
CALL `waitfans`.`create_video_shard`('waitfans_video_ent', 'ent');
CALL `waitfans`.`create_video_shard`('waitfans_video_knowledge', 'knowledge');
CALL `waitfans`.`create_video_shard`('waitfans_video_tech', 'tech');
CALL `waitfans`.`create_video_shard`('waitfans_video_information', 'information');
CALL `waitfans`.`create_video_shard`('waitfans_video_food', 'food');
CALL `waitfans`.`create_video_shard`('waitfans_video_life', 'life');
CALL `waitfans`.`create_video_shard`('waitfans_video_car', 'car');
CALL `waitfans`.`create_video_shard`('waitfans_video_fashion', 'fashion');
CALL `waitfans`.`create_video_shard`('waitfans_video_sports', 'sports');
CALL `waitfans`.`create_video_shard`('waitfans_video_animal', 'animal');
CALL `waitfans`.`create_video_shard`('waitfans_video_virtual', 'virtual');

DROP PROCEDURE `waitfans`.`create_video_shard`;

CREATE SQL SECURITY INVOKER VIEW `waitfans`.`video` AS
    SELECT * FROM `waitfans_video_anime`.`video`
    UNION ALL SELECT * FROM `waitfans_video_guochuang`.`video`
    UNION ALL SELECT * FROM `waitfans_video_douga`.`video`
    UNION ALL SELECT * FROM `waitfans_video_game`.`video`
    UNION ALL SELECT * FROM `waitfans_video_kichiku`.`video`
    UNION ALL SELECT * FROM `waitfans_video_music`.`video`
    UNION ALL SELECT * FROM `waitfans_video_dance`.`video`
    UNION ALL SELECT * FROM `waitfans_video_cinephile`.`video`
    UNION ALL SELECT * FROM `waitfans_video_ent`.`video`
    UNION ALL SELECT * FROM `waitfans_video_knowledge`.`video`
    UNION ALL SELECT * FROM `waitfans_video_tech`.`video`
    UNION ALL SELECT * FROM `waitfans_video_information`.`video`
    UNION ALL SELECT * FROM `waitfans_video_food`.`video`
    UNION ALL SELECT * FROM `waitfans_video_life`.`video`
    UNION ALL SELECT * FROM `waitfans_video_car`.`video`
    UNION ALL SELECT * FROM `waitfans_video_fashion`.`video`
    UNION ALL SELECT * FROM `waitfans_video_sports`.`video`
    UNION ALL SELECT * FROM `waitfans_video_animal`.`video`
    UNION ALL SELECT * FROM `waitfans_video_virtual`.`video`;

CREATE SQL SECURITY INVOKER VIEW `waitfans`.`video_stats` AS
    SELECT * FROM `waitfans_video_anime`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_guochuang`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_douga`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_game`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_kichiku`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_music`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_dance`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_cinephile`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_ent`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_knowledge`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_tech`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_information`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_food`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_life`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_car`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_fashion`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_sports`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_animal`.`video_stats`
    UNION ALL SELECT * FROM `waitfans_video_virtual`.`video_stats`;

DROP TABLE `waitfans`.`video_stats_legacy`;
DROP TABLE `waitfans`.`video_legacy`;
