package com.fntv.app;

import android.content.Context;
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
    private Button btnCloudMode, btnBrightness, btnSkip;
    private boolean introSkipped = false, outroSkipped = false;
    private float speedBeforeLongPress = 1.0f;
    private DanmuView danmuView;
    private View controller, infoPanel, topBar;
    private boolean isLocked = false;
    private DanmuManager danmuManager;

    private Handler handler = new Handler(Looper.getMainLooper());
    private String itemGuid, baseUrl, itemTitle, itemTV, itemPoster, itemCategory, parentGuid;
    private long itemDuration;
    private FnApiManager apiManager;
    private String mediaGuid, videoGuid, audioGuid, subtitleGuid, resolution;
    private boolean seeked = false, ctrlVis = false, infoVis = false;
    private long seekTs = 0;
    private float[] speeds = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f};
    private int speedIdx = 0, ratioIdx = 0;
    private boolean isHwDecode = true;
    private EpisodeManager episodeManager;
    private int seasonNumber = 1;
    private long backPressedTime = 0;
    private CloudStreamManager cloudStreamManager;
    private boolean useHls = false;
    private int seekStep = 10000;
    private int streamBitrate = 0; // bps 来自 stream API
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
    private boolean hdrNotified = false; // HDR 已提示过一次
    private boolean firstReady = true;   // 首次进入 READY（用于控制初始 UI 显示）
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
        btnLock = (ImageView) findViewById(R.id.btnLock);
        tvTitle = findViewById(R.id.tvTitle);
        tvDanmuStatus = findViewById(R.id.tvDanmuStatus);
        btnCloudMode = findViewById(R.id.btnCloudMode);
        tvDanmuMatch = findViewById(R.id.tvDanmuMatch);
        tvSpeedHint = findViewById(R.id.tvSpeedHint);
        topBar = findViewById(R.id.topBar);
        controller = findViewById(R.id.controller);
        infoPanel = findViewById(R.id.infoPanel);
        infoText = findViewById(R.id.infoText);
        infoTextAudio = findViewById(R.id.infoTextAudio);
        infoTextExtra = findViewById(R.id.infoTextExtra);

        initPlayer();

        danmuManager = new DanmuManager(this, new DanmuManager.DataProvider() {
            @Override public Player getPlayer() { return player; }
            @Override public long getItemDuration() { return itemDuration; }
            @Override public String getItemTV() { return itemTV; }
            @Override public String getItemTitle() { return itemTitle; }
            @Override public String getItemGuid() { return itemGuid; }
            @Override public String getParentGuid() { return parentGuid; }
        }, danmuView, tvDanmuStatus, tvDanmuMatch, btnDanmu, prefs);
        danmuManager.initFromPrefs();

        findViewById(android.R.id.content).setOnTouchListener(new View.OnTouchListener() {
            private boolean longPressing = false;
            private android.os.Handler longPressHandler = new android.os.Handler(Looper.getMainLooper());
            @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                if (isLocked) {
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        showCtrl(true);
                    }
                    return true;
                }
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        longPressing = false;
                        longPressHandler.postDelayed(() -> {
                            longPressing = true;
                            if (player != null) {
                                speedBeforeLongPress = player.getPlaybackParameters().speed;
                                player.setPlaybackSpeed(2.0f);
                                danmuView.setPlaybackSpeed(2.0f);
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
                                danmuView.setPlaybackSpeed(speedBeforeLongPress);
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

        episodeManager = new EpisodeManager(new EpisodeManager.Callback() {
            @Override public String getBaseUrl() { return baseUrl; }
            @Override public String getParentGuid() { return parentGuid; }
            @Override public String getItemGuid() { return itemGuid; }
            @Override public int getEpisodeNumber() { return getIntent().getIntExtra("episode_number", 0); }
            @Override public FnApiManager getApiManager() { return apiManager; }
            @Override public Context getContext() { return PlayerActivity.this; }
            @Override public void onSwitchEpisode(String guid, String title) {
                introSkipped = false;
                outroSkipped = false;
                itemGuid = guid;
                itemTitle = title;
                mediaGuid = null;
                seeked = false;
                seekTs = 0;
                episodeManager.reset();
                loadPlayInfo();
            }
        }, btnEpisodeList, btnNextEp);

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
        btnDanmu.setOnClickListener(v -> danmuManager.showSettings());
        btnLock.setOnClickListener(v -> {
            isLocked = !isLocked;
            btnLock.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
            if (isLocked) {
                topBar.setVisibility(View.INVISIBLE);
                controller.setVisibility(View.INVISIBLE);
                btnLock.setVisibility(View.INVISIBLE);
            } else {
                showCtrl(true);
            }
        });
        btnCloseInfo.setOnClickListener(v -> { infoPanel.setVisibility(View.GONE); infoVis = false; });
        btnBrightness = findViewById(R.id.btnBrightness);
        if (btnBrightness != null) {
            btnBrightness.setOnClickListener(v -> showBrightnessDialog());
        }
        btnSkip = findViewById(R.id.btnSkip);
        if (btnSkip != null) {
            btnSkip.setOnClickListener(v -> showIntroOutroDialog());
        }
        // 应用保存的亮度和 HDR 设置
        int savedBright = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getInt("video_brightness", 100);
        if (savedBright != 100) applyBrightness(savedBright);
        applyHdrMode();
        btnEpisodeList.setOnClickListener(v -> episodeManager.showPicker());
        btnNextEp.setOnClickListener(v -> episodeManager.playNext());

        cloudStreamManager = new CloudStreamManager(new CloudStreamManager.Callback() {
            @Override public String getBaseUrl() { return baseUrl; }
            @Override public String getMediaGuid() { return mediaGuid; }
            @Override public FnApiManager getApiManager() { return apiManager; }
            @Override public Context getContext() { return PlayerActivity.this; }
            @Override public SharedPreferences getPrefs() { return getSharedPreferences("fntv_prefs", MODE_PRIVATE); }
            @Override public void onStreamInfoParsed(CloudStreamManager.StreamInfo info) {
                streamBitrate = info.bitrate;
                streamVCodec = info.vCodec;
                streamVProfile = info.vProfile;
                streamVWidth = info.width;
                streamVHeight = info.height;
                streamVBitDepth = info.bitDepth;
                streamVHdr = info.vHdr;
                streamVPixFmt = info.vPixFmt;
                streamVColor = info.vColor;
                streamVFps = info.vFps;
                streamDuration = info.duration;
                streamFileSize = info.fileSize;
                streamContainer = info.container;
                streamAudioTracks = info.audioTracks;
                streamSubtitleTracks = info.subtitleTracks;
                if (streamVCodec.isEmpty() || streamContainer.isEmpty()) {
                    probeWithMediaExtractor();
                }
            }
            @Override public void onStreamDataFailed() { startPlayback(); }
            @Override public void startPlayback() { PlayerActivity.this.startPlayback(); }
            @Override public void onTrackChanged() {
                final Format oldFmt = player != null ? player.getAudioFormat() : null;
                final int[] tries = {6};
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (player == null) return;
                        Format newFmt = player.getAudioFormat();
                        if (newFmt != null && newFmt != oldFmt) {
                            updateInfo();
                        } else if (tries[0] > 0) {
                            tries[0]--;
                            handler.postDelayed(this, 500);
                        }
                    }
                });
            }
            @Override public void reloadPlayback() {
                mediaGuid = null;
                seeked = false;
                seekTs = 0;
                cloudStreamManager.resetForQualitySwitch();
                loadPlayInfo();
            }
            @Override public void probeWithMediaExtractor() { PlayerActivity.this.probeWithMediaExtractor(); }
            @Override public void onCloudBtnVisibilityChanged(boolean vis) {
                if (tvDanmuMatch != null) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) tvDanmuMatch.getLayoutParams();
                    if (lp != null) {
                        lp.rightMargin = vis ? (int) (100 * getResources().getDisplayMetrics().density) : 20;
                        tvDanmuMatch.setLayoutParams(lp);
                    }
                }
            }
            @Override public void runOnUiThread(Runnable r) { PlayerActivity.this.runOnUiThread(r); }
        }, btnCloudMode, getSharedPreferences("fntv_prefs", MODE_PRIVATE));
        cloudStreamManager.initFromPrefs();
        cloudStreamManager.setPlayer(player);

        // 音轨/字幕选择按钮
        Button btnAudioTrack = findViewById(R.id.btnAudioTrack);
        Button btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack);
        if (btnAudioTrack != null) {
            btnAudioTrack.setOnClickListener(v -> cloudStreamManager.showAudioTrackDialog(PlayerActivity.this));
        }
        if (btnSubtitleTrack != null) {
            btnSubtitleTrack.setOnClickListener(v -> cloudStreamManager.showSubtitleTrackDialog(PlayerActivity.this));
        }

        setupFocusAutoHide();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null) {
                    player.seekTo(p);
                    tvTime.setText(FormatUtils.fmt(p) + " / " + FormatUtils.fmt(player.getDuration()));
                    if (danmuManager != null) danmuManager.onSeekTo(p);
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
                    startSave(); updateTime();
                    if (firstReady) { showCtrl(true); firstReady = false; }
                    btnPlayPause.setText(player.isPlaying() ? "暂停" : "播放");
                    if (danmuManager != null) danmuManager.onPlayerReady();
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
                    // 片头跳过（只开始触发一次，片尾在 updateTime 实时监测）
                    if (!introSkipped && (parentGuid != null || (itemTV != null && !itemTV.isEmpty()))) {
                        SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
                        String skipId = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : itemTV;
                        int introSec = sp.getInt("skip_" + skipId + "_intro", 0);
                        if (introSec > 0) {
                            int pos = (int)(player.getCurrentPosition() / 1000);
                            if (pos < introSec) { player.seekTo(introSec * 1000L); danmuManager.showDanmuStatus("跳过片头 " + introSec + "秒"); }
                            introSkipped = true;
                        }
                    }
                } else if (s == Player.STATE_ENDED) {
                    Log.d(TAG, "STATE_ENDED hasNext=" + (episodeManager != null && episodeManager.hasNext()));
                    if (episodeManager != null && episodeManager.hasNext()) {
                        episodeManager.playNext();
                    }
                } else {
                    stopSave();
                    if (danmuManager != null) danmuManager.onPlayerPause();
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
                // 按响应码切换
                int code = OkHttpExoDataSource.lastResponseCode;
                if (code == 200 && !useHls && cloudStreamManager.hasDirectUrl()) {
                    useHls = true;
                    Log.d(TAG, "响应200，切换到HLS");
                    switchMediaSource(true);
                    return;
                } else if (code == 206 && useHls && cloudStreamManager.hasDirectUrl()) {
                    useHls = false;
                    Log.d(TAG, "响应206，切换到渐进式");
                    switchMediaSource(false);
                    return;
                } else if (cloudStreamManager.hasDirectUrl() && useHls) {
                    // 非200/206时按渐进式重试
                    useHls = false;
                    Log.d(TAG, "非200/206响应码(" + code + ")，切换到渐进式");
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
        hdrNotified = false;
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
                    if (danmuManager != null) danmuManager.loadDanmu(matchName, itemGuid);
                    if (info.item != null && info.item.mediaStream != null
                            && info.item.mediaStream.resolutions != null
                            && !info.item.mediaStream.resolutions.isEmpty())
                        resolution = info.item.mediaStream.resolutions.get(0);

                    // 获取直链信息，获取完后开始播放
                    cloudStreamManager.fetchDirectLink(itemGuid, mediaGuid);
                }
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {}
        });
    }

    /** 开始播放（加载到 ExoPlayer） */
    private void startPlayback() {
        if (mediaGuid == null) return;
        CloudStreamManager.PlaybackConfig cfg = cloudStreamManager.getPlaybackConfig(baseUrl, mediaGuid);
        OkHttpExoDataSource.setChunkedMode(cfg.chunkedModeSize);
        useHls = cfg.hls;
        com.google.android.exoplayer2.upstream.DataSource.Factory f = () -> new OkHttpExoDataSource(apiManager.getStreamClient());
        if (useHls) {
            player.setMediaSource(new com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory(f).createMediaSource(MediaItem.fromUri(cfg.url)));
            Log.d(TAG, "播放器: HLS");
        } else {
            player.setMediaSource(new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(f, new DefaultExtractorsFactory()).createMediaSource(MediaItem.fromUri(cfg.url)));
            Log.d(TAG, "播放器: 渐进式");
        }
        player.prepare(); player.setPlayWhenReady(true);
        Log.d(TAG, "startPlayback: parentGuid=" + parentGuid + " episodeLoaded=" + (episodeManager != null && episodeManager.isLoaded()) + " loadingEp=" + (episodeManager != null && episodeManager.isLoading()));
        if (parentGuid != null && !parentGuid.isEmpty() && episodeManager != null && !episodeManager.isLoaded() && !episodeManager.isLoading())
            episodeManager.loadList(parentGuid);
    }

    // ========== 剧集移至 EpisodeManager ==========

    // ========== 控制 ==========

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            btnPlayPause.setText("播放");
            if (danmuManager != null) danmuManager.onPlayerPause();
        } else {
            player.play();
            btnPlayPause.setText("暂停");
            updateTime();
            if (danmuManager != null) danmuManager.onPlayerReady();
        }
    }

    private void seekRel(int ms) {
        if (player == null) return;
        long p = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + ms));
        player.seekTo(p);
        if (danmuManager != null) danmuManager.onSeekTo(p);
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.length;
        float s = speeds[speedIdx];
        btnSpeed.setText((s == (int)s ? String.valueOf((int)s) : String.valueOf(s)) + "x");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) player.setPlaybackSpeed(s);
        if (danmuManager != null) danmuView.setPlaybackSpeed(s);
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
                    if (!hdrNotified) {
                        hdrNotified = true;
                        danmuManager.showDanmuStatus("HDR 模式已激活");
                    }
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


    private void showIntroOutroDialog() {
        SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        String skipId = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : itemTV;
        String key = "skip_" + skipId;
        int defIntro = p.getInt(key + "_intro", 0);
        int defOutro = p.getInt(key + "_outro", 0);

        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(R.layout.dialog_skip);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xDD1A1A1A));

        // 标题
        TextView tvTitle = dialog.findViewById(R.id.tv_skip_title);
        if (tvTitle != null) tvTitle.setText((itemTV != null ? itemTV : "当前视频") + " - 跳过设置");

        // 片头滑条
        final TextView introLabel = dialog.findViewById(R.id.dm_label);
        final SeekBar introSb = dialog.findViewById(R.id.dm_seekbar);
        // 片尾滑条（第二个 include 的 ID 是 dm_outro，里面的子控件 ID 相同）
        final TextView outroLabel = ((ViewGroup)dialog.findViewById(R.id.dm_outro)).findViewById(R.id.dm_label);
        final SeekBar outroSb = ((ViewGroup)dialog.findViewById(R.id.dm_outro)).findViewById(R.id.dm_seekbar);

        if (introLabel != null) introLabel.setText("跳过片头: " + defIntro + "秒");
        if (outroLabel != null) outroLabel.setText("跳过片尾: " + defOutro + "秒");

        if (introSb != null) {
            introSb.setMax(600);
            introSb.setProgress(defIntro);
            introSb.setKeyProgressIncrement(1);
            introSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int v, boolean u) {
                    if (introLabel != null) introLabel.setText("跳过片头: " + v + "秒");
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
        if (outroSb != null) {
            outroSb.setMax(600);
            outroSb.setProgress(defOutro);
            outroSb.setKeyProgressIncrement(1);
            outroSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int v, boolean u) {
                    if (outroLabel != null) outroLabel.setText("跳过片尾: " + v + "秒");
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }

        Button reset = dialog.findViewById(R.id.dm_reset);
        Button cancel = dialog.findViewById(R.id.dm_cancel);
        Button ok = dialog.findViewById(R.id.dm_ok);

        if (reset != null) reset.setOnClickListener(v -> { if (introSb != null) introSb.setProgress(0); if (outroSb != null) outroSb.setProgress(0); });
        if (cancel != null) cancel.setOnClickListener(v -> dialog.dismiss());
        if (ok != null) ok.setOnClickListener(v -> {
            if (introSb != null) p.edit().putInt(key + "_intro", introSb.getProgress()).apply();
            if (outroSb != null) p.edit().putInt(key + "_outro", outroSb.getProgress()).apply();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void switchMediaSource(boolean toHls) {
        if (player == null || !cloudStreamManager.hasDirectUrl()) return;
        handler.post(() -> {
            String u = cloudStreamManager.getCloudDirectUrl();
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
            danmuManager.showDanmuStatus("HDR 已开启");
        } else {
            getWindow().setColorMode(0);
            if (videoHdr) danmuManager.showDanmuStatus("HDR 已关闭");
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
        if (controller.hasFocus() || btnDanmu.hasFocus() || btnLock.hasFocus() || btnCloudMode.hasFocus() || btnBrightness.hasFocus() || btnSkip.hasFocus() || topBar.hasFocus()) {
            resetHideTimer();
            return;
        }
        showCtrl(false);
    };

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
        if (btnSkip != null) btnSkip.setOnFocusChangeListener(l);
        View btnAudioTrack = findViewById(R.id.btnAudioTrack);
        View btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack);
        if (btnAudioTrack != null) btnAudioTrack.setOnFocusChangeListener(l);
        if (btnSubtitleTrack != null) btnSubtitleTrack.setOnFocusChangeListener(l);
    };

    private void updateTime() {
        if (player == null) return;
        long cur = player.getCurrentPosition(), dur = player.getDuration();
        tvTime.setText(FormatUtils.fmt(cur) + " / " + FormatUtils.fmt(dur));
        seekBar.setMax((int) Math.max(dur, 1)); seekBar.setProgress((int) cur);
        seekBar.setKeyProgressIncrement(5000); // 方向键每次 5 秒
        if (danmuManager != null) danmuManager.setPlayTime(cur);
        // 实时监测片尾位置
        if (!outroSkipped && dur > 0) {
            SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
            String sid = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : (itemTV != null ? itemTV : null);
            if (sid != null) {
                int outroSec = sp.getInt("skip_" + sid + "_outro", 0);
                if (outroSec > 0) Log.d(TAG, "片尾检测: cur=" + (cur/1000) + "s dur=" + (dur/1000) + "s 阈值=" + (dur/1000 - outroSec) + "s");
                if (outroSec > 0 && cur / 1000 > dur / 1000 - outroSec) {
                outroSkipped = true;
                danmuManager.showDanmuStatus("检测到片尾");
                if (episodeManager != null && episodeManager.hasNext())
                    handler.postDelayed(() -> episodeManager.playNext(), 1000);
                }
            }
        }
        handler.postDelayed(timeR, 200);
    }
    private final Runnable timeR = () -> { if (player != null && player.isPlaying()) updateTime(); };

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

    private void updateInfo() {
        if (player == null) return;
        Format vf = player.getVideoFormat();
        Format af = player.getAudioFormat();

        // 视频（左列）
        StringBuilder v = new StringBuilder();
        v.append("── 视频 ──\n");
        String codec = FormatUtils.fmtVideoCodec(streamVCodec.isEmpty() ? (vf != null ? vf.codecs : null) : streamVCodec);
        v.append("编码 ").append(codec).append("\n");
        int w = streamVWidth > 0 ? streamVWidth : (vf != null ? vf.width : 0);
        int h = streamVHeight > 0 ? streamVHeight : (vf != null ? vf.height : 0);
        if (w > 0 && h > 0) v.append("分辨率 ").append(w).append("×").append(h).append("\n");
        float fps = 0;
        if (!streamVFps.isEmpty()) { try { fps = Float.parseFloat(streamVFps.replaceAll("[^0-9.]", "")); } catch (Exception ignored) {} }
        if (fps <= 0 && vf != null) fps = vf.frameRate;
        if (fps > 0) v.append("帧率 ").append(String.format("%.3f fps", fps)).append("\n");
        if (streamBitrate > 0) v.append("码率 ").append(FormatUtils.formatBitrate(streamBitrate)).append("\n");
        else if (vf != null && vf.bitrate > 0) v.append("码率 ").append(vf.bitrate/1000).append("kbps\n");
        if (streamVBitDepth > 0) v.append("色深 ").append(streamVBitDepth).append("bit\n");
        if (streamVHdr || (vf != null && vf.colorInfo != null)) v.append("HDR10\n");
        v.append("解码 ").append(actualVideoDecoder.isEmpty() ? (isHwDecode ? "硬解" : "软解") : actualVideoDecoder);
        infoText.setText(v.toString());

        // 音频（右列）
        StringBuilder a = new StringBuilder();
        a.append("── 音频 ──\n");
        if (af != null) {
            String ac = FormatUtils.fmtAudioCodec(af.codecs != null ? af.codecs : af.sampleMimeType);
            a.append("编码 ").append(ac).append("\n");
            int ch = af.channelCount;
            a.append("声道 ").append(ch > 0 ? (ch == 8 ? "7.1" : ch == 6 ? "5.1" : ch + "ch") : "?").append("\n");
            a.append("采样 ").append(af.sampleRate > 0 ? af.sampleRate + "Hz" : "?").append("\n");
            if (af.bitrate > 0) a.append("码率 ").append(af.bitrate/1000).append("kbps\n");
            a.append("解码 ").append(actualAudioDecoder.isEmpty() ? (isHwDecode ? "硬解" : "软解") : actualAudioDecoder);
            // 显示用户选择的音轨（如有）
            String selAudio = cloudStreamManager != null ? cloudStreamManager.getLastAudioTrackLabel() : "";
            if (!selAudio.isEmpty() && !selAudio.equals("默认")) {
                a.append("\n已选 ").append(selAudio);
            }
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
                String an = FormatUtils.fmtAudioCodec(asi.codecName);
                String al = asi.language != null && !asi.language.isEmpty() ? asi.language : "";
                String ach = asi.channels > 0 ? (asi.channels == 8 ? "7.1" : asi.channels == 6 ? "5.1" : asi.channels + "ch") : "?";
                String ab = asi.bps > 0 ? " " + FormatUtils.formatBitrate(asi.bps) : "";
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
            x.append("时长 ").append(FormatUtils.fmtTime((int)(durMs/1000)));
        }
        if (infoTextExtra != null) infoTextExtra.setText(x.toString());
    }

    // ========== 弹幕全部移至 DanmuManager ==========

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
                    if (controller.hasFocus() || btnDanmu.hasFocus() || btnLock.hasFocus() || btnCloudMode.hasFocus() || btnBrightness.hasFocus() || btnSkip.hasFocus() || topBar.hasFocus()) {
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
                            || btnEpisodeList.hasFocus() || btnNextEp.hasFocus() || btnBrightness.hasFocus() || btnSkip.hasFocus()) {
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

    @Override protected void onPause() {
        super.onPause();
        restoreOrientation();
    }
    @Override protected void onStop() { super.onStop(); saveProgress(); if (player != null) player.setPlayWhenReady(false); }
    @Override protected void onDestroy() {
        super.onDestroy(); handler.removeCallbacksAndMessages(null);
        if (danmuManager != null) { danmuManager.destroy(); }
        if (player != null) { player.release(); player = null; }
    }
}


