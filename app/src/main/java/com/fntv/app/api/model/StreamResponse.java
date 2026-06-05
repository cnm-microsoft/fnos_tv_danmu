package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class StreamResponse {

    @SerializedName("direct_link_qualities")
    public List<DirectLinkQuality> directLinkQualities;

    public List<Quality> qualities;

    @SerializedName("video_stream")
    public VideoStreamInfo videoStream;

    public ResponseHeader header;

    @SerializedName("cloud_storage_info")
    public CloudStorageInfo cloudStorageInfo;

    /** 响应头（含 Cookie） */
    public static class ResponseHeader {
        public List<String> Cookie;
    }

    /** 云存储信息 */
    public static class CloudStorageInfo {
        @SerializedName("cloud_storage_type")
        public int cloudStorageType;
        public boolean valid;
        @SerializedName("cloud_nick_name")
        public String cloudNickName;
        @SerializedName("is_vip")
        public boolean isVip;
        @SerializedName("quark_vip_type")
        public String quarkVipType;
    }

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
