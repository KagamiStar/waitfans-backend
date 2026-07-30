package com.waitfans.backend.repository;

import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.pojo.VideoStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class PartitionedVideoStore {
    private static final Set<String> STATS_COLUMNS = new HashSet<>(Arrays.asList(
            "play", "danmu", "good", "bad", "coin", "collect", "share", "comment"
    ));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VideoShardCatalog shardCatalog;

    @Transactional
    public void insertVideoWithStats(Video video, VideoStats stats) {
        String core = shardCatalog.coreDatabase();
        String shard = shardCatalog.videoDatabase(video.getMcId());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + core + ".`video_locator` " +
                            "(`mc_id`, `uid`, `status`, `upload_date`, `delete_date`) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, video.getMcId());
            statement.setInt(2, video.getUid());
            statement.setInt(3, video.getStatus());
            statement.setTimestamp(4, timestamp(video.getUploadDate()));
            statement.setTimestamp(5, timestamp(video.getDeleteDate()));
            return statement;
        }, keyHolder);
        if (keyHolder.getKey() == null) {
            throw new IllegalStateException("MySQL did not return a global video ID");
        }
        video.setVid(keyHolder.getKey().intValue());
        stats.setVid(video.getVid());

        jdbcTemplate.update(
                "INSERT INTO " + shard + ".`video` " +
                        "(`vid`, `uid`, `title`, `type`, `auth`, `duration`, `mc_id`, `sc_id`, " +
                        "`tags`, `descr`, `cover_url`, `video_url`, `status`, `upload_date`, `delete_date`) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                video.getVid(), video.getUid(), video.getTitle(), video.getType(), video.getAuth(),
                video.getDuration(), video.getMcId(), video.getScId(), video.getTags(), video.getDescr(),
                video.getCoverUrl(), video.getVideoUrl(), video.getStatus(),
                timestamp(video.getUploadDate()), timestamp(video.getDeleteDate())
        );
        jdbcTemplate.update(
                "INSERT INTO " + shard + ".`video_stats` " +
                        "(`vid`, `play`, `danmu`, `good`, `bad`, `coin`, `collect`, `share`, `comment`) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stats.getVid(), stats.getPlay(), stats.getDanmu(), stats.getGood(), stats.getBad(),
                stats.getCoin(), stats.getCollect(), stats.getShare(), stats.getComment()
        );
    }

    @Transactional
    public int updateStatus(Integer vid, Integer status, Date eventTime) {
        String mainCategory = locateMainCategory(vid);
        String shard = shardCatalog.videoDatabase(mainCategory);
        String core = shardCatalog.coreDatabase();
        String dateColumn = status == 1 ? ", `upload_date` = ?" : status == 3 ? ", `delete_date` = ?" : "";
        Object[] shardArguments = dateColumn.isEmpty()
                ? new Object[]{status, vid}
                : new Object[]{status, timestamp(eventTime), vid};
        int updated = jdbcTemplate.update(
                "UPDATE " + shard + ".`video` SET `status` = ?" + dateColumn + " WHERE `vid` = ?",
                shardArguments
        );
        if (updated > 0) {
            String locatorDateColumn = status == 1
                    ? ", `upload_date` = ?"
                    : status == 3 ? ", `delete_date` = ?" : "";
            Object[] locatorArguments = locatorDateColumn.isEmpty()
                    ? new Object[]{status, vid}
                    : new Object[]{status, timestamp(eventTime), vid};
            jdbcTemplate.update(
                    "UPDATE " + core + ".`video_locator` SET `status` = ?" +
                            locatorDateColumn + " WHERE `vid` = ?",
                    locatorArguments
            );
        }
        return updated;
    }

    @Transactional
    public void updateStats(Integer vid, String column, boolean increase, Integer count) {
        requireStatsColumn(column);
        if (count == null || count < 0) {
            throw new IllegalArgumentException("Stats delta must be non-negative");
        }
        String shard = shardCatalog.videoDatabase(locateMainCategory(vid));
        String expression = increase
                ? "`" + column + "` = `" + column + "` + ?"
                : "`" + column + "` = GREATEST(0, `" + column + "` - ?)";
        jdbcTemplate.update(
                "UPDATE " + shard + ".`video_stats` SET " + expression + " WHERE `vid` = ?",
                count, vid
        );
    }

    @Transactional
    public void updateGoodAndBad(Integer vid, boolean addGood) {
        String shard = shardCatalog.videoDatabase(locateMainCategory(vid));
        String expression = addGood
                ? "`good` = `good` + 1, `bad` = GREATEST(0, `bad` - 1)"
                : "`bad` = `bad` + 1, `good` = GREATEST(0, `good` - 1)";
        jdbcTemplate.update(
                "UPDATE " + shard + ".`video_stats` SET " + expression + " WHERE `vid` = ?",
                vid
        );
    }

    public String locateMainCategory(Integer vid) {
        List<String> categories = jdbcTemplate.query(
                "SELECT `mc_id` FROM " + shardCatalog.coreDatabase() +
                        ".`video_locator` WHERE `vid` = ?",
                (resultSet, rowNum) -> resultSet.getString(1),
                vid
        );
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("Video does not exist: " + vid);
        }
        return categories.get(0);
    }

    private static void requireStatsColumn(String column) {
        if (!STATS_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("Unsupported video stats column: " + column);
        }
    }

    private static Timestamp timestamp(Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }
}
