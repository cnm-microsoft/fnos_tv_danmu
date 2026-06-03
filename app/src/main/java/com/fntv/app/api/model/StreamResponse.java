package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class StreamResponse {

    @SerializedName("direct_link_qualities")
    public List<DirectLinkQuality> directLinkQualities;

    public List<Quality> qualities;

    @SerializedName("video_stream")
    public VideoStreamInfo videoStream;

    /** 直链质量 */
    public static class DirectLinkQuality {
        public int bitrate;
        public String resolution;
        public boolean progressive;
        public String url;
        @SerializedName("is_m3u8")
        public boolean isM3u8;
        @SerializedName("expired_at")
        public long expiredAt;
    }

    /** 质量（可能不含url） */
    public static class Quality {
        public int bitrate;
        public String resolution;
        public boolean progressive;
    }

    /** 视频流信息 */
    public static class VideoStreamInfo {
        public int width;
        public int height;
        public String codec;
        @SerializedName("codec_name")
        public String codecName;
        public String profile;
        public String level;
    }
}
