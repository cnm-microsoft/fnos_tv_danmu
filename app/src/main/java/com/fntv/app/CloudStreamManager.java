package com.fntv.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.StreamResponse;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Response;

/** 云直链管理 — Stream API 请求、质量切换、播放模式管理 */
public class CloudStreamManager {

    public interface Callback {
        String getBaseUrl();
        String getMediaGuid();
        FnApiManager getApiManager();
        Context getContext();
        SharedPreferences getPrefs();
        void onStreamInfoParsed(StreamInfo info);
        void onStreamDataFailed();
        void startPlayback();
        void reloadPlayback();
        void onTrackChanged();
        void probeWithMediaExtractor();
        void onCloudBtnVisibilityChanged(boolean vis);
        void runOnUiThread(Runnable r);
    }

    /** Stream API 解析出的音视频元数据（传给 Activity 更新信息面板） */
    public static class StreamInfo {
        public int bitrate, width, height, bitDepth, duration;
        public long fileSize;
        public String vCodec, vProfile, vPixFmt, vColor, vFps, container;
        public String resolution;
        public boolean vHdr;
        public List<StreamResponse.AudioStreamInfo> audioTracks;
        public List<StreamResponse.SubtitleStreamInfo> subtitleTracks;
    }

    /** 播放配置（getPlaybackConfig 返回） */
    public static class PlaybackConfig {
        public final String url;
        public final boolean hls;
        public final int chunkedModeSize;
        public final String userAgent;

        public PlaybackConfig(String url, boolean hls, int chunkedModeSize, String userAgent) {
            this.url = url;
            this.hls = hls;
            this.chunkedModeSize = chunkedModeSize;
            this.userAgent = userAgent;
        }
    }

    private final Callback cb;
    private final Button btnCloudMode;
    private final SharedPreferences prefs;

    // 云直链状态
    private boolean cloudDirectMode = true;
    private String cloudDirectUrl = "";
    private boolean isStrmFile = false;
    private int qualityIndex = 1;
    private String[] qualityLabels;
    private String[] qualityUrls;
    private int qualityCount = 0;

    // 网盘直链专用 UA（如 pan.baidu.com，由服务端下发）
    private String cloudUserAgent = "";

    // Stream API 返回的音轨/字幕信息（供对话框显示标签用）
    private List<StreamResponse.AudioStreamInfo> streamAudioTracks;
    private List<StreamResponse.SubtitleStreamInfo> streamSubtitleTracks;

    // 用户最后一次选择的音轨/字幕标签（供信息面板显示）
    private String lastAudioTrackLabel = "";
    private String lastSubtitleTrackLabel = "";

    private static final String TAG = "Player";

    public CloudStreamManager(Callback cb, Button btnCloudMode, SharedPreferences prefs) {
        this.cb = cb;
        this.btnCloudMode = btnCloudMode;
        this.prefs = prefs;
    }

    // ========== 外部调用 ==========

    /** 从 SharedPreferences 恢复初始状态（onCreate 时调用） */
    public void initFromPrefs() {
        cloudDirectMode = prefs.getBoolean("cloud_direct_mode", true);
        qualityIndex = prefs.getInt("cloud_quality_index", 1);
        updateCloudBtnText();
        btnCloudMode.setOnClickListener(v -> showQualityMenu());
    }

    /** 获取当前播放配置（startPlayback 中调用） */
    public PlaybackConfig getPlaybackConfig(String baseUrl, String mediaGuid) {
        String url;
        // 0 = 不分块，全文件流式加载，适合内网大文件
        int chunkedMode = 0;
        if (!cloudDirectUrl.isEmpty()) {
            url = cloudDirectUrl;
            chunkedMode = isStrmFile ? 0 : 20 * 1024 * 1024;
            Log.d(TAG, "播放模式: 直链 " + url);
        } else if (cloudDirectMode && qualityCount > 0) {
            url = baseUrl + "/v/api/v1/media/range/" + mediaGuid + "?direct_link_quality_index=" + qualityIndex;
            Log.d(TAG, "播放模式: 直链 (NAS代理, index=" + qualityIndex + ") " + url);
        } else {
            url = baseUrl + "/v/api/v1/media/range/" + mediaGuid;
            Log.d(TAG, "播放模式: 代理 " + url);
        }
        boolean hls = url.contains(".m3u8");
        if (!hls && !cloudDirectUrl.isEmpty()) {
            hls = isStrmFile;
            Log.d(TAG, "直链模式: isStrm=" + isStrmFile + " useHls=" + hls);
        }
        return new PlaybackConfig(url, hls, chunkedMode, cloudUserAgent);
    }

    /** 获取当前直链 URL（用于 switchMediaSource 等） */
    public String getCloudDirectUrl() { return cloudDirectUrl; }

    /** 是否处于直链播放模式 */
    public boolean hasDirectUrl() { return !cloudDirectUrl.isEmpty(); }

    /** 获取用户最后选择的音轨标签（供信息面板展示） */
    public String getLastAudioTrackLabel() { return lastAudioTrackLabel; }
    /** 获取用户最后选择的字幕标签（供信息面板展示） */
    public String getLastSubtitleTrackLabel() { return lastSubtitleTrackLabel; }

    // ========== Stream API ==========

    /** 调用 stream API 获取直链并解析（从 loadPlayInfo 回调中调用） */
    public void fetchDirectLink(final String itemGuid, final String mediaGuid) {
        if (mediaGuid == null) {
            cb.onStreamDataFailed();
            return;
        }
        Map<String, Object> streamReq = new HashMap<>();
        Map<String, Object> header = new HashMap<>();
        header.put("User-Agent", new String[]{"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"});
        streamReq.put("header", header);
        streamReq.put("level", 1);
        streamReq.put("media_guid", mediaGuid);
        // ip = 账号的 MD5 哈希
        String account = prefs.getString("user", "video");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(account.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            streamReq.put("ip", sb.toString());
        } catch (Exception e) {
            streamReq.put("ip", "");
        }
        streamReq.put("nonce", String.valueOf(100000 + (int) (Math.random() * 900000)));
        String reqJson = new Gson().toJson(streamReq);
        Log.d(TAG, "getStream 请求体: " + reqJson);
        Log.d(TAG, "getStream 请求URL: " + cb.getBaseUrl() + "/v/api/v1/stream");

        cb.getApiManager().getApi().getStream(streamReq)
                .enqueue(new retrofit2.Callback<ApiResponse<StreamResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<StreamResponse>> call,
                                           Response<ApiResponse<StreamResponse>> r) {
                        try {
                            Log.d(TAG, "getStream resp code=" + r.code());
                            if (r.isSuccessful() && r.body() != null && r.body().code == 0
                                    && r.body().data != null) {
                                StreamResponse sd = r.body().data;
                                StreamInfo info = new StreamInfo();

                                // [诊断] 打印 header 原始内容，确认服务端是否下发网盘 Cookie 及字段名
                                try {
                                    String headerJson = new Gson().toJson(sd.header);
                                    Log.w(TAG, "[诊断] sd.header 原始=" + headerJson);
                                } catch (Exception ignored) {}

                                // 视频流
                                if (sd.videoStream != null) {
                                    info.bitrate = sd.videoStream.bps;
                                    info.vCodec = sd.videoStream.codecName != null ? sd.videoStream.codecName : "";
                                    info.vProfile = sd.videoStream.profile != null ? sd.videoStream.profile : "";
                                    info.width = sd.videoStream.width;
                                    info.height = sd.videoStream.height;
                                    info.bitDepth = sd.videoStream.bitDepth;
                                    info.vHdr = sd.videoStream.dvProfile > 0;
                                    info.vPixFmt = sd.videoStream.pixFmt != null ? sd.videoStream.pixFmt : "";
                                    info.vColor = sd.videoStream.colorPrimaries != null ? sd.videoStream.colorPrimaries : "";
                                    String cs = sd.videoStream.colorSpace != null ? sd.videoStream.colorSpace : "";
                                    if (!info.vColor.isEmpty() && !cs.isEmpty()) info.vColor += " " + cs;
                                    info.vFps = sd.videoStream.rFrameRate != null ? sd.videoStream.rFrameRate : "";
                                    info.duration = sd.videoStream.duration;
                                }

                                // 文件信息
                                boolean isStrm = false;
                                if (sd.fileStream != null) {
                                    info.fileSize = sd.fileStream.size;
                                    String fn = sd.fileStream.fileName != null ? sd.fileStream.fileName : "";
                                    String fp = sd.fileStream.path != null ? sd.fileStream.path : "";
                                    if (fn.contains("."))
                                        info.container = fn.substring(fn.lastIndexOf('.') + 1).toLowerCase();
                                    if (info.duration <= 0) info.duration = sd.fileStream.duration;
                                    isStrm = fp.toLowerCase().endsWith(".strm") || fn.toLowerCase().endsWith(".strm");
                                }

                                // STRM 处理
                                if (isStrm && sd.directLinkQualities != null && !sd.directLinkQualities.isEmpty()) {
                                    isStrmFile = true;
                                    String directUrl = sd.directLinkQualities.get(0).url;
                                    directUrl = directUrl.replace("\\u0026", "&");
                                    cloudDirectUrl = directUrl;
                                    cloudDirectMode = true;
                                    qualityCount = sd.directLinkQualities.size();
                                    qualityLabels = new String[qualityCount];
                                    qualityUrls = new String[qualityCount];
                                    for (int qi = 0; qi < qualityCount; qi++) {
                                        StreamResponse.DirectLinkQuality dlq = sd.directLinkQualities.get(qi);
                                        qualityLabels[qi] = dlq.resolution != null && !dlq.resolution.isEmpty()
                                                ? dlq.resolution : ("画质" + qi);
                                        String u = dlq.url != null ? dlq.url.replace("\\u0026", "&") : "";
                                        qualityUrls[qi] = u;
                                    }
                                    if (qualityIndex >= qualityCount) qualityIndex = 0;
                                    cloudDirectUrl = qualityUrls[qualityIndex];
                                    Log.d(TAG, "STRM 文件，使用直链: " + cloudDirectUrl);
                                    cb.runOnUiThread(() -> {
                                        setCloudBtnVisible(true);
                                        updateCloudBtnText();
                                    });
                                }

                                // 音频/字幕流（保存本地副本供音轨切换用）
                                streamAudioTracks = sd.audioStreams;
                                streamSubtitleTracks = sd.subtitleStreams;
                                info.audioTracks = sd.audioStreams;
                                info.subtitleTracks = sd.subtitleStreams;

                                // 非 STRM 的画质信息（直连网盘）
                                qualityCount = sd.directLinkQualities != null ? sd.directLinkQualities.size() : 0;
                                if (qualityCount > 0) {
                                    // [诊断] 网盘鉴权 Cookie：确认 sd.header.Cookie 是否下发
                                    int cookieCount = (sd.header != null && sd.header.Cookie != null)
                                            ? sd.header.Cookie.size() : 0;
                                    Log.w(TAG, "[诊断] 非 STRM 直链: qualityCount=" + qualityCount
                                            + " header.Cookie 条数=" + cookieCount);
                                    for (int qi = 0; qi < qualityCount; qi++) {
                                        StreamResponse.DirectLinkQuality q = sd.directLinkQualities.get(qi);
                                        Log.w(TAG, "[诊断]   q[" + qi + "] res=" + q.resolution
                                                + " url=" + (q.url != null ? q.url : "null")
                                                + " expiredAt=" + q.expiredAt
                                                + " isM3u8=" + q.isM3u8);
                                    }

                                    // 读取服务端下发的网盘专用 UA（如 pan.baidu.com，百度大文件必须此 UA 才能直连）
                                    String serverUA = "";
                                    if (sd.header != null && sd.header.UserAgent != null && !sd.header.UserAgent.isEmpty()) {
                                        serverUA = sd.header.UserAgent.get(0);
                                        Log.d(TAG, "网盘直链专用 UA: " + serverUA);
                                    }

                                    qualityLabels = new String[qualityCount];
                                    qualityUrls = new String[qualityCount];
                                    for (int qi = 0; qi < qualityCount; qi++) {
                                        StreamResponse.DirectLinkQuality q = sd.directLinkQualities.get(qi);
                                        qualityLabels[qi] = q.resolution != null && !q.resolution.isEmpty()
                                                ? q.resolution : ("画质" + qi);
                                        qualityUrls[qi] = q.url != null ? q.url.replace("\\u0026", "&") : "";
                                    }
                                    if (qualityIndex >= qualityCount) qualityIndex = 0;
                                    cloudDirectUrl = qualityUrls[qualityIndex];
                                    cloudDirectMode = true;

                                    // 注入网盘专用 UA，让直链请求使用正确的 User-Agent
                                    cloudUserAgent = serverUA;
                                    Log.d(TAG, "非 STRM 直链模式: " + cloudDirectUrl.substring(0, Math.min(80, cloudDirectUrl.length())) + "... UA=" + serverUA);

                                    cb.runOnUiThread(() -> {
                                        setCloudBtnVisible(true);
                                        updateCloudBtnText();
                                    });
                                }

                                // 取 resolution：直链优先，代理取 qualities[0]
                                if (sd.directLinkQualities != null && qualityIndex < sd.directLinkQualities.size()) {
                                    info.resolution = sd.directLinkQualities.get(qualityIndex).resolution;
                                    if (sd.directLinkQualities.get(qualityIndex).bitrate > 0)
                                        info.bitrate = sd.directLinkQualities.get(qualityIndex).bitrate;
                                } else if (sd.qualities != null && !sd.qualities.isEmpty()) {
                                    info.resolution = sd.qualities.get(0).resolution;
                                    info.bitrate = sd.qualities.get(0).bitrate;
                                }
                                // 回调 Activity 更新显示信息
                                cb.onStreamInfoParsed(info);
                            } else {
                                cb.onStreamDataFailed();
                                return;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "getStream parse error", e);
                            cb.onStreamDataFailed();
                            return;
                        }
                        cb.startPlayback(); // 无论成功/失败都尝试播放
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<StreamResponse>> call, Throwable t) {
                        Log.e(TAG, "getStream onFailure: " + t.getMessage());
                        cb.onStreamDataFailed();
                    }
                });
    }

    /** 重新加载（切换清晰度时调用，重置云直链状态） */
    public void resetForQualitySwitch() {
        cloudDirectUrl = "";
        isStrmFile = false;
        qualityCount = 0;
        qualityLabels = null;
        qualityUrls = null;
        cloudUserAgent = "";
    }

    // ========== 画质菜单 ==========

    /** 显示画质/模式切换菜单 */
    public void showQualityMenu() {
        final SharedPreferences sp = cb.getPrefs();
        if (isStrmFile && qualityCount > 0 && qualityLabels != null) {
            // STRM 文件：只显示画质
            final String[] items = new String[qualityCount];
            for (int i = 0; i < qualityCount; i++) {
                items[i] = (i == qualityIndex ? "✓ " : "  ") + qualityLabels[i];
            }
            new android.app.AlertDialog.Builder(cb.getContext())
                    .setTitle("画质选择")
                    .setItems(items, (dialog, which) -> {
                        if (which < qualityCount) {
                            qualityIndex = which;
                            sp.edit().putInt("cloud_quality_index", qualityIndex).apply();
                            updateCloudBtnText();
                            dialog.dismiss();
                            Toast.makeText(cb.getContext(), "切换画质：" + qualityLabels[qualityIndex], Toast.LENGTH_SHORT).show();
                            cb.runOnUiThread(() -> reloadPlayback());
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } else if (cloudDirectMode && qualityCount > 0 && qualityLabels != null) {
            // 直链模式：画质列表 + 切换代理
            final String[] items = new String[qualityCount + 1];
            for (int i = 0; i < qualityCount; i++) {
                items[i] = (i == qualityIndex ? "✓ " : "  ") + qualityLabels[i];
            }
            items[qualityCount] = "切换到代理模式";
            new android.app.AlertDialog.Builder(cb.getContext())
                    .setTitle("播放设置")
                    .setItems(items, (dialog, which) -> {
                        if (which < qualityCount) {
                            qualityIndex = which;
                            sp.edit().putInt("cloud_quality_index", qualityIndex)
                                    .putBoolean("cloud_direct_mode", true).apply();
                            updateCloudBtnText();
                            dialog.dismiss();
                            Toast.makeText(cb.getContext(), "切换画质：" + qualityLabels[qualityIndex], Toast.LENGTH_SHORT).show();
                            cb.runOnUiThread(() -> reloadPlayback());
                        } else {
                            cloudDirectMode = false;
                            sp.edit().putBoolean("cloud_direct_mode", false).apply();
                            updateCloudBtnText();
                            dialog.dismiss();
                            Toast.makeText(cb.getContext(), "已切换为代理模式", Toast.LENGTH_SHORT).show();
                            cb.runOnUiThread(() -> reloadPlayback());
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } else {
            // 代理模式：切换到直链
            new android.app.AlertDialog.Builder(cb.getContext())
                    .setTitle("播放设置")
                    .setItems(new String[]{"切换到直链模式"}, (dialog, which) -> {
                        cloudDirectMode = true;
                        sp.edit().putBoolean("cloud_direct_mode", true).apply();
                        updateCloudBtnText();
                        dialog.dismiss();
                        Toast.makeText(cb.getContext(), "已切换为直链模式", Toast.LENGTH_SHORT).show();
                        cb.runOnUiThread(() -> reloadPlayback());
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        }
    }

    // ========== UI 按钮 ==========

    public void setCloudBtnVisible(boolean vis) {
        btnCloudMode.setVisibility(vis ? View.VISIBLE : View.GONE);
        cb.onCloudBtnVisibilityChanged(vis);
    }

    public void updateCloudBtnText() {
        String mode = isStrmFile ? "STRM" : (cloudDirectMode ? "直链" : "代理");
        String ql = qualityCount > 0 && qualityIndex < qualityCount && qualityLabels != null
                ? qualityLabels[qualityIndex] : "";
        btnCloudMode.setText(ql.isEmpty() ? mode : mode + "/" + ql);
        btnCloudMode.setTextColor(isStrmFile || cloudDirectMode ? 0xFF81C784 : 0xFFFFB74D);
    }

    // ========== 内部 ==========

    private void reloadPlayback() {
        resetForQualitySwitch();
        cb.reloadPlayback(); // triggers loadPlayInfo again
    }
}




