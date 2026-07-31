-- ============================================================================
-- 将 "Valorant 2026.04.01 - 00.31.33.47.DVR" 标题改为"卡了"，
-- 并在所有 19 个主分区中各复制 5 份。
--
-- 执行方式:
--   mysql -u root -p --default-character-set=utf8mb4 -P 3307 < scripts/duplicate-valorant-video.sql
-- ============================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS `waitfans`.`duplicate_valorant_video`$$

CREATE PROCEDURE `waitfans`.`duplicate_valorant_video`()
main_block: BEGIN
    DECLARE v_original_vid   INT;
    DECLARE v_uid            INT;
    DECLARE v_type           TINYINT;
    DECLARE v_auth           TINYINT;
    DECLARE v_duration       DOUBLE;
    DECLARE v_orig_mc_id     VARCHAR(20);
    DECLARE v_sc_id          VARCHAR(20);
    DECLARE v_tags           VARCHAR(500);
    DECLARE v_descr          VARCHAR(2000);
    DECLARE v_cover_url      VARCHAR(500);
    DECLARE v_video_url      VARCHAR(500);
    DECLARE v_status         TINYINT;
    DECLARE v_upload_date    DATETIME;
    DECLARE v_delete_date    DATETIME;

    DECLARE v_done           INT DEFAULT 0;
    DECLARE v_target_mc_id   VARCHAR(20);
    DECLARE v_copy_idx       INT;
    DECLARE v_new_vid        INT;
    DECLARE v_total_copies   INT DEFAULT 0;

    DECLARE category_cursor CURSOR FOR
        SELECT 'anime'       UNION ALL SELECT 'guochuang'  UNION ALL
        SELECT 'douga'       UNION ALL SELECT 'game'       UNION ALL
        SELECT 'kichiku'     UNION ALL SELECT 'music'      UNION ALL
        SELECT 'dance'       UNION ALL SELECT 'cinephile'  UNION ALL
        SELECT 'ent'         UNION ALL SELECT 'knowledge'  UNION ALL
        SELECT 'tech'        UNION ALL SELECT 'information' UNION ALL
        SELECT 'food'        UNION ALL SELECT 'life'       UNION ALL
        SELECT 'car'         UNION ALL SELECT 'fashion'    UNION ALL
        SELECT 'sports'      UNION ALL SELECT 'animal'     UNION ALL
        SELECT 'virtual';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        SELECT '[ERROR] Script failed, all changes rolled back' AS result;
    END;

    -- Step 1: Find original video
    SELECT
        v.vid, v.uid, v.type, v.auth, v.duration, v.mc_id, v.sc_id,
        v.tags, v.descr, v.cover_url, v.video_url, v.status,
        v.upload_date, v.delete_date
    INTO
        v_original_vid, v_uid, v_type, v_auth, v_duration, v_orig_mc_id, v_sc_id,
        v_tags, v_descr, v_cover_url, v_video_url, v_status,
        v_upload_date, v_delete_date
    FROM `waitfans`.`video` v
    WHERE v.title = 'Valorant 2026.04.01 - 00.31.33.47.DVR'
    LIMIT 1;

    IF v_original_vid IS NULL THEN
        SELECT '[WARN] Video not found: Valorant 2026.04.01 - 00.31.33.47.DVR' AS result;
        LEAVE main_block;
    END IF;

    SELECT CONCAT('[OK] Found video vid=', v_original_vid, ' mc_id=', v_orig_mc_id) AS step1;

    -- Step 2: Rename original video
    SET @sql_update_title = CONCAT(
        'UPDATE `waitfans_video_', v_orig_mc_id, '`.`video` ',
        'SET `title` = ', QUOTE('卡了'), ' WHERE `vid` = ?'
    );
    SET @orig_vid = v_original_vid;
    PREPARE stmt_update FROM @sql_update_title;
    EXECUTE stmt_update USING @orig_vid;
    DEALLOCATE PREPARE stmt_update;

    SELECT CONCAT('[OK] Title changed, partition: waitfans_video_', v_orig_mc_id) AS step2;

    -- Step 3: Duplicate into every partition (5 copies each)
    START TRANSACTION;

    SET @uid         = v_uid;
    SET @type        = v_type;
    SET @auth        = v_auth;
    SET @duration    = v_duration;
    SET @sc_id       = v_sc_id;
    SET @tags        = v_tags;
    SET @descr       = v_descr;
    SET @cover_url   = v_cover_url;
    SET @video_url   = v_video_url;
    SET @status      = v_status;
    SET @upload_date = v_upload_date;
    SET @delete_date = v_delete_date;

    OPEN category_cursor;

    category_loop: LOOP
        FETCH category_cursor INTO v_target_mc_id;
        IF v_done THEN
            LEAVE category_loop;
        END IF;

        SET @target_mc_id = v_target_mc_id;
        SET v_copy_idx = 0;

        WHILE v_copy_idx < 5 DO
            INSERT INTO `waitfans`.`video_locator`
                (`mc_id`, `uid`, `status`, `upload_date`, `delete_date`)
            VALUES
                (v_target_mc_id, v_uid, v_status, v_upload_date, v_delete_date);

            SET v_new_vid = LAST_INSERT_ID();
            SET @new_vid = v_new_vid;

            SET @sql_insert_video = CONCAT(
                'INSERT INTO `waitfans_video_', v_target_mc_id, '`.`video` ',
                '(`vid`, `uid`, `title`, `type`, `auth`, `duration`, ',
                '`mc_id`, `sc_id`, `tags`, `descr`, `cover_url`, `video_url`, ',
                '`status`, `upload_date`, `delete_date`) ',
                'VALUES (?, ?, ', QUOTE('卡了'), ', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
            );
            PREPARE stmt_video FROM @sql_insert_video;
            EXECUTE stmt_video USING
                @new_vid, @uid, @type, @auth, @duration,
                @target_mc_id, @sc_id, @tags, @descr, @cover_url, @video_url,
                @status, @upload_date, @delete_date;
            DEALLOCATE PREPARE stmt_video;

            SET @sql_insert_stats = CONCAT(
                'INSERT INTO `waitfans_video_', v_target_mc_id, '`.`video_stats` ',
                '(`vid`, `play`, `danmu`, `good`, `bad`, `coin`, `collect`, `share`, `comment`) ',
                'VALUES (?, 0, 0, 0, 0, 0, 0, 0, 0)'
            );
            PREPARE stmt_stats FROM @sql_insert_stats;
            EXECUTE stmt_stats USING @new_vid;
            DEALLOCATE PREPARE stmt_stats;

            SET v_total_copies = v_total_copies + 1;
            SET v_copy_idx = v_copy_idx + 1;
        END WHILE;

    END LOOP category_loop;

    CLOSE category_cursor;
    COMMIT;

    SELECT CONCAT('[DONE] ', v_total_copies, ' copies created across ',
                  v_total_copies / 5, ' partitions') AS result;

END main_block$$

DELIMITER ;

CALL `waitfans`.`duplicate_valorant_video`();

-- Verification
SELECT '--- Verification: count per partition (expect >= 5 each, >= 6 in original) ---' AS verification;

SELECT
    `mc_id`          AS 'partition',
    COUNT(*)         AS 'count'
FROM `waitfans`.`video`
WHERE `title` = '卡了'
GROUP BY `mc_id`
ORDER BY `mc_id`;

SELECT CONCAT('Total: ', COUNT(*), ' videos titled with target name') AS summary
FROM `waitfans`.`video`
WHERE `title` = '卡了';
