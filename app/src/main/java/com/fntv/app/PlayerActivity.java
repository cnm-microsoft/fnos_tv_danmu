package com.fntv.app;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import android.content.pm.ActivityInfo;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.*;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.CaptionStyleCompat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerActivity extends AppCompatActivity {

    private PlayerView playerView;
    private SimpleExoPlayer player;
    private TextView tvBuffering, tvTime, infoText;
    private SeekBar seekBar;
    private Button btnPlayPause, btnRewind, btnForward, btnSpeed, btnRatio, btnInfo, btnCloseInfo, btnEpisodeList, btnNextEp, btnBack, btnDanmu;
    private ImageView btnLock;
    private TextView tvTitle, tvDanmuStatus, tvDanmuMatch, tvSpeedHint, infoTextAudio, infoTextExtra;
    private Button btnCloudMode, btnBrightness;
    private float speedBeforeLongPress = 1.0f;
    private DanmuView danmuView;
    private View controller, infoPanel, topBar;
    private boolean isLocked = false, danmuOn = false;
    private List<DanmuView.DanmuComment> danmuItems;
    private String danmuUrl = "";

    private Handler handler = new Handler(Looper.getMainLooper());
    private String itemGuid, baseUrl, itemTitle, itemTV, itemPoster, itemCategory, parentGuid;
    private long itemDuration;
    private FnApiManager apiManager;
    private String mediaGuid, videoGuid, audioGuid, subtitleGuid, resolution;
    private boolean seeked = false, ctrlVis = false, infoVis = false, loadingEpisodes = false;
    private long seekTs = 0;
    private float[] speeds = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f};
    private int speedIdx = 0, ratioIdx = 0;
    private boolean isHwDecode = true;
    private List<PlayListItem> episodeList;
    private int currentEpIndex = -1;
    private int seasonNumber = 1;
    private long backPressedTime = 0;
    private String pendingDanmuTitle, pendingDanmuGuid;
    private String danmuMatchedName = "";
    private boolean cloudDirectMode = true;
    private String cloudDirectUrl = "";
    private String playbackUrl = "";
    private boolean isStrmFile = false;
    private boolean useHls = false;
    private int seekStep = 10000;
    private int qualityIndex = 1;
    private int streamBitrate = 0; // bps 来自 stream API
    private String[] qualityLabels;
    private String[] qualityUrls;
    private int qualityCount = 0;
    private static final String TAG = "Player";

    private static final int[] RATIO_MODES = {0, 1, 2};
    private static final String[] RATIO_LABELS = {"适应", "拉伸", "缩放"};
    private String actualVideoDecoder = "";
    private String actualAudioDecoder = "";
    // 流 API 探测数据
    private String streamVCodec = "", streamVProfile = "", streamVPixFmt = "", streamVColor = "", streamVFps = "";
    private int streamVWidth = 0, streamVHeight = 0, streamVBitDepth = 0;
    private boolean streamVHdr = false;
    private long streamFileSize = 0;
    private int streamDuration = 0; // 秒
    private String streamContainer = "";
    private java.util.List<StreamResponse.AudioStreamInfo> streamAudioTracks;
    private java.util.List<StreamResponse.SubtitleStreamInfo> streamSubtitleTracks;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        apiManager = FnApiManager.getInstance();
        SharedPreferences prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        isHwDecode = "hardware".equals(prefs.getString("decoder_mode", "hardware"));

        itemGuid = getIntent().getStringExtra("guid");
        seekTs = getIntent().getLongExtra("ts", 0) * 1000L;
        itemDuration = getIntent().getLongExtra("duration", 0);
        itemTitle = getIntent().getStringExtra("title");
        itemTV = getIntent().getStringExtra("tv_title");
        itemPoster = getIntent().getStringExtra("poster");
        itemCategory = getIntent().getStringExtra("category");
        parentGuid = getIntent().getStringExtra("parent_guid");

        playerView = findViewById(R.id.playerView);
        tvBuffering = findViewById(R.id.tvBuffering);
        tvTime = findViewById(R.id.tvTime);
        seekBar = findViewById(R.id.seekBar);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnRewind = findViewById(R.id.btnRewind);
        btnForward = findViewById(R.id.btnForward);
        btnSpeed = findViewById(R.id.btnSpeed);
        btnRatio = findViewById(R.id.btnRatio);
        btnInfo = findViewById(R.id.btnInfo);
        btnCloseInfo = findViewById(R.id.btnCloseInfo);
        btnEpisodeList = findViewById(R.id.btnEpisodeList);
        btnNextEp = findViewById(R.id.btnNextEp);
        btnBack = findViewById(R.id.btnBack);
        btnDanmu = findViewById(R.id.btnDanmu);
        danmuView = findViewById(R.id.danmuView);

        String savedUrl = prefs.getString("danmu_url", "");
        if (savedUrl.isEmpty()) {
            String host = prefs.getString("host", "");
            host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
            savedUrl = "http://" + host + ":9321";
        }
        danmuUrl = savedUrl;
        btnLock = (ImageView) findViewById(R.id.btnLock);
        tvTitle = findViewById(R.id.tvTitle);
        tvDanmuStatus = findViewById(R.id.tvDanmuStatus);
        btnCloudMode = findViewById(R.id.btnCloudMode);
        tvDanmuMatch = findViewById(R.id.tvDanmuMatch);
        tvSpeedHint = findViewById(R.id.tvSpeedHint);
        if (tvDanmuMatch != null) {
            try {
                java.lang.reflect.Field f = android.widget.TextView.class.getDeclaredField("mMarqueeSpeed");
                f.setAccessible(true);
                f.set(tvDanmuMatch, 2.0f);
            } catch (Exception ignored) {}
        }
        topBar = findViewById(R.id.topBar);
        controller = findViewById(R.id.controller);
        infoPanel = findViewById(R.id.infoPanel);
        infoText = findViewById(R.id.infoText);
        infoTextAudio = findViewById(R.id.infoTextAudio);
        infoTextExtra = findViewById(R.id.infoTextExtra);

        initPlayer();

        boolean savedDanmuOn = prefs.getBoolean("danmu_on", true);
        danmuView.setShowScroll(prefs.getBoolean("danmu_scroll", true));
        danmuView.setShowTop(prefs.getBoolean("danmu_top", true));
        danmuView.setShowBottom(prefs.getBoolean("danmu_bottom", true));
        if (savedDanmuOn) {
            danmuOn = true;
            danmuView.setVisibility(View.VISIBLE);
            btnDanmu.setText("弹");
            danmuView.setAreaPct(prefs.getInt("danmu_area", 35));
            danmuView.setSpeedMul(prefs.getFloat("danmu_speed", 1.0f));
            danmuView.setOpacity(prefs.getFloat("danmu_opacity", 0.85f));
            danmuView.setFontSize(prefs.getFloat("danmu_fontsize", 22f));
            danmuView.setShowOutline(prefs.getBoolean("danmu_outline", true));
            danmuView.setMaxActive(prefs.getInt("danmu_maxactive", 40));
            danmuView.setDensityPct(prefs.getInt("danmu_density", 100));
            danmuView.setRowSpacing(prefs.getFloat("danmu_rowspacing", 1.8f));
        } else {
            danmuView.setVisibility(View.GONE);
            btnDanmu.setText("弹");
        }

        findViewById(android.R.id.content).setOnTouchListener(new View.OnTouchListener() {
            private boolean longPressing = false;
            private android.os.Handler longPressHandler = new android.os.Handler(Looper.getMainLooper());
            @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                if (isLocked) return false;
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        longPressing = false;
                        longPressHandler.postDelayed(() -> {
                            longPressing = true;
                            if (player != null) {
                                speedBeforeLongPress = player.getPlaybackParameters().speed;
                                player.setPlaybackSpeed(2.0f);
                                showCtrl(false);
                                if (tvSpeedHint != null) {
                                    tvSpeedHint.setVisibility(View.VISIBLE);
                                }
                            }
                        }, 500);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        longPressHandler.removeCallbacksAndMessages(null);
                        if (longPressing) {
                            longPressing = false;
                            if (player != null) {
                                player.setPlaybackSpeed(speedBeforeLongPress);
                            }
                            if (tvSpeedHint != null) {
                                tvSpeedHint.setVisibility(View.GONE);
                            }
                            return true;
                        } else {
                            showCtrl(true);
                        }
                        return true;
                }
                return false;
            }
        });
        btnPlayPause.setOnClickListener(v -> togglePlay());
        seekStep = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getInt("seek_step", 10) * 1000;
        btnRewind.setOnClickListener(v -> seekRel(-seekStep));
        btnForward.setOnClickListener(v -> seekRel(seekStep));
        btnRewind.setText("-" + (seekStep / 1000) + "秒");
        btnForward.setText("+" + (seekStep / 1000) + "秒");
        btnSpeed.setOnClickListener(v -> cycleSpeed());
        btnRatio.setOnClickListener(v -> cycleRatio());
        btnInfo.setOnClickListener(v -> toggleInfo());
        btnBack.setOnClickListener(v -> { restoreOrientation(); finish(); });
        btnDanmu.setOnClickListener(v -> showDanmuSettings());
        btnLock.setOnClickListener(v -> {
            isLocked = !isLocked;
            btnLock.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
            if (isLocked) {
                topBar.setVisibility(View.INVISIBLE);
                controller.setVisibility(View.INVISIBLE);
                btnLock.setVisibility(View.VISIBLE);
            } else {
                showCtrl(true);
            }
        });
        btnCloseInfo.setOnClickListener(v -> { infoPanel.setVisibility(View.GONE); infoVis = false; });
        btnBrightness = findViewById(R.id.btnBrightness);
        if (btnBrightness != null) {
            btnBrightness.setOnClickListener(v -> showBrightnessDialog());
        }
        // 应用保存的亮度和 HDR 设置
        int savedBright = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getInt("video_brightness", 100);
        if (savedBright != 100) applyBrightness(savedBright);
        applyHdrMode();
        btnEpisodeList.setOnClickListener(v -> showEpisodePicker());
        btnNextEp.setOnClickListener(v -> playNextEp());
        setupCloudModeToggle();
        setupFocusAutoHide();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null) {
                    player.seekTo(p);
                    tvTime.setText(fmt(p) + " / " + fmt(player.getDuration()));
                    if (danmuView != null) danmuView.seekToTime(p);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        showCtrl(true);
        loadPlayInfo();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        hideSystemUi();
    }

    private void initPlayer() {
        // 强制最高刷新率（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Window win = getWindow();
            if (win != null) {
                WindowManager.LayoutParams lp = win.getAttributes();
                Display.Mode[] modes = getWindowManager().getDefaultDisplay().getSupportedModes();
                float maxRefresh = 60f;
                for (Display.Mode m : modes) {
                    if (m.getRefreshRate() > maxRefresh) maxRefresh = m.getRefreshRate();
                }
                lp.preferredDisplayModeId = 0;
                for (Display.Mode m : modes) {
                    if (m.getRefreshRate() == maxRefresh) {
                        lp.preferredDisplayModeId = m.getModeId();
                        break;
                    }
                }
                win.setAttributes(lp);
            }
        }
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this);
        if ("software".equals(getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("decoder_mode", "hardware"))) {
            rf.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        }else {
            rf.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        }
        player = new SimpleExoPlayer.Builder(this, rf)
                .setTrackSelector(new DefaultTrackSelector(this)).build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setShutterBackgroundColor(Color.TRANSPARENT);
        playerView.setKeepScreenOn(true);
        // 字幕样式：白色文字，透明背景，黑色描边
        com.google.android.exoplayer2.ui.CaptionStyleCompat captionStyle =
                new com.google.android.exoplayer2.ui.CaptionStyleCompat(
                        Color.WHITE,                    // 前景色
                        Color.TRANSPARENT,              // 背景色（透明）
                        Color.TRANSPARENT,              // 窗口色（透明）
                        com.google.android.exoplayer2.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        Color.BLACK,                    // 描边色
                        null                            // 字体
                );
        if (playerView.getSubtitleView() != null) {
            playerView.getSubtitleView().setStyle(captionStyle);
        }

        player.addAnalyticsListener(new com.google.android.exoplayer2.analytics.AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs) {
                actualVideoDecoder = decoderName;
                Log.d(TAG, "视频解码器: " + decoderName);
            }

            @Override
            public void onAudioDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs) {
                actualAudioDecoder = decoderName;
                Log.d(TAG, "音频解码器: " + decoderName);
            }
        });

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                tvBuffering.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (s == Player.STATE_READY) {
                    if (!seeked && seekTs > 0) { player.seekTo(seekTs); seeked = true; }
                    startSave(); updateTime(); showCtrl(true);
                    btnPlayPause.setText(player.isPlaying() ? "暂停" : "播放");
                    if (danmuOn && danmuView != null) danmuView.resume();
                    // HDR 检测（延时等格式就绪）
                    checkHdr();
                    // 打印音轨信息
                    com.google.android.exoplayer2.Format af2 = player.getAudioFormat();
                    if (af2 != null) {
                        Log.d(TAG, "音轨: codec=" + af2.codecs + " mime=" + af2.sampleMimeType
                                + " 采样率=" + af2.sampleRate + "Hz"
                                + " 声道=" + af2.channelCount
                                + " 码率=" + af2.bitrate);
                    } else {
                        Log.d(TAG, "音轨: 无音频信息");
                    }
                } else if (s == Player.STATE_ENDED) {
                    Log.d(TAG, "STATE_ENDED epList=" + (episodeList != null ? episodeList.size() : "null")
                            + " idx=" + currentEpIndex);
                    if (episodeList != null && currentEpIndex >= 0 && currentEpIndex < episodeList.size() - 1) {
                        playNextEp();
                    }
                } else {
                    stopSave();
                    if (danmuOn && danmuView != null) danmuView.pause();
                }
            }
            int retryCount = 0;
            @Override public void onPlayerError(PlaybackException e) {
                // 打印完整错误链
                StringBuilder sb2 = new StringBuilder("播放错误: " + e.getMessage());
                Throwable tc = e;
                while (tc != null) {
                    sb2.append("\n  ").append(tc.getClass().getSimpleName()).append(": ").append(tc.getMessage());
                    tc = tc.getCause();
                }
                Log.e(TAG, sb2.toString());
                // 按响应码切换：200→HLS, 206→渐进式
                int code = OkHttpExoDataSource.lastResponseCode;
                if (code == 200 && !useHls && !cloudDirectUrl.isEmpty()) {
                    useHls = true;
                    Log.d(TAG, "响应200，切换到HLS");
                    switchMediaSource(true);
                    return;
                } else if (code == 206 && useHls && !cloudDirectUrl.isEmpty()) {
                    useHls = false;
                    Log.d(TAG, "响应206，切换到渐进式");
                    switchMediaSource(false);
                    return;
                }
                if (retryCount < 5 && player != null) {
                    retryCount++;
                    handler.postDelayed(() -> {
                        if (player != null) {
                            player.prepare();
                            player.setPlayWhenReady(true);
                        }
                    }, 2000 * retryCount);
                }
            }
        });
    }

    private void loadPlayInfo() {
        Map<String, String> b = new HashMap<>(); b.put("item_guid", itemGuid);
        apiManager.getApi().getPlayInfo(b).enqueue(new retrofit2.Callback<ApiResponse<PlayInfoResponse>>() {
            @Override public void onResponse(retrofit2.Call<ApiResponse<PlayInfoResponse>> call,
                                             retrofit2.Response<ApiResponse<PlayInfoResponse>> r) {
                if (r.isSuccessful() && r.body() != null && r.body().code == 0 && r.body().data != null) {
                    PlayInfoResponse info = r.body().data;
                    mediaGuid = info.mediaGuid; videoGuid = info.videoGuid; audioGuid = info.audioGuid;
                    if (info.parentGuid != null && !info.parentGuid.isEmpty()) parentGuid = info.parentGuid;
                    // 从 intent 的 parent_guid 兜底（详情页传递的）
                    if (parentGuid == null || parentGuid.isEmpty()) {
                        parentGuid = getIntent().getStringExtra("parent_guid");
                    }
                    subtitleGuid = info.subtitleGuid != null ? info.subtitleGuid : "_no_display_";
                    if (info.item != null && info.item.tvTitle != null) itemTV = info.item.tvTitle;
                    if (info.item != null) itemTitle = info.item.title;
                    if (info.item != null && info.item.seasonNumber > 0) seasonNumber = info.item.seasonNumber;
                    if (info.item != null) getIntent().putExtra("episode_number", info.item.episodeNumber);
                    int epNum = info.item != null ? info.item.episodeNumber : 0;
                    String matchName = itemTV != null && !itemTV.isEmpty() ? itemTV : itemTitle;
                    if (matchName != null && !matchName.isEmpty() && epNum > 0) {
                        matchName = matchName + " S" + String.format("%02d", seasonNumber) + "E" + String.format("%02d", epNum);
                    }
                    loadDanmu(matchName, itemGuid);
                    if (info.item != null && info.item.mediaStream != null
                            && info.item.mediaStream.resolutions != null
                            && !info.item.mediaStream.resolutions.isEmpty())
                        resolution = info.item.mediaStream.resolutions.get(0);

                    // 获取直链信息，获取完后开始播放
                    fetchCloudDirectLink(itemGuid);
                }
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {}
        });
    }

    private void fetchCloudDirectLink(final String itemGuid) {
        if (mediaGuid == null) { startPlayback(); return; }
        // 直接用 play/info 返回的 media_guid 调 stream 接口获取直链
        Map<String, Object> streamReq = new HashMap<>();
        Map<String, Object> header = new HashMap<>();
        header.put("User-Agent", new String[]{"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"});
        streamReq.put("header", header);
        streamReq.put("level", 1);
        streamReq.put("media_guid", mediaGuid);
        // ip = 账号的 MD5 哈希（32位十六进制）
        String account = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("user", "video");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(account.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            streamReq.put("ip", sb.toString());
        } catch (Exception e) {
            streamReq.put("ip", "");
        }
        // 添加 nonce（桌面版 Go 代理也这么做）
        streamReq.put("nonce", String.valueOf(100000 + (int)(Math.random() * 900000)));
        String reqJson = new com.google.gson.Gson().toJson(streamReq);
        Log.d(TAG, "getStream 请求体: " + reqJson);
        Log.d(TAG, "getStream 请求URL: " + baseUrl + "/v/api/v1/stream");
        apiManager.getApi().getStream(streamReq)
            .enqueue(new retrofit2.Callback<ApiResponse<StreamResponse>>() {
                @Override public void onResponse(retrofit2.Call<ApiResponse<StreamResponse>> call,
                        retrofit2.Response<ApiResponse<StreamResponse>> r) {
                    try {
                        Log.d(TAG, "getStream resp code=" + r.code());
                        if (r.isSuccessful() && r.body() != null && r.body().code == 0
                                && r.body().data != null) {
                            StreamResponse sd = r.body().data;
                            if (sd.videoStream != null) {
                                streamBitrate = sd.videoStream.bps;
                                streamVCodec = sd.videoStream.codecName != null ? sd.videoStream.codecName : "";
                                streamVProfile = sd.videoStream.profile != null ? sd.videoStream.profile : "";
                                streamVWidth = sd.videoStream.width;
                                streamVHeight = sd.videoStream.height;
                                streamVBitDepth = sd.videoStream.bitDepth;
                                streamVHdr = sd.videoStream.dvProfile > 0;
                                streamVPixFmt = sd.videoStream.pixFmt != null ? sd.videoStream.pixFmt : "";
                                streamVColor = sd.videoStream.colorPrimaries != null ? sd.videoStream.colorPrimaries : "";
                                String cs = sd.videoStream.colorSpace != null ? sd.videoStream.colorSpace : "";
                                if (!streamVColor.isEmpty() && !cs.isEmpty()) streamVColor += " " + cs;
                                streamVFps = sd.videoStream.rFrameRate != null ? sd.videoStream.rFrameRate : "";
                                streamDuration = sd.videoStream.duration;
                            }
                            // 文件信息
                            boolean isStrm = false;
                            if (sd.fileStream != null) {
                                streamFileSize = sd.fileStream.size;
                                String fn = sd.fileStream.fileName != null ? sd.fileStream.fileName : "";
                                String fp = sd.fileStream.path != null ? sd.fileStream.path : "";
                                if (fn.contains(".")) streamContainer = fn.substring(fn.lastIndexOf('.') + 1).toLowerCase();
                                if (streamDuration <= 0) streamDuration = sd.fileStream.duration;
                                isStrm = fp.toLowerCase().endsWith(".strm") || fn.toLowerCase().endsWith(".strm");
                            }
                            // STRM 文件：必须使用直链，NAS 无法代理
                            if (isStrm && sd.directLinkQualities != null && !sd.directLinkQualities.isEmpty()) {
                                isStrmFile = true;
                                String directUrl = sd.directLinkQualities.get(0).url;
                                // 还原 & → &（JSON Unicode 转义）
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
                                // 使用当前选择的画质 URL
                                cloudDirectUrl = qualityUrls[qualityIndex];
                                Log.d(TAG, "STRM 文件，使用直链: " + cloudDirectUrl);
                                runOnUiThread(() -> {
                                    setCloudBtnVisible(true);
                                    updateCloudBtnText();
                                });
                            }
                            // 音频流
                            streamAudioTracks = sd.audioStreams;
                            // 字幕流
                            streamSubtitleTracks = sd.subtitleStreams;
                            // 流 API 信息不全时用 MediaExtractor 补充
                            if (streamVCodec.isEmpty() || streamContainer.isEmpty()) {
                                probeWithMediaExtractor();
                            }
                            qualityCount = sd.directLinkQualities != null ? sd.directLinkQualities.size() : 0;
                            if (qualityCount > 0) {
                                qualityLabels = new String[qualityCount];
                                for (int qi = 0; qi < qualityCount; qi++) {
                                    StreamResponse.DirectLinkQuality q = sd.directLinkQualities.get(qi);
                                    qualityLabels[qi] = q.resolution != null && !q.resolution.isEmpty() ? q.resolution : ("画质" + qi);
                                }
                                if (qualityIndex >= qualityCount) qualityIndex = 0;
                                runOnUiThread(() -> {
                                    setCloudBtnVisible(true);
                                    updateCloudBtnText();
                                });
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "getStream parse error", e);
                    }
                    startPlayback();
                }
                @Override public void onFailure(retrofit2.Call<ApiResponse<StreamResponse>> call, Throwable t) {
                    Log.e(TAG, "getStream onFailure: " + t.getMessage());
                    startPlayback();
                }
            });
    }

    /** 开始播放（加载到 ExoPlayer） */
    private void startPlayback() {
        if (mediaGuid == null) return;
        String url;
        if (!cloudDirectUrl.isEmpty()) {
            // STRM 或夸克直链：直接用直链 URL
            url = cloudDirectUrl;
            if (isStrmFile) OkHttpExoDataSource.setChunkedMode(0); // STRM 不需要分块
            else OkHttpExoDataSource.setChunkedMode(10 * 1024 * 1024);
            Log.d(TAG, "播放模式: 直链 " + url);
        } else if (cloudDirectMode && qualityCount > 0) {
            url = baseUrl + "/v/api/v1/media/range/" + mediaGuid + "?direct_link_quality_index=" + qualityIndex;
            Log.d(TAG, "播放模式: 直链 (NAS代理, index=" + qualityIndex + ") " + url);
        } else {
            url = baseUrl + "/v/api/v1/media/range/" + mediaGuid;
            Log.d(TAG, "播放模式: 代理 " + url);
        }
        com.google.android.exoplayer2.upstream.DataSource.Factory f = () -> new OkHttpExoDataSource(apiManager.getStreamClient());
        // 检测内容类型：HEAD 请求获取 Content-Type
        useHls = url.contains(".m3u8");
        if (!useHls && !cloudDirectUrl.isEmpty()) {
            // 默认按文件特征推断
            useHls = isStrmFile;
            Log.d(TAG, "直链模式: isStrm=" + isStrmFile + " useHls=" + useHls);
        }
        if (useHls) {
            player.setMediaSource(new com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory(f).createMediaSource(MediaItem.fromUri(url)));
            Log.d(TAG, "播放器: HLS");
        } else {
            player.setMediaSource(new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(f, new DefaultExtractorsFactory()).createMediaSource(MediaItem.fromUri(url)));
            Log.d(TAG, "播放器: 渐进式");
        }
        player.prepare(); player.setPlayWhenReady(true);
        Log.d(TAG, "startPlayback: parentGuid=" + parentGuid + " episodeList=" + (episodeList != null ? episodeList.size() : "null") + " loadingEp=" + loadingEpisodes);
        if (parentGuid != null && !parentGuid.isEmpty() && episodeList == null && !loadingEpisodes) loadEpisodeList();
    }

    // ========== 剧集相关 ==========

    private void loadEpisodeList() {
        loadingEpisodes = true;
        Log.d(TAG, "getEpisodeList 请求: " + baseUrl + "/v/api/v1/episode/list/" + parentGuid);
        apiManager.getApi().getEpisodeList(parentGuid).enqueue(
                new retrofit2.Callback<ApiResponse<List<PlayListItem>>>() {
                    @Override public void onResponse(retrofit2.Call<ApiResponse<List<PlayListItem>>> call,
                                                     retrofit2.Response<ApiResponse<List<PlayListItem>>> resp) {
                        loadingEpisodes = false;
                        Log.d(TAG, "getEpisodeList 响应 code=" + resp.code()
                                + " body=" + (resp.body() != null ? "code=" + resp.body().code + " size="
                                        + (resp.body().data != null ? resp.body().data.size() : "null") : "null"));
                        if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0
                                && resp.body().data != null && !resp.body().data.isEmpty()) {
                            episodeList = resp.body().data;
                            currentEpIndex = -1;
                            int epNum = getIntent().getIntExtra("episode_number", 0);
                            for (int i = 0; i < episodeList.size(); i++) {
                                PlayListItem ep = episodeList.get(i);
                                if (ep.guid.equals(itemGuid)) { currentEpIndex = i; break; }
                                // GUID 不匹配时按集数找
                                if (currentEpIndex < 0 && epNum > 0 && ep.episodeNumber == epNum) {
                                    currentEpIndex = i;
                                }
                            }
                            Log.d(TAG, "getEpisodeList 成功: " + episodeList.size() + " 集, currentIdx=" + currentEpIndex
                                    + " epNum=" + epNum + " itemGuid=" + itemGuid);
                            btnEpisodeList.setVisibility(View.VISIBLE);
                            if (currentEpIndex >= 0 && currentEpIndex < episodeList.size() - 1)
                                btnNextEp.setVisibility(View.VISIBLE);
                        }
                    }
                    @Override public void onFailure(retrofit2.Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                        loadingEpisodes = false;
                        Log.e(TAG, "getEpisodeList 失败: " + t.getMessage());
                    }
                });
    }

    private void playNextEp() {
        if (episodeList == null || currentEpIndex < 0 || currentEpIndex >= episodeList.size() - 1) return;
        PlayListItem next = episodeList.get(currentEpIndex + 1);
        itemGuid = next.guid; currentEpIndex++;
        btnNextEp.setVisibility(currentEpIndex < episodeList.size() - 1 ? View.VISIBLE : View.GONE);
        mediaGuid = null; videoGuid = null; audioGuid = null; seeked = false; seekTs = 0; episodeList = null;
        itemTitle = next.title;
        loadPlayInfo();
    }

    private void showEpisodePicker() {
        if (episodeList == null || episodeList.isEmpty()) return;
        final String[] items = new String[episodeList.size()];
        for (int i = 0; i < episodeList.size(); i++) {
            PlayListItem ep = episodeList.get(i);
            items[i] = "EP" + (ep.episodeNumber > 0 ? ep.episodeNumber : (i+1))
                    + "  " + (ep.title != null ? ep.title : "");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择剧集")
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < episodeList.size()) {
                        PlayListItem s = episodeList.get(which);
                        itemGuid = s.guid; currentEpIndex = which;
                        btnNextEp.setVisibility(which < episodeList.size() - 1 ? View.VISIBLE : View.GONE);
                        mediaGuid = null; seeked = false; seekTs = 0; episodeList = null;
                        itemTitle = s.title;
                        loadPlayInfo();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ========== 控制 ==========

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            btnPlayPause.setText("播放");
            if (danmuOn && danmuView != null) danmuView.pause();
        } else {
            player.play();
            btnPlayPause.setText("暂停");
            updateTime();
            if (danmuOn && danmuView != null) danmuView.resume();
        }
    }

    private void seekRel(int ms) {
        if (player == null) return;
        long p = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + ms));
        player.seekTo(p);
        if (danmuView != null) danmuView.seekToTime(p);
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.length;
        float s = speeds[speedIdx];
        btnSpeed.setText((s == (int)s ? String.valueOf((int)s) : String.valueOf(s)) + "x");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) player.setPlaybackSpeed(s);
    }

    private void cycleRatio() {
        ratioIdx = (ratioIdx + 1) % RATIO_MODES.length;
        btnRatio.setText(RATIO_LABELS[ratioIdx]);
        if (playerView != null) {
            playerView.setResizeMode(RATIO_MODES[ratioIdx]);
            // 拉伸/缩放时把字幕上移，避免被裁切
            if (playerView.getSubtitleView() != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) playerView.getSubtitleView().getLayoutParams();
                if (lp != null) {
                    int bottom = RATIO_MODES[ratioIdx] == 0 ? 0 : (int)(55 * getResources().getDisplayMetrics().density);
                    if (lp.bottomMargin != bottom) {
                        lp.bottomMargin = bottom;
                        playerView.getSubtitleView().setLayoutParams(lp);
                    }
                }
            }
        }
    }

    private void checkHdr() {
        handler.postDelayed(() -> {
            if (player == null) return;
            boolean isHdr = isHdrVideo();
            Log.d(TAG, "HDR检查: isHdr=" + isHdr
                    + " streamVHdr=" + streamVHdr
                    + " color=" + streamVColor);

            if (isHdr && deviceSupportsHdr()) {
                boolean userEnabled = getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                        .getBoolean("hdr_enabled", true);
                if (userEnabled) {
                    // 尽早设置，减少闪屏
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getWindow().setColorMode(ActivityInfo.COLOR_MODE_HDR);
                    }
                    showDanmuStatus("HDR 模式已激活");
                    Log.d(TAG, "HDR 模式已激活");
                } else {
                    Log.d(TAG, "用户关闭了 HDR");
                }
            } else if (isHdr && !deviceSupportsHdr()) {
                Log.d(TAG, "设备不支持 HDR，跳过");
                // 可选：提示用户
                // showDanmuStatus("设备不支持 HDR 显示");
            }
        }, 1500);
    }

    private boolean deviceSupportsHdr() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Display.HdrCapabilities caps = getWindowManager()
                    .getDefaultDisplay().getHdrCapabilities();
            if (caps != null) {
                for (int type : caps.getSupportedHdrTypes()) {
                    if (type == Display.HdrCapabilities.HDR_TYPE_HDR10
                            || type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    private void switchMediaSource(boolean toHls) {
        if (player == null || cloudDirectUrl.isEmpty()) return;
        handler.post(() -> {
            String u = cloudDirectUrl;
            com.google.android.exoplayer2.upstream.DataSource.Factory f2 = () -> new OkHttpExoDataSource(apiManager.getStreamClient());
            com.google.android.exoplayer2.source.MediaSource ms = toHls
                    ? new com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory(f2).createMediaSource(MediaItem.fromUri(u))
                    : new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(f2, new DefaultExtractorsFactory()).createMediaSource(MediaItem.fromUri(u));
            player.stop();
            player.setMediaSource(ms);
            player.prepare();
            player.setPlayWhenReady(true);
        });
    }

    private void showBrightnessDialog() {
        SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        int brightness = p.getInt("video_brightness", 100);
        if (brightness > 100) brightness = 100;
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(R.layout.dialog_brightness);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xDD1A1A1A));

        final TextView label = dialog.findViewById(R.id.dm_label);
        final SeekBar sb = dialog.findViewById(R.id.dm_seekbar);
        final Button cancel = dialog.findViewById(R.id.dm_cancel);
        final Button ok = dialog.findViewById(R.id.dm_ok);
        final Button reset = dialog.findViewById(R.id.dm_reset);

        if (label != null) label.setText("亮度: " + (brightness - 100) + "%");
        if (sb != null) {
            sb.setMax(200);
            sb.setProgress(brightness);
            sb.setKeyProgressIncrement(5);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seek, int val, boolean fromUser) {
                    int adj = val - 100;
                    if (label != null) label.setText("亮度: " + (adj > 0 ? "+" : "") + adj + "%");
                    if (fromUser) applyBrightness(val);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
        if (reset != null) reset.setOnClickListener(v -> { if (sb != null) { sb.setProgress(100); applyBrightness(100); if (label != null) label.setText("亮度: 0%"); } });
        if (cancel != null) cancel.setOnClickListener(v -> dialog.dismiss());
        if (ok != null) ok.setOnClickListener(v -> {
            if (sb != null) p.edit().putInt("video_brightness", sb.getProgress()).apply();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void applyHdrMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        boolean enabled = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getBoolean("hdr_enabled", true);
        boolean videoHdr = isHdrVideo();
        Log.d(TAG, "applyHdrMode: enabled=" + enabled + " videoHdr=" + videoHdr);
        if (enabled && videoHdr) {
            getWindow().setColorMode(1);
            showDanmuStatus("HDR 已开启");
        } else {
            getWindow().setColorMode(0);
            if (videoHdr) showDanmuStatus("HDR 已关闭");
        }
    }

    private boolean isHdrVideo() {
        Format vf = player != null ? player.getVideoFormat() : null;
        if (vf != null && vf.colorInfo != null) {
            int cs = vf.colorInfo.colorSpace;
            int ct = vf.colorInfo.colorTransfer;
            if (cs >= 6 || ct == 7 || ct == 16 || ct == 18) return true;
        }
        return streamVHdr || (!streamVColor.isEmpty() && (streamVColor.contains("bt2020") || streamVColor.contains("2020")));
    }

    /** 调节屏幕亮度（仅当前 Activity），val 0~200，100=系统默认 */
    private void applyBrightness(int val) {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (val == 100) {
            lp.screenBrightness = -1f; // 恢复系统默认
        } else {
            float f = val / 100f;
            f = Math.max(0.01f, Math.min(1.0f, f));
            lp.screenBrightness = f;
        }
        getWindow().setAttributes(lp);
    }

    private void toggleInfo() {
        infoVis = !infoVis; infoPanel.setVisibility(infoVis ? View.VISIBLE : View.GONE);
        if (infoVis) {
            updateInfo();
            btnCloseInfo.post(() -> btnCloseInfo.requestFocus());
        } else {
            btnInfo.requestFocus();
        }
    }

    private void updateTitle() {
        int epNum = getIntent().getIntExtra("episode_number", 0);
        String epName = itemTitle != null ? itemTitle : "";
        StringBuilder sb = new StringBuilder();
        if (itemTV != null && !itemTV.isEmpty()) {
            sb.append(itemTV);
            if (epNum > 0) sb.append(" 第").append(epNum).append("集");
            if (epName != null && !epName.isEmpty() && !epName.equals(itemTV)) {
                sb.append(" ").append(epName);
            }
        } else {
            sb.append(epName);
        }
        tvTitle.setText(sb.toString().trim());
    }

    private void showCtrl(boolean show) {
        if (show && isLocked) {
            btnLock.setVisibility(View.VISIBLE);
            return;
        }
        ctrlVis = show;
        controller.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        topBar.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        btnLock.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        btnDanmu.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show) {
            updateTitle();
            if (!controller.hasFocus() && !btnDanmu.hasFocus() && !btnLock.hasFocus() && !topBar.hasFocus()) {
                btnPlayPause.post(() -> btnPlayPause.requestFocus());
            }
            resetHideTimer();
        }
        else hideSystemUi();
    }
    private void resetHideTimer() {
        handler.removeCallbacks(hideC);
        handler.postDelayed(hideC, 5000);
    }
    private final Runnable hideC = () -> {
        if (controller.hasFocus() || btnDanmu.hasFocus() || btnLock.hasFocus() || btnCloudMode.hasFocus() || btnBrightness.hasFocus() || topBar.hasFocus()) {
            resetHideTimer();
            return;
        }
        showCtrl(false);
    };

    private void setCloudBtnVisible(boolean vis) {
        btnCloudMode.setVisibility(vis ? View.VISIBLE : View.GONE);
        // 按钮可见时给弹幕匹配名留出空间
        if (tvDanmuMatch != null) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) tvDanmuMatch.getLayoutParams();
            if (lp != null) {
                lp.rightMargin = vis ? (int)(100 * getResources().getDisplayMetrics().density) : 20;
                tvDanmuMatch.setLayoutParams(lp);
            }
        }
    }

    private void updateCloudBtnText() {
        String mode = isStrmFile ? "STRM" : (cloudDirectMode ? "直链" : "代理");
        String ql = qualityCount > 0 && qualityIndex < qualityCount && qualityLabels != null ? qualityLabels[qualityIndex] : "";
        btnCloudMode.setText(ql.isEmpty() ? mode : mode + "/" + ql);
        btnCloudMode.setTextColor(isStrmFile || cloudDirectMode ? 0xFF81C784 : 0xFFFFB74D);
    }

    private void setupCloudModeToggle() {
        SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        cloudDirectMode = p.getBoolean("cloud_direct_mode", true);
        qualityIndex = p.getInt("cloud_quality_index", 1);
        updateCloudBtnText();
        btnCloudMode.setOnClickListener(v -> showQualityMenu());
    }

    private void showQualityMenu() {
        final SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        if (isStrmFile && qualityCount > 0 && qualityLabels != null) {
            // STRM 文件：只显示画质，不显示切换代理
            final String[] items = new String[qualityCount];
            for (int i = 0; i < qualityCount; i++) {
                items[i] = (i == qualityIndex ? "✓ " : "  ") + qualityLabels[i];
            }
            new android.app.AlertDialog.Builder(this)
                .setTitle("画质选择")
                .setItems(items, (dialog, which) -> {
                    if (which < qualityCount) {
                        qualityIndex = which;
                        sp.edit().putInt("cloud_quality_index", qualityIndex).apply();
                        updateCloudBtnText();
                        dialog.dismiss();
                        Toast.makeText(this, "切换画质：" + qualityLabels[qualityIndex], Toast.LENGTH_SHORT).show();
                        // 直接重选播放，不清空 cloudDirectUrl
                        mediaGuid = null; seeked = false; seekTs = 0;
                        loadPlayInfo();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
        } else if (cloudDirectMode && qualityCount > 0 && qualityLabels != null) {
            // 直链模式：显示画质列表 + 切换代理
            final String[] items = new String[qualityCount + 1];
            for (int i = 0; i < qualityCount; i++) {
                items[i] = (i == qualityIndex ? "✓ " : "  ") + qualityLabels[i];
            }
            items[qualityCount] = "切换到代理模式";
            new android.app.AlertDialog.Builder(this)
                .setTitle("播放设置")
                .setItems(items, (dialog, which) -> {
                    if (which < qualityCount) {
                        qualityIndex = which;
                        sp.edit().putInt("cloud_quality_index", qualityIndex)
                                .putBoolean("cloud_direct_mode", true).apply();
                        updateCloudBtnText();
                        dialog.dismiss();
                        Toast.makeText(this, "切换画质：" + qualityLabels[qualityIndex], Toast.LENGTH_SHORT).show();
                        mediaGuid = null; seeked = false; seekTs = 0;
                        loadPlayInfo();
                    } else {
                        cloudDirectMode = false;
                        sp.edit().putBoolean("cloud_direct_mode", false).apply();
                        updateCloudBtnText();
                        dialog.dismiss();
                        Toast.makeText(this, "已切换为代理模式", Toast.LENGTH_SHORT).show();
                        mediaGuid = null; seeked = false; seekTs = 0;
                        loadPlayInfo();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
        } else {
            // 代理模式：只显示切换到直链
            new android.app.AlertDialog.Builder(this)
                .setTitle("播放设置")
                .setItems(new String[]{"切换到直链模式"}, (dialog, which) -> {
                    cloudDirectMode = true;
                    sp.edit().putBoolean("cloud_direct_mode", true).apply();
                    updateCloudBtnText();
                    dialog.dismiss();
                    Toast.makeText(this, "已切换为直链模式", Toast.LENGTH_SHORT).show();
                    mediaGuid = null; seeked = false; seekTs = 0;
                    loadPlayInfo();
                })
                .setNegativeButton("关闭", null)
                .show();
        }
    }

    private void setupFocusAutoHide() {
        View.OnFocusChangeListener l = (v, hasFocus) -> {
            if (hasFocus) resetHideTimer();
        };
        btnPlayPause.setOnFocusChangeListener(l);
        btnRewind.setOnFocusChangeListener(l);
        btnForward.setOnFocusChangeListener(l);
        btnSpeed.setOnFocusChangeListener(l);
        btnRatio.setOnFocusChangeListener(l);
        btnInfo.setOnFocusChangeListener(l);
        btnEpisodeList.setOnFocusChangeListener(l);
        btnNextEp.setOnFocusChangeListener(l);
        btnBack.setOnFocusChangeListener(l);
        btnDanmu.setOnFocusChangeListener(l);
        btnLock.setOnFocusChangeListener(l);
        btnCloudMode.setOnFocusChangeListener(l);
        if (btnBrightness != null) btnBrightness.setOnFocusChangeListener(l);
    };

    private void updateTime() {
        if (player == null) return;
        long cur = player.getCurrentPosition(), dur = player.getDuration();
        tvTime.setText(fmt(cur) + " / " + fmt(dur));
        seekBar.setMax((int) Math.max(dur, 1)); seekBar.setProgress((int) cur);
        seekBar.setKeyProgressIncrement(5000); // 方向键每次 5 秒
        if (danmuView != null) danmuView.setPlayTime(cur);
        handler.postDelayed(timeR, 500);
    }
    private final Runnable timeR = () -> { if (player != null && player.isPlaying()) updateTime(); };

    private String fmtAudioCodec(String name) {
        if (name == null) return "?";
        String n = name.toLowerCase();
        if (n.contains("truehd")) return "Dolby TrueHD";
        if (n.contains("eac3") || n.contains("ec3")) return "Dolby Digital Plus";
        if (n.contains("ac3")) return "AC3";
        if (n.contains("dts")) return n.contains("dts-hd") || n.contains("dtshd") ? "DTS-HD MA" : "DTS";
        if (n.contains("mp4a") || n.contains("aac")) return "AAC";
        if (n.contains("flac")) return "FLAC";
        if (n.contains("opus")) return "OPUS";
        if (n.contains("vorbis")) return "Vorbis";
        if (n.contains("pcm")) return "PCM";
        return name;
    }
    private String fmtVideoCodec(String name) {
        if (name == null) return "?";
        String n = name.toLowerCase();
        if (n.contains("hevc") || n.contains("h265") || n.contains("h.265")) return "H.265";
        if (n.contains("avc") || n.contains("h264") || n.contains("h.264")) return "H.264";
        if (n.contains("vp9")) return "VP9";
        if (n.contains("vp8")) return "VP8";
        if (n.contains("av1")) return "AV1";
        if (n.contains("mpeg")) return "MPEG";
        return name.toUpperCase();
    }

    private void probeWithMediaExtractor() {
        if (mediaGuid == null || baseUrl == null) return;
        final String url = baseUrl + "/v/api/v1/media/range/" + mediaGuid;
        new Thread(() -> {
            try {
                android.media.MediaExtractor ex = new android.media.MediaExtractor();
                try {
                    ex.setDataSource(url);
                    for (int i = 0; i < ex.getTrackCount(); i++) {
                        android.media.MediaFormat mf = ex.getTrackFormat(i);
                        String mime = mf.getString(android.media.MediaFormat.KEY_MIME);
                        if (mime == null) continue;
                        if (mime.startsWith("video/")) {
                            if (streamVWidth <= 0) streamVWidth = mf.containsKey(android.media.MediaFormat.KEY_WIDTH) ? mf.getInteger(android.media.MediaFormat.KEY_WIDTH) : 0;
                            if (streamVHeight <= 0) streamVHeight = mf.containsKey(android.media.MediaFormat.KEY_HEIGHT) ? mf.getInteger(android.media.MediaFormat.KEY_HEIGHT) : 0;
                            if (streamBitrate <= 0) streamBitrate = mf.containsKey(android.media.MediaFormat.KEY_BIT_RATE) ? mf.getInteger(android.media.MediaFormat.KEY_BIT_RATE) : 0;
                            if (streamVCodec.isEmpty()) streamVCodec = mime.replace("video/", "");
                        }
                    }
                } finally { ex.release(); }
            } catch (Exception e) {
                Log.w(TAG, "MediaExtractor 失败: " + e.getMessage());
            }
        }).start();
    }

    private String fmtTime(int sec) {
        if (sec <= 0) return "?";
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h + "h" + (m < 10 ? "0" : "") + m + "m" + (s < 10 ? "0" : "") + s + "s";
    }
    private String fmtSize(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes >= 1073741824L) return String.format("%.1f GB", bytes / 1073741824f);
        if (bytes >= 1048576) return String.format("%.0f MB", bytes / 1048576f);
        return bytes / 1024 + " KB";
    }

    private void updateInfo() {
        if (player == null) return;
        Format vf = player.getVideoFormat();
        Format af = player.getAudioFormat();

        // 视频（左列）
        StringBuilder v = new StringBuilder();
        v.append("── 视频 ──\n");
        String codec = fmtVideoCodec(streamVCodec.isEmpty() ? (vf != null ? vf.codecs : null) : streamVCodec);
        v.append("编码 ").append(codec).append("\n");
        int w = streamVWidth > 0 ? streamVWidth : (vf != null ? vf.width : 0);
        int h = streamVHeight > 0 ? streamVHeight : (vf != null ? vf.height : 0);
        if (w > 0 && h > 0) v.append("分辨率 ").append(w).append("×").append(h).append("\n");
        float fps = 0;
        if (!streamVFps.isEmpty()) { try { fps = Float.parseFloat(streamVFps.replaceAll("[^0-9.]", "")); } catch (Exception ignored) {} }
        if (fps <= 0 && vf != null) fps = vf.frameRate;
        if (fps > 0) v.append("帧率 ").append(String.format("%.3f fps", fps)).append("\n");
        if (streamBitrate > 0) v.append("码率 ").append(formatBitrate(streamBitrate)).append("\n");
        else if (vf != null && vf.bitrate > 0) v.append("码率 ").append(vf.bitrate/1000).append("kbps\n");
        if (streamVBitDepth > 0) v.append("色深 ").append(streamVBitDepth).append("bit\n");
        if (streamVHdr || (vf != null && vf.colorInfo != null)) v.append("HDR10\n");
        v.append("解码 ").append(actualVideoDecoder.isEmpty() ? (isHwDecode ? "硬解" : "软解") : actualVideoDecoder);
        infoText.setText(v.toString());

        // 音频（右列）
        StringBuilder a = new StringBuilder();
        a.append("── 音频 ──\n");
        if (af != null) {
            String ac = fmtAudioCodec(af.codecs != null ? af.codecs : af.sampleMimeType);
            a.append("编码 ").append(ac).append("\n");
            int ch = af.channelCount;
            a.append("声道 ").append(ch > 0 ? (ch == 8 ? "7.1" : ch == 6 ? "5.1" : ch + "ch") : "?").append("\n");
            a.append("采样 ").append(af.sampleRate > 0 ? af.sampleRate + "Hz" : "?").append("\n");
            if (af.bitrate > 0) a.append("码率 ").append(af.bitrate/1000).append("kbps\n");
            a.append("解码 ").append(actualAudioDecoder.isEmpty() ? (isHwDecode ? "硬解" : "软解") : actualAudioDecoder);
        } else {
            a.append("无音轨\n");
        }
        if (infoTextAudio != null) infoTextAudio.setText(a.toString());

        // 额外信息（字幕、音轨、时长）
        StringBuilder x = new StringBuilder();
        // 额外音轨
        if (streamAudioTracks != null && streamAudioTracks.size() > 1) {
            for (int i = 1; i < streamAudioTracks.size(); i++) {
                StreamResponse.AudioStreamInfo asi = streamAudioTracks.get(i);
                String an = fmtAudioCodec(asi.codecName);
                String al = asi.language != null && !asi.language.isEmpty() ? asi.language : "";
                String ach = asi.channels > 0 ? (asi.channels == 8 ? "7.1" : asi.channels == 6 ? "5.1" : asi.channels + "ch") : "?";
                String ab = asi.bps > 0 ? " " + formatBitrate(asi.bps) : "";
                x.append("音轨").append(i + 1).append(" ").append(an);
                if (!al.isEmpty()) x.append(" ").append(al);
                x.append(" ").append(ach).append(ab).append("  ");
            }
        }
        // 字幕
        if (streamSubtitleTracks != null && !streamSubtitleTracks.isEmpty()) {
            if (x.length() > 0) x.append("\n");
            x.append("字幕 ");
            for (int i = 0; i < streamSubtitleTracks.size(); i++) {
                StreamResponse.SubtitleStreamInfo sub = streamSubtitleTracks.get(i);
                if (i > 0) x.append("  ");
                String sf = sub.codecName != null ? sub.codecName.toUpperCase() : "?";
                String lang = sub.language != null && !sub.language.isEmpty() ? sub.language : "?";
                String def = sub.isDefault != 0 ? "[默认]" : "";
                x.append(sf).append(" ").append(lang).append(def);
            }
        }
        // 时长
        long durMs = player.getDuration();
        if (durMs > 0) {
            if (x.length() > 0) x.append("\n");
            x.append("时长 ").append(fmtTime((int)(durMs/1000)));
        }
        if (infoTextExtra != null) infoTextExtra.setText(x.toString());
    }

    private String formatBitrate(int bps) {
        if (bps <= 0) return "?";
        if (bps >= 1000000) return String.format("%.2f Mbps", bps / 1000000f);
        if (bps >= 1000) return String.format("%.0f Kbps", bps / 1000f);
        return bps + " bps";
    }

    private String formatDecoder(String decoderName) {
        if (decoderName == null || decoderName.isEmpty()) return "等待中...";
        if (decoderName.startsWith("ffmpeg")) return "FFmpeg软解 (" + decoderName + ")";
        if (decoderName.startsWith("OMX.google") || decoderName.startsWith("c2.android"))
            return "系统软解 (" + decoderName + ")";
        // 其他一律视为硬件解码器
        return "硬解 (" + decoderName + ")";
    }

    // ========== 弹幕 ==========

    private void showDanmuSettings() {
        SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        final boolean[] isOn = {p.getBoolean("danmu_on", true)};
        final int[] area = {p.getInt("danmu_area", 35)};
        final float[] speed = {p.getFloat("danmu_speed", 1.0f)};
        final float[] opacity = {p.getFloat("danmu_opacity", 0.85f)};
        final float[] fontSize = {p.getFloat("danmu_fontsize", 22f)};
        final boolean[] outline = {p.getBoolean("danmu_outline", true)};
        final int[] density = {p.getInt("danmu_density", 100)};
        final int[] maxActive = {p.getInt("danmu_maxactive", 40)};
        final int[] offset = {p.getInt("danmu_offset", 0)};
        final int[] maxComments = {p.getInt("danmu_maxcomments", 50000)};
        final float[] rowSpacing = {p.getFloat("danmu_rowspacing", 1.8f)};

        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(R.layout.dialog_danmu_settings);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xDD1A1A1A));

        final Switch sw = dialog.findViewById(R.id.dm_sw);
        sw.setChecked(isOn[0]);

        final Switch swScroll = dialog.findViewById(R.id.dm_show_scroll);
        final Switch swTop = dialog.findViewById(R.id.dm_show_top);
        final Switch swBottom = dialog.findViewById(R.id.dm_show_bottom);
        swScroll.setChecked(p.getBoolean("danmu_scroll", true));
        swTop.setChecked(p.getBoolean("danmu_top", true));
        swBottom.setChecked(p.getBoolean("danmu_bottom", true));

        final Button matchBtn = dialog.findViewById(R.id.dm_matchBtn);
        matchBtn.setOnClickListener(v -> { dialog.dismiss(); showDanmuSearch(); });

        int opVal = Math.min(100, Math.max(0, (int)(opacity[0]*100)));
        setupSlider(dialog, R.id.dm_opacity, "不透明度", opVal, 0, 100, "%");
        setupSlider(dialog, R.id.dm_area, "显示区域", area[0], 10, 80, "%");
        setupSlider(dialog, R.id.dm_fontsize, "字号", (int)fontSize[0], 12, 40, "");
        setupSlider(dialog, R.id.dm_rowspacing, "行间距", (int)(rowSpacing[0]*100), 120, 300, "x");
        setupSlider(dialog, R.id.dm_speed, "速度", (int)(speed[0]*100), 30, 300, "x");
        setupSlider(dialog, R.id.dm_density, "密度", density[0], 50, 100, "%");
        setupSlider(dialog, R.id.dm_maxactive, "同屏最大", maxActive[0], 10, 80, "");
        setupSlider(dialog, R.id.dm_offset, "时间偏移", offset[0]+30, 0, 60, "s");
        setupSlider(dialog, R.id.dm_maxcomments, "加载上限", maxComments[0], 100, 50000, "");

        final Switch olSw = dialog.findViewById(R.id.dm_outline);
        olSw.setChecked(outline[0]);

        dialog.findViewById(R.id.dm_cancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.dm_ok).setOnClickListener(v -> {
            isOn[0]=sw.isChecked(); outline[0]=olSw.isChecked();
            int a = readSlider(dialog, R.id.dm_area, 10);
            float sp = readSlider(dialog, R.id.dm_speed, 30) / 100f;
            float op = readSlider(dialog, R.id.dm_opacity, 0) / 100f;      // 修复：min 是 0 不是 20
            float fs = readSlider(dialog, R.id.dm_fontsize, 12);
            float rs = readSlider(dialog, R.id.dm_rowspacing, 120) / 100f;  // 行间距 1.20~3.00
            int dn = readSlider(dialog, R.id.dm_density, 50);
            int mx = readSlider(dialog, R.id.dm_maxactive, 10);
            int of = readSlider(dialog, R.id.dm_offset, 0) - 30;
            int mc = readSlider(dialog, R.id.dm_maxcomments, 100);

            p.edit().putBoolean("danmu_on",isOn[0]).putInt("danmu_area",a)
                    .putFloat("danmu_speed",sp).putFloat("danmu_opacity",op)
                    .putFloat("danmu_fontsize",fs).putBoolean("danmu_outline",outline[0])
                    .putInt("danmu_density",dn).putInt("danmu_maxactive",mx)
                    .putInt("danmu_offset",of).putInt("danmu_maxcomments",mc)
                    .putFloat("danmu_rowspacing",rs)
                    .putBoolean("danmu_scroll", swScroll.isChecked())
                    .putBoolean("danmu_top", swTop.isChecked())
                    .putBoolean("danmu_bottom", swBottom.isChecked()).apply();
            danmuView.setShowScroll(swScroll.isChecked());
            danmuView.setShowTop(swTop.isChecked());
            danmuView.setShowBottom(swBottom.isChecked());
            if(isOn[0]) {
                boolean wasOff = !danmuOn;
                danmuOn=true; danmuView.setVisibility(View.VISIBLE); btnDanmu.setText("弹✕");
                danmuView.setAreaPct(a); danmuView.setSpeedMul(sp); danmuView.setOpacity(op);
                danmuView.setFontSize(fs); danmuView.setShowOutline(outline[0]);
                danmuView.setMaxActive(mx); danmuView.setDensityPct(dn);
                danmuView.setRowSpacing(rs);
                danmuView.start();
                if(danmuItems!=null) danmuView.loadDanmu(danmuItems);
                // 从关闭→打开时，触发一次匹配
                if (wasOff && pendingDanmuTitle != null) loadDanmu(pendingDanmuTitle, pendingDanmuGuid);
            } else { danmuOn=false; danmuView.setVisibility(View.GONE); btnDanmu.setText("弹");
                danmuView.stop(); danmuView.clear(); }
            dialog.dismiss();
        });
        dialog.show();
    }

    private void setupSlider(android.app.Dialog d, int id, String label, int val, int min, int max, String unit) {
        ViewGroup v = d.findViewById(id);
        if(v==null) return;
        TextView tv = v.findViewById(R.id.dm_label);
        SeekBar sb = v.findViewById(R.id.dm_seekbar);
        if(tv!=null) {
            String display = String.valueOf(val);
            if(unit.equals("x")) display = String.format("%.1f", val/100f);
            else if(unit.equals("%")) display = val + "%";
            else if(unit.equals("s")) display = (val-30) + "s";
            tv.setText(label + "  " + display);
        }
        if(sb!=null) { sb.setMax(max-min); sb.setProgress(val-min);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s,int p,boolean u) {
                    int real = p + min;
                    String suffix;
                    if(unit.equals("x")) suffix = String.format("%.1f", real/100f);
                    else if(unit.equals("%")) suffix = real + "%";
                    else if(unit.equals("s")) suffix = (real-30) + "s";
                    else suffix = String.valueOf(real);
                    if(tv!=null) tv.setText(label + "  " + suffix);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
    }

    private int readSlider(android.app.Dialog d, int id, int min) {
        SeekBar sb = d.findViewById(id).findViewById(R.id.dm_seekbar);
        return sb != null ? sb.getProgress() + min : min;
    }

    private void showDanmuSearch() {
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(R.layout.dialog_danmu_search);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xDD1A1A1A));
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        final android.widget.EditText input = dialog.findViewById(R.id.dm_search_input);
        final Button sBtn = dialog.findViewById(R.id.dm_search_btn);
        final LinearLayout results = dialog.findViewById(R.id.dm_search_results);
        final Button cancelBtn = dialog.findViewById(R.id.dm_search_cancel);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        String autoFill = itemTV != null && !itemTV.isEmpty() ? itemTV : (itemTitle != null ? itemTitle : "");
        input.setText(autoFill);
        if(!autoFill.isEmpty()) input.setSelection(autoFill.length());

        dialog.show();

        sBtn.setOnClickListener(v -> {
            final String kw = input.getText().toString().trim();
            if(kw.isEmpty()) { Toast.makeText(this,"请输入番剧名",Toast.LENGTH_SHORT).show(); return; }
            results.removeAllViews();
            sBtn.setEnabled(false); sBtn.setText("搜索中...");
            sBtn.setTextColor(0xFF808080);
            Log.d(TAG, "搜索: " + kw);
            TextView ld = new TextView(this); ld.setText("正在搜索  " + kw + "...");
            ld.setTextColor(0xFF808080); ld.setPadding(0,12,0,10); ld.setTextSize(14);
            results.addView(ld);
            new Thread(() -> {
                try {
                    String enc = java.net.URLEncoder.encode(kw,"UTF-8");
                    java.net.URL u = new java.net.URL(danmuUrl+"/api/v2/search/anime?keyword="+enc);
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)u.openConnection();
                    c.connect();
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream(),"UTF-8"));
                    StringBuilder sb = new StringBuilder(); String l;
                    while((l=r.readLine())!=null) sb.append(l); r.close();
                    String raw = sb.toString();
                    org.json.JSONArray arr = null;
                    if(raw.startsWith("{")) {
                        org.json.JSONObject obj = new org.json.JSONObject(raw);
                        if(obj.has("data")) arr = obj.getJSONArray("data");
                        else if(obj.has("animes")) arr = obj.getJSONArray("animes");
                    }
                    if(arr == null) arr = new org.json.JSONArray(raw.trim());
                    final org.json.JSONArray finalArr = arr;
                    runOnUiThread(() -> {
                        results.removeAllViews(); sBtn.setEnabled(true); sBtn.setText("搜索");
                        if(finalArr.length()==0) {
                            sBtn.setEnabled(true); sBtn.setText("搜索");
                            sBtn.setTextColor(0xFFFFFFFF);
                            TextView e=new TextView(this); e.setText("未找到匹配结果");
                            e.setTextColor(0xFF808080); e.setPadding(0,20,0,10);
                            results.addView(e); return;
                        }
                        for(int i=0;i<Math.min(finalArr.length(),20);i++){
                            org.json.JSONObject o=finalArr.optJSONObject(i); if(o==null) continue;
                            int aid=o.optInt("animeId",o.optInt("id",0));
                            String t=o.optString("animeTitle","");
                            if(t.isEmpty()) t=o.optString("title","");
                            if(t.isEmpty()) t=o.optString("name","?");
                            int epCount = o.optInt("episodeCount", 0);
                            String label = epCount > 0 ? t + "  (" + epCount + "集)" : t;
                            Button b=new Button(this);
                            b.setBackgroundResource(R.drawable.bg_search_item);
                            b.setText(label); b.setTextColor(0xFFEEEEEE);
                            b.setPadding(16,14,16,14); b.setAllCaps(false);
                            b.setTextSize(14);
                            b.setGravity(android.view.Gravity.START);
                            b.setLayoutParams(new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                            ((LinearLayout.LayoutParams)b.getLayoutParams()).setMargins(0,0,0,6);
                            final String animeName = t;
                            b.setOnClickListener(btn->{ dialog.dismiss(); loadDanmuById(aid, animeName); });
                            results.addView(b);
                        }
                    });
                } catch(Exception e) {
                    runOnUiThread(()->{ results.removeAllViews(); sBtn.setEnabled(true); sBtn.setText("搜索");
                        TextView er=new TextView(this); er.setText("搜索失败: "+e.getMessage());
                        er.setTextColor(0xFFFF6B6B); er.setPadding(0,20,0,10);
                        results.addView(er); });
                }
            }).start();
        });
    }

    private void loadDanmuById(int animeId) { loadDanmuById(animeId, null); }
    private void loadDanmuById(int animeId, String animeName) {
        showDanmuStatus("弹幕: 正在加载...");
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(danmuUrl+"/api/v2/bangumi/"+animeId);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)u.openConnection();
                c.connect();
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(),"UTF-8"));
                StringBuilder sb = new StringBuilder(); String l;
                while((l=r.readLine())!=null) sb.append(l); r.close();
                org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                org.json.JSONArray eps = null;
                if(j.has("bangumi") && j.getJSONObject("bangumi").has("episodes"))
                    eps = j.getJSONObject("bangumi").getJSONArray("episodes");
                else if(j.has("episodes"))
                    eps = j.getJSONArray("episodes");
                else if(j.has("data") && j.getJSONObject("data").has("episodes"))
                    eps = j.getJSONObject("data").getJSONArray("episodes");
                if(eps==null || eps.length()==0) { showDanmuStatus("弹幕: 无剧集"); return; }

                final int epCount = eps.length();
                final String[] epLabels = new String[epCount];
                final int[] epIds = new int[epCount];
                for (int i = 0; i < epCount; i++) {
                    org.json.JSONObject epo = eps.getJSONObject(i);
                    int epNum = epo.optInt("episodeNumber", epo.optInt("ep", i + 1));
                    epIds[i] = epo.optInt("episodeId", epo.optInt("id", 0));
                    epLabels[i] = "第" + epNum + "集";
                }

                runOnUiThread(() -> {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("选择剧集")
                            .setItems(epLabels, (dialog, which) -> {
                                if (which >= 0 && which < epCount && epIds[which] > 0) {
                                    // 保存手动匹配的番剧信息（给同系列其他集自动用）
                                    if (animeId > 0 && animeName != null && itemTV != null && !itemTV.isEmpty()) {
                                        try {
                                            String cacheJson = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("danmu_match_cache", "{}");
                                            org.json.JSONObject cache = new org.json.JSONObject(cacheJson);
                                            cache.put(itemTV, animeId + "|" + animeName);
                                            getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit().putString("danmu_match_cache", cache.toString()).apply();
                                        } catch (Exception ignored) {}
                                    }
                                    loadDanmuByEp(epIds[which], animeName != null ? animeName + " " + epLabels[which] : null);
                                } else
                                    showDanmuStatus("弹幕: 无效剧集ID");
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch(Exception e) { showDanmuStatus("弹幕失败: "+e.getMessage()); }
        }).start();
    }

    private void loadDanmuByEp(int epId) { loadDanmuByEp(epId, null); }
    private void loadDanmuByEp(int epId, String epName) {
        updateDanmuMatchDisplay(epName);
        showDanmuStatus(epName != null ? "弹幕: " + epName + " 获取数据..." : "弹幕: 获取数据...");
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(danmuUrl+"/api/v2/comment/"+epId+"?format=json");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)u.openConnection();
                c.connect();
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(),"UTF-8"));
                StringBuilder sb = new StringBuilder(); String l;
                while((l=r.readLine())!=null) sb.append(l); r.close();
                String raw = sb.toString();
                org.json.JSONArray arr;
                if(raw.trim().startsWith("{")) {
                    org.json.JSONObject jo = new org.json.JSONObject(raw);
                    if(jo.has("comments")) arr = jo.getJSONArray("comments");
                    else if(jo.has("data")) arr = jo.getJSONArray("data");
                    else arr = new org.json.JSONArray();
                } else {
                    arr = new org.json.JSONArray(raw);
                }
                int maxCom = getSharedPreferences("fntv_prefs",MODE_PRIVATE).getInt("danmu_maxcomments",50000);
                int total = arr.length();
                float keepRate = total > maxCom ? (float) maxCom / total : 1f;
                final List<DanmuView.DanmuComment> list = new java.util.ArrayList<>(Math.min(total, maxCom));
                for(int i=0;i<total;i++){
                    // 超过上限时按比例稀疏，保留均匀分布
                    if (keepRate < 1f && Math.random() >= keepRate) continue;

                    org.json.JSONObject o=arr.getJSONObject(i);
                    DanmuView.DanmuComment dc=new DanmuView.DanmuComment();
                    dc.text=o.optString("m","");

                    String pVal = o.optString("p", "0");
                    if (pVal.contains(",")) {
                        String[] parts = pVal.split(",");
                        try { dc.time = Float.parseFloat(parts[0].trim()); }
                        catch (Exception e2) { dc.time = 0; }
                        // 解析弹幕模式：1=滚动 4=底部 5=顶部
                        if (parts.length >= 2) {
                            try { dc.type = Integer.parseInt(parts[1].trim()); }
                            catch (Exception e2) { dc.type = 1; }
                        }
                        if (parts.length >= 4) {
                            try { dc.color = 0xFF000000 | (int) Long.parseLong(parts[2].trim()); }
                            catch (Exception e2) { dc.color = 0xFFFFFFFF; }
                        } else {
                            dc.color = 0xFFFFFFFF;
                        }
                    } else {
                        try { dc.time = Float.parseFloat(pVal); }
                        catch (Exception e2) { dc.time = 0; }
                        dc.type = 1;
                        dc.color = 0xFF000000 | o.optInt("c", 0xFFFFFF);
                    }

                    // 打印前 10 条弹幕的颜色和模式，方便调试
                    if (i < 10) Log.d(TAG, "[弹幕#" + i + "] \"" + dc.text
                            + "\" color=0x" + String.format("%08X", dc.color)
                            + " mode=" + dc.type + " raw=" + pVal);

                    list.add(dc);
                }

                java.util.Collections.sort(list, (a, b) -> Float.compare(a.time, b.time));

                final int loaded = list.size();
                runOnUiThread(()->{
                    danmuItems = list;
                    // 弹幕时间戳压缩匹配视频时长
                    long videoDur = 0;
                    if (player != null && player.getDuration() > 0)
                        videoDur = player.getDuration() / 1000;
                    else if (itemDuration > 0)
                        videoDur = itemDuration;
                    if (videoDur > 0 && !list.isEmpty()) {
                        float maxDanmuTime = list.get(list.size() - 1).time;
                        if (maxDanmuTime > 0) {
                            float ratio = (float) videoDur / maxDanmuTime;
                            for (DanmuView.DanmuComment dc : list) {
                                dc.time *= ratio;
                            }
                            Log.d(TAG, "[弹幕] 时间匹配 " + maxDanmuTime + "s → " + videoDur + "s ratio=" + String.format("%.3f", ratio));

                            // 差距超过 ±5% 时警告
                            if (ratio < 0.95f || ratio > 1.05f) {
                                float diffPct = Math.abs((1f - ratio) * 100f);
                                String direction = ratio < 1f ? "弹幕偏长" : "弹幕偏短";
                                showDanmuStatus( direction
                                        + " 弹幕=" + (int)maxDanmuTime + "s"
                                        + " 视频=" + (int)videoDur + "s"
                                        + " 差距=" + String.format("%.1f", diffPct) + "%");
                            }
                        }
                    }

                    if (danmuView != null) {
                        danmuView.loadDanmu(list);
                        if (danmuOn) {
                            danmuView.start();
                        }
                    }
                    String msg = (epName != null ? epName + " " : "") + "弹幕加载完成·共" + total + "条";
                    if(loaded < total) msg += "（显示前" + loaded + "条）";
                    showDanmuStatus(msg);
                });
            } catch(Exception e) { showDanmuStatus("弹幕失败: "+e.getMessage()); }
        }).start();
    }


    private void updateDanmuMatchDisplay(String name) {
        danmuMatchedName = name != null ? name : "";
        runOnUiThread(() -> {
            if (tvDanmuMatch != null) {
                if (!danmuMatchedName.isEmpty()) {
                    tvDanmuMatch.setText(danmuMatchedName);
                    tvDanmuMatch.setVisibility(View.VISIBLE);
                    tvDanmuMatch.setSelected(true);
                } else {
                    tvDanmuMatch.setVisibility(View.GONE);
                }
            }
        });
    }

    private void showDanmuStatus(String msg) {
        runOnUiThread(() -> {
            if (tvDanmuStatus != null) {
                tvDanmuStatus.setText(msg);
                tvDanmuStatus.setVisibility(View.VISIBLE);
                handler.removeCallbacks(hideDanmuStatus);
                handler.postDelayed(hideDanmuStatus, 6000);
            }
            Log.d(TAG, "[弹幕] " + msg);
        });
    }
    private final Runnable hideDanmuStatus = () -> {
        if (tvDanmuStatus != null) tvDanmuStatus.setVisibility(View.GONE);
    };

    private void loadDanmu(String title, String guid) {
        pendingDanmuTitle = title;
        pendingDanmuGuid = guid;
        if (!danmuOn) {
            showDanmuStatus("弹幕: 已关闭，" + title + " 待匹配");
            return;
        }
        if (danmuUrl.isEmpty() || title == null) {
            showDanmuStatus("弹幕: 未配置服务器");
            return;
        }
        // 先提取目标集数
        int cacheTargetEp = 0;
        java.util.regex.Matcher cacheEpM = java.util.regex.Pattern.compile("[Ee](\\d+)").matcher(title);
        if (cacheEpM.find()) cacheTargetEp = Integer.parseInt(cacheEpM.group(1));

        // 检查缓存（异步，避免主线程网络请求）
        if (itemTV != null && !itemTV.isEmpty() && cacheTargetEp > 0) {
            final int fTargetEp = cacheTargetEp;
            try {
                String cacheJson = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("danmu_match_cache", "{}");
                org.json.JSONObject cache = new org.json.JSONObject(cacheJson);
                if (cache.has(itemTV)) {
                    String val = cache.getString(itemTV);
                    String[] parts = val.split("\\|", 2);
                    if (parts.length == 2) {
                        final int cachedAid = Integer.parseInt(parts[0]);
                        final String cachedName = parts[1];
                        showDanmuStatus("弹幕: 缓存命中 " + cachedName + "，匹配第" + fTargetEp + "集...");
                        Log.d(TAG, "缓存命中: " + itemTV + " -> " + cachedName + " (aid=" + cachedAid + ")");
                        new Thread(() -> {
                            try {
                                java.net.URL bu = new java.net.URL(danmuUrl + "/api/v2/bangumi/" + cachedAid);
                                java.net.HttpURLConnection bc = (java.net.HttpURLConnection) bu.openConnection();
                                bc.connect();
                                java.io.BufferedReader br2 = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(bc.getInputStream(), "UTF-8"));
                                StringBuilder bp2 = new StringBuilder(); String l3;
                                while ((l3 = br2.readLine()) != null) bp2.append(l3);
                                br2.close();
                                org.json.JSONObject bj2 = new org.json.JSONObject(bp2.toString());
                                org.json.JSONArray eps2 = null;
                                if (bj2.has("bangumi") && bj2.getJSONObject("bangumi").has("episodes"))
                                    eps2 = bj2.getJSONObject("bangumi").getJSONArray("episodes");
                                else if (bj2.has("episodes")) eps2 = bj2.getJSONArray("episodes");
                                else if (bj2.has("data") && bj2.getJSONObject("data").has("episodes"))
                                    eps2 = bj2.getJSONObject("data").getJSONArray("episodes");
                                if (eps2 != null) {
                                    for (int ei = 0; ei < eps2.length(); ei++) {
                                        org.json.JSONObject epo = eps2.getJSONObject(ei);
                                        if (epo.optInt("episodeNumber", 0) == fTargetEp) {
                                            int epId = epo.optInt("episodeId", 0);
                                            if (epId > 0) {
                                                final int fEpId = epId;
                                                String epLabel = cachedName + " 第" + fTargetEp + "集";
                                                updateDanmuMatchDisplay(epLabel);
                                                loadDanmuByEp(fEpId, epLabel);
                                                return;
                                            }
                                        }
                                    }
                                }
                                // 缓存没找到对应集 → 走自动匹配
                                Log.d(TAG, "缓存未找到第" + fTargetEp + "集，走自动匹配");
                                startAutoMatch(title, fTargetEp);
                            } catch (Exception e) {
                                Log.w(TAG, "缓存请求失败: " + e.getMessage());
                                startAutoMatch(title, fTargetEp);
                            }
                        }).start();
                        return; // 缓存命中，异步处理
                    }
                }
            } catch (Exception ignored) {}
        }

        // 缓存未命中或无缓存 → 直接自动匹配
        startAutoMatch(title, cacheTargetEp);
    }

    private void startAutoMatch(String title, int targetEp) {
        showDanmuStatus("弹幕: 正在匹配 \"" + title + "\"...");
        new Thread(() -> {
            try {
                int episodeId = 0;
                String matchedName = title;
                Log.d(TAG, "目标集数: " + targetEp + " 来自: " + title);

                // match 返回的番剧信息（若集数不匹配时用作搜索回退）
                int matchAnimeId = 0;
                String matchAnimeTitle = "";
                try {
                    java.net.URL url = new java.net.URL(danmuUrl + "/api/v2/match");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    String body = "{\"fileName\":\"" + title + "\"}";
                    conn.getOutputStream().write(body.getBytes("UTF-8"));
                    conn.connect();
                    if (conn.getResponseCode() == 200) {
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder resp = new StringBuilder(); String l;
                        while ((l = br.readLine()) != null) resp.append(l);
                        br.close();
                        org.json.JSONObject j = new org.json.JSONObject(resp.toString());
                        Log.d(TAG, "match resp: " + resp.toString().substring(0, Math.min(200, resp.length())));
                        org.json.JSONArray matches = j.optJSONArray("matches");
                        if (matches != null && matches.length() > 0) {
                            org.json.JSONObject firstMatch = matches.getJSONObject(0);
                            episodeId = firstMatch.optInt("episodeId", 0);
                            matchAnimeId = firstMatch.optInt("animeId", 0);
                            matchAnimeTitle = firstMatch.optString("animeTitle", "");
                            String matchEp = firstMatch.optString("episodeTitle", "");
                            if (!matchAnimeTitle.isEmpty())
                                matchedName = matchAnimeTitle + (matchEp.isEmpty() ? "" : " " + matchEp);
                            updateDanmuMatchDisplay(matchedName);
                            // 验证集数是否匹配
                            int matchedEpNum = 0;
                            java.util.regex.Matcher mEp = java.util.regex.Pattern.compile("[第](\\d+)[集]").matcher(matchEp);
                            if (mEp.find()) matchedEpNum = Integer.parseInt(mEp.group(1));
                            Log.d(TAG, "match ok: epId=" + episodeId + " matchedEp=" + matchedEpNum
                                    + " targetEp=" + targetEp + " name=" + matchedName);
                            if (targetEp > 0 && matchedEpNum > 0 && matchedEpNum != targetEp) {
                                showDanmuStatus("弹幕: match 匹配到第" + matchedEpNum + "集，需要第" + targetEp + "集，丢弃");
                                episodeId = -1;
                            }
                        }
                    }
                } catch (Exception ignored) {}
                // match 失败时重试一次
                if (episodeId <= 0 && matchAnimeId == 0) {
                    Log.d(TAG, "match retry...");
                    try {
                        java.net.URL url = new java.net.URL(danmuUrl + "/api/v2/match");
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setDoOutput(true);
                        String body = "{\"fileName\":\"" + title + "\"}";
                        conn.getOutputStream().write(body.getBytes("UTF-8"));
                        conn.connect();
                        if (conn.getResponseCode() == 200) {
                            java.io.BufferedReader br = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                            StringBuilder resp = new StringBuilder(); String l;
                            while ((l = br.readLine()) != null) resp.append(l);
                            br.close();
                            org.json.JSONObject j = new org.json.JSONObject(resp.toString());
                            org.json.JSONArray matches = j.optJSONArray("matches");
                            if (matches != null && matches.length() > 0) {
                                org.json.JSONObject firstMatch = matches.getJSONObject(0);
                                episodeId = firstMatch.optInt("episodeId", 0);
                                matchAnimeId = firstMatch.optInt("animeId", 0);
                                matchAnimeTitle = firstMatch.optString("animeTitle", "");
                                if (episodeId > 0) {
                                    String matchEp = firstMatch.optString("episodeTitle", "");
                                    int matchedEpNum = 0;
                                    java.util.regex.Matcher mEp = java.util.regex.Pattern.compile("[第](\\d+)[集]").matcher(matchEp);
                                    if (mEp.find()) matchedEpNum = Integer.parseInt(mEp.group(1));
                                    if (targetEp > 0 && matchedEpNum > 0 && matchedEpNum != targetEp) {
                                        showDanmuStatus("弹幕: 重试 match 匹配到第" + matchedEpNum + "集，需要第" + targetEp + "集，丢弃");
                                        episodeId = -1;
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (episodeId <= 0) {
                    // 优先用 match 返回的番剧名/ID 搜索，避免带上 S01E162 后缀
                    String searchKw = matchAnimeTitle.isEmpty() ? title : matchAnimeTitle;
                    Log.d(TAG, "match failed, searching: " + searchKw + " (animeId=" + matchAnimeId + ")");
                    showDanmuStatus("弹幕: 搜索 \"" + searchKw + "\"...");
                    try {
                        // 如果有 animeId 直接取剧集列表，跳过搜索
                        if (matchAnimeId > 0) {
                            java.net.URL bu = new java.net.URL(danmuUrl + "/api/v2/bangumi/" + matchAnimeId);
                            java.net.HttpURLConnection bc = (java.net.HttpURLConnection) bu.openConnection();
                            bc.connect();
                            java.io.BufferedReader br2 = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(bc.getInputStream(), "UTF-8"));
                            StringBuilder bp = new StringBuilder(); String l3;
                            while ((l3 = br2.readLine()) != null) bp.append(l3);
                            br2.close();
                            org.json.JSONObject bj = new org.json.JSONObject(bp.toString());
                            org.json.JSONArray eps = null;
                            if (bj.has("bangumi") && bj.getJSONObject("bangumi").has("episodes"))
                                eps = bj.getJSONObject("bangumi").getJSONArray("episodes");
                            else if (bj.has("episodes")) eps = bj.getJSONArray("episodes");
                            else if (bj.has("data") && bj.getJSONObject("data").has("episodes"))
                                eps = bj.getJSONObject("data").getJSONArray("episodes");
                            if (eps != null && eps.length() > 0) {
                                if (targetEp > 0) {
                                    showDanmuStatus("弹幕: 从剧集列表中找第" + targetEp + "集...");
                                    for (int ei = 0; ei < eps.length(); ei++) {
                                        org.json.JSONObject epo = eps.getJSONObject(ei);
                                        if (epo.optInt("episodeNumber", 0) == targetEp) {
                                            episodeId = epo.optInt("episodeId", 0);
                                            matchedName = matchAnimeTitle + " 第" + targetEp + "集";
                                            updateDanmuMatchDisplay(matchedName);
                                            Log.d(TAG, "direct bangumi match: targetEp=" + targetEp + " episodeId=" + episodeId);
                                            break;
                                        }
                                    }
                                }
                                if (episodeId <= 0)
                                    episodeId = eps.getJSONObject(0).optInt("episodeId", 0);
                            }
                        }
                        if (episodeId <= 0) {
                            String enc = java.net.URLEncoder.encode(searchKw, "UTF-8");
                            java.net.URL su = new java.net.URL(danmuUrl + "/api/v2/search/anime?keyword=" + enc);
                            java.net.HttpURLConnection sc = (java.net.HttpURLConnection) su.openConnection();
                            sc.connect();
                            java.io.BufferedReader sr = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(sc.getInputStream(), "UTF-8"));
                            StringBuilder srp = new StringBuilder(); String l2;
                            while ((l2 = sr.readLine()) != null) srp.append(l2);
                            sr.close();
                            String raw = srp.toString().trim();
                            Log.d(TAG, "search resp: " + raw.substring(0, Math.min(200, raw.length())));
                            org.json.JSONArray animes;
                            if (raw.startsWith("[")) animes = new org.json.JSONArray(raw);
                            else { org.json.JSONObject jo = new org.json.JSONObject(raw);
                                animes = jo.has("animes") ? jo.getJSONArray("animes")
                                        : jo.has("data") ? jo.getJSONArray("data")
                                        : new org.json.JSONArray(); }
                            if (animes.length() > 0) {
                                // 找 episodeCount >= targetEp 的番剧
                                int aid = 0;
                                for (int ai = 0; ai < animes.length(); ai++) {
                                    org.json.JSONObject aobj = animes.getJSONObject(ai);
                                    int ac = aobj.optInt("episodeCount", 0);
                                    if (targetEp <= 0 || ac >= targetEp) {
                                        aid = aobj.optInt("animeId", aobj.optInt("id", 0));
                                        Log.d(TAG, "found anime: " + aobj.optString("animeTitle", "") + " epCount=" + ac);
                                        break;
                                    }
                                }
                                Log.d(TAG, "search result: selected animeId=" + aid + " (targetEp=" + targetEp + ")");
                                if (aid > 0) {
                                    java.net.URL bu = new java.net.URL(danmuUrl + "/api/v2/bangumi/" + aid);
                                    java.net.HttpURLConnection bc = (java.net.HttpURLConnection) bu.openConnection();
                                    bc.connect();
                                    java.io.BufferedReader br2 = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(bc.getInputStream(), "UTF-8"));
                                    StringBuilder bp = new StringBuilder(); String l3;
                                    while ((l3 = br2.readLine()) != null) bp.append(l3);
                                    br2.close();
                                    org.json.JSONObject bj = new org.json.JSONObject(bp.toString());
                                    org.json.JSONArray eps = null;
                                    if (bj.has("bangumi") && bj.getJSONObject("bangumi").has("episodes"))
                                        eps = bj.getJSONObject("bangumi").getJSONArray("episodes");
                                    else if (bj.has("episodes")) eps = bj.getJSONArray("episodes");
                                    else if (bj.has("data") && bj.getJSONObject("data").has("episodes"))
                                        eps = bj.getJSONObject("data").getJSONArray("episodes");
                                    if (eps != null && eps.length() > 0) {
                                        if (targetEp > 0) {
                                            showDanmuStatus("弹幕: 从剧集列表中找第" + targetEp + "集...");
                                            for (int ei = 0; ei < eps.length(); ei++) {
                                                org.json.JSONObject epo = eps.getJSONObject(ei);
                                                if (epo.optInt("episodeNumber", 0) == targetEp) {
                                                    episodeId = epo.optInt("episodeId", 0);
                                                    Log.d(TAG, "search matched ep by number: targetEp=" + targetEp + " episodeId=" + episodeId);
                                                    break;
                                                }
                                            }
                                        }
                                        if (episodeId <= 0)
                                            episodeId = eps.getJSONObject(0).optInt("episodeId", 0);
                                    }
                                }
                            }
                        }
                    } catch (Exception e2) {
                        Log.d(TAG, "search fallback failed: " + e2.getMessage());
                    }
                }
                // 从手动匹配缓存中查找
                if (episodeId <= 0 && itemTV != null && !itemTV.isEmpty()) {
                    try {
                        String cacheJson = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("danmu_match_cache", "{}");
                        org.json.JSONObject cache = new org.json.JSONObject(cacheJson);
                        if (cache.has(itemTV)) {
                            String val = cache.getString(itemTV);
                            String[] parts = val.split("\\|", 2);
                            if (parts.length == 2) {
                                final int cachedAid = Integer.parseInt(parts[0]);
                                final String cachedName = parts[1];
                                showDanmuStatus("弹幕: 使用缓存 \"" + cachedName + "\" 匹配第" + targetEp + "集...");
                                Log.d(TAG, "缓存命中: " + itemTV + " -> " + cachedName + " (aid=" + cachedAid + ")");
                                // 直接调用 bangumi API 取剧集列表
                                java.net.URL bu = new java.net.URL(danmuUrl + "/api/v2/bangumi/" + cachedAid);
                                java.net.HttpURLConnection bc = (java.net.HttpURLConnection) bu.openConnection();
                                bc.connect();
                                java.io.BufferedReader br2 = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(bc.getInputStream(), "UTF-8"));
                                StringBuilder bp2 = new StringBuilder(); String l3;
                                while ((l3 = br2.readLine()) != null) bp2.append(l3);
                                br2.close();
                                org.json.JSONObject bj2 = new org.json.JSONObject(bp2.toString());
                                org.json.JSONArray eps2 = null;
                                if (bj2.has("bangumi") && bj2.getJSONObject("bangumi").has("episodes"))
                                    eps2 = bj2.getJSONObject("bangumi").getJSONArray("episodes");
                                else if (bj2.has("episodes")) eps2 = bj2.getJSONArray("episodes");
                                else if (bj2.has("data") && bj2.getJSONObject("data").has("episodes"))
                                    eps2 = bj2.getJSONObject("data").getJSONArray("episodes");
                                if (eps2 != null) {
                                    for (int ei = 0; ei < eps2.length(); ei++) {
                                        org.json.JSONObject epo = eps2.getJSONObject(ei);
                                        if (epo.optInt("episodeNumber", 0) == targetEp) {
                                            episodeId = epo.optInt("episodeId", 0);
                                            matchedName = cachedName + " 第" + targetEp + "集";
                                            Log.d(TAG, "缓存匹配成功: epId=" + episodeId);
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "缓存读取失败: " + e.getMessage());
                    }
                }
                if (episodeId <= 0) {
                    showDanmuStatus("弹幕: 匹配失败，请手动匹配");
                    return;
                }
                updateDanmuMatchDisplay(matchedName);
                loadDanmuByEp(episodeId, matchedName);
            } catch (Exception e) {
                showDanmuStatus("弹幕加载失败: " + e.getMessage());
                Log.e(TAG, "loadDanmu error", e);
            }
        }).start();
    }

    // ========== 进度保存 ==========

    private void startSave() { handler.removeCallbacks(saveR); handler.postDelayed(saveR, 10000); }
    private void stopSave() { handler.removeCallbacks(saveR); }
    private final Runnable saveR = new Runnable() {
        @Override public void run() { saveProgress(); handler.postDelayed(this, 15000); }
    };

    private void saveProgress() {
        if (player == null || player.getPlaybackState() != Player.STATE_READY) return;
        long p = player.getCurrentPosition(); if (p <= 0) return;
        long ts = p / 1000;
        com.fntv.app.model.WatchHistoryManager whm = new com.fntv.app.model.WatchHistoryManager(
                getSharedPreferences("fntv_prefs", MODE_PRIVATE));
        int ep = getIntent().getIntExtra("episode_number", 0);
        long actualDuration = itemDuration > 0 ? itemDuration : (player.getDuration() / 1000);
        if (actualDuration <= 0) actualDuration = 0;
        whm.put(new com.fntv.app.model.WatchRecord(itemGuid, itemTitle, itemTV, ep,
                itemPoster, itemCategory, parentGuid, ts, actualDuration));
        Map<String, Object> r = new HashMap<>();
        r.put("item_guid", itemGuid); r.put("media_guid", mediaGuid);
        r.put("video_guid", videoGuid != null ? videoGuid : "");
        r.put("audio_guid", audioGuid != null ? audioGuid : "");
        r.put("subtitle_guid", subtitleGuid != null ? subtitleGuid : "_no_display_");
        r.put("resolution", resolution != null ? resolution : ""); r.put("bitrate", 0);
        r.put("ts", ts); r.put("duration", itemDuration > 0 ? itemDuration : player.getDuration()/1000);
        apiManager.getApi().recordPlayStatus(r).enqueue(new retrofit2.Callback<ApiResponse<Object>>() {
            @Override public void onResponse(retrofit2.Call<ApiResponse<Object>> call, retrofit2.Response<ApiResponse<Object>> response) {}
            @Override public void onFailure(retrofit2.Call<ApiResponse<Object>> call, Throwable t) {}
        });
    }

    // ========== 按键 ==========

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (isLocked) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (btnLock.hasFocus() || controller.hasFocus()) {
                    controller.clearFocus();
                    btnLock.clearFocus();
                    return true;
                }
                isLocked = false;
                btnLock.setImageResource(R.drawable.ic_unlock);
                showCtrl(true);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                isLocked = false;
                btnLock.setImageResource(R.drawable.ic_unlock);
                showCtrl(true);
                return true;
            }
            return true;
        }
        if (ctrlVis) {
            switch (k) {
                case KeyEvent.KEYCODE_BACK:
                    if (infoVis) { toggleInfo(); return true; }
                    if (controller.hasFocus() || btnDanmu.hasFocus() || btnLock.hasFocus() || btnCloudMode.hasFocus() || btnBrightness.hasFocus() || topBar.hasFocus()) {
                        controller.clearFocus();
                        topBar.clearFocus();
                        btnDanmu.clearFocus();
                        btnLock.clearFocus();
                        return true;
                    }
                    if (backPressedTime + 2000 > System.currentTimeMillis()) {
                        restoreOrientation();
                        finish();
                    } else {
                        backPressedTime = System.currentTimeMillis();
                        Toast.makeText(this, "再按一次退出播放", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                // LEFT/RIGHT 由 SeekBar 自身处理（已设 keyProgressIncrement=5000）
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (seekBar.hasFocus() || btnRewind.hasFocus() || btnForward.hasFocus()
                            || btnSpeed.hasFocus() || btnRatio.hasFocus() || btnInfo.hasFocus()
                            || btnEpisodeList.hasFocus() || btnNextEp.hasFocus() || btnBrightness.hasFocus()) {
                        return true;
                    }
                    togglePlay(); return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (btnDanmu.hasFocus() || btnLock.hasFocus() || topBar.hasFocus()) {
                        showCtrl(false);
                        return true;
                    }
                    return super.onKeyDown(k, e);
                case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_MENU:
                    toggleInfo(); return true;
            }
            return super.onKeyDown(k, e);
        } else {
            switch (k) {
                case KeyEvent.KEYCODE_BACK:
                    if (backPressedTime + 2000 > System.currentTimeMillis()) {
                        restoreOrientation();
                        finish();
                    } else {
                        backPressedTime = System.currentTimeMillis();
                        Toast.makeText(this, "再按一次退出播放", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                    showCtrl(true); return true;
                case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_MENU:
                    toggleInfo(); return true;
            }
            return super.onKeyDown(k, e);
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private boolean isTvDevice() {
        android.app.UiModeManager uiModeManager = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        return uiModeManager != null
                && uiModeManager.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }

    @Override
    public void finish() {
        // 退出时恢复系统亮度
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = -1f; // 恢复系统默认
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
        super.finish();
    }

    private void restoreOrientation() {
        if (isTvDevice()) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    private String fmt(long ms) {
        if (ms <= 0) return "00:00";
        int s = (int)(ms/1000), m = s/60, h = m/60; s %= 60; m %= 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    @Override protected void onPause() {
        super.onPause();
        restoreOrientation();
    }
    @Override protected void onStop() { super.onStop(); saveProgress(); if (player != null) player.setPlayWhenReady(false); }
    @Override protected void onDestroy() {
        super.onDestroy(); handler.removeCallbacksAndMessages(null);
        if (danmuView != null) { danmuView.stop(); danmuView.clear(); }
        if (player != null) { player.release(); player = null; }
    }
}
