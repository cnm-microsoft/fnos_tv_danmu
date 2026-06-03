package com.fntv.app.model;

import com.google.gson.annotations.SerializedName;

/**
 * 观看记录 - 用于"继续观看"功能
 */
public class WatchRecord {

    public String guid;
    public String title;          // 单集标题
    @SerializedName("tv_title")
    public String tvTitle;        // 系列名称（如"仙逆"）
    @SerializedName("episode_number")
    public int episodeNumber;     // 集数
    public String poster;         // 海报相对路径
    public String libraryName;    // 所属媒体库名
    @SerializedName("parent_guid")
    public String parentGuid;     // 系列/父级GUID（用于加载剧集列表）

    public long ts;               // 播放进度(秒)
    public long duration;         // 总时长(秒)

    @SerializedName("updated_at")
    public long updatedAt;        // 更新时间戳

    public WatchRecord() {}

    public WatchRecord(String guid, String title, String tvTitle, int episodeNumber,
                       String poster, String libraryName, long ts, long duration) {
        this(guid, title, tvTitle, episodeNumber, poster, libraryName, null, ts, duration);
    }

    public WatchRecord(String guid, String title, String tvTitle, int episodeNumber,
                       String poster, String libraryName, String parentGuid, long ts, long duration) {
        this.guid = guid;
        this.title = title;
        this.tvTitle = tvTitle;
        this.episodeNumber = episodeNumber;
        this.poster = poster;
        this.libraryName = libraryName;
        this.parentGuid = parentGuid;
        this.ts = ts;
        this.duration = duration;
        this.updatedAt = System.currentTimeMillis();
    }

    /** 获取显示的标题（含集数） */
    public String getDisplayTitle() {
        if (tvTitle != null && !tvTitle.isEmpty()) {
            if (episodeNumber > 0) {
                return tvTitle + " 第" + episodeNumber + "集";
            }
            return tvTitle;
        }
        return title != null ? title : "";
    }

    /** 获取去重用的 key（同系列只保留最新一条） */
    public String getDedupKey() {
        if (tvTitle != null && !tvTitle.isEmpty()) return tvTitle;
        return title;
    }

    /** 播放进度百分比 (0~100) */
    public int getProgressPercent() {
        if (duration <= 0) return 0;
        return (int) (ts * 100 / duration);
    }

    /** 判断是否基本看完 (>90%) */
    public boolean isNearlyFinished() {
        return getProgressPercent() >= 90;
    }
}
