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
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.*;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
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
    private TextView tvTitle;
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
    private static final String TAG = "Player";

    private static final int[] RATIO_MODES = {0, 3};
    private static final String[] RATIO_LABELS = {"适应", "拉伸"};

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
        topBar = findViewById(R.id.topBar);
        controller = findViewById(R.id.controller);
        infoPanel = findViewById(R.id.infoPanel);
        infoText = findViewById(R.id.infoText);

        initPlayer();

        boolean savedDanmuOn = prefs.getBoolean("danmu_on", true);
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

        findViewById(android.R.id.content).setOnClickListener(v -> {
            if (ctrlVis) togglePlay();
            else showCtrl(true);
        });

        btnPlayPause.setOnClickListener(v -> togglePlay());
        btnRewind.setOnClickListener(v -> seekRel(-10000));
        btnForward.setOnClickListener(v -> seekRel(10000));
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
        btnEpisodeList.setOnClickListener(v -> showEpisodePicker());
        btnNextEp.setOnClickListener(v -> playNextEp());
        setupFocusAutoHide();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null) player.seekTo(p);
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
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this);
        if ("software".equals(getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("decoder_mode", "hardware"))) {
            rf.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
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

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                tvBuffering.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (s == Player.STATE_READY) {
                    if (!seeked && seekTs > 0) { player.seekTo(seekTs); seeked = true; }
                    startSave(); updateTime(); showCtrl(true);
                    btnPlayPause.setText(player.isPlaying() ? "暂停" : "播放");
                    if (danmuOn && danmuView != null) danmuView.resume();
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
            @Override public void onPlayerError(PlaybackException e) { Log.e(TAG, e.getMessage()); }
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
                    startPlayback();
                }
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {}
        });
    }

    private void startPlayback() {
        if (mediaGuid == null) return;
        String url = baseUrl + "/v/api/v1/media/range/" + mediaGuid;
        com.google.android.exoplayer2.upstream.DataSource.Factory f = () -> new OkHttpExoDataSource(apiManager.getClient());
        DefaultExtractorsFactory ef = new DefaultExtractorsFactory(); ef.setConstantBitrateSeekingEnabled(true);
        player.setMediaSource(new ProgressiveMediaSource.Factory(f, ef).createMediaSource(MediaItem.fromUri(url)));
        player.prepare(); player.setPlayWhenReady(true);
        if (parentGuid != null && !parentGuid.isEmpty() && episodeList == null && !loadingEpisodes) loadEpisodeList();
    }

    // ========== 剧集相关 ==========

    private void loadEpisodeList() {
        loadingEpisodes = true;
        apiManager.getApi().getEpisodeList(parentGuid).enqueue(
                new retrofit2.Callback<ApiResponse<List<PlayListItem>>>() {
                    @Override public void onResponse(retrofit2.Call<ApiResponse<List<PlayListItem>>> call,
                                                     retrofit2.Response<ApiResponse<List<PlayListItem>>> resp) {
                        loadingEpisodes = false;
                        if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0
                                && resp.body().data != null && !resp.body().data.isEmpty()) {
                            episodeList = resp.body().data;
                            for (int i = 0; i < episodeList.size(); i++) {
                                if (episodeList.get(i).guid.equals(itemGuid)) { currentEpIndex = i; break; }
                            }
                            btnEpisodeList.setVisibility(View.VISIBLE);
                            if (currentEpIndex >= 0 && currentEpIndex < episodeList.size() - 1)
                                btnNextEp.setVisibility(View.VISIBLE);
                        }
                    }
                    @Override public void onFailure(retrofit2.Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                        loadingEpisodes = false;
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
        if (playerView != null) playerView.setResizeMode(RATIO_MODES[ratioIdx]);
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
        if (controller.hasFocus() || btnDanmu.hasFocus() || btnLock.hasFocus() || topBar.hasFocus()) {
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
    };

    private void updateTime() {
        if (player == null) return;
        long cur = player.getCurrentPosition(), dur = player.getDuration();
        tvTime.setText(fmt(cur) + " / " + fmt(dur));
        seekBar.setMax((int) Math.max(dur, 1)); seekBar.setProgress((int) cur);
        if (danmuView != null) danmuView.setPlayTime(cur);
        handler.postDelayed(timeR, 500);
    }
    private final Runnable timeR = () -> { if (player != null && player.isPlaying()) updateTime(); };

    private void updateInfo() {
        if (player == null) return;
        StringBuilder s = new StringBuilder();
        Format vf = player.getVideoFormat(); Format af = player.getAudioFormat();
        if (vf != null) {
            s.append("分辨率 ").append(vf.width).append("x").append(vf.height);
            if (vf.codecs != null) s.append("  ").append(vf.codecs);
            s.append("\n码率 ").append(vf.bitrate > 0 ? vf.bitrate/1000 + "kbps" : "?");
            s.append("  帧率 ").append(vf.frameRate > 0 ? String.format("%.2f fps", vf.frameRate) : "未知");
            s.append("  ").append(vf.colorInfo != null ? "HDR" : "SDR");
        }
        if (af != null) {
            s.append("\n音频 ").append(af.codecs != null ? af.codecs : "?");
            s.append("  ").append(af.channelCount > 0 ? af.channelCount + "ch" : "?");
            s.append("  ").append(af.sampleRate > 0 ? af.sampleRate/1000 + "kHz" : "?");
            s.append("  ").append(af.bitrate > 0 ? af.bitrate/1000 + "kbps" : "?");
        }
        s.append("\n解码 ").append(isHwDecode ? "硬解" : "软解");
        infoText.setText(s.toString());
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
                    .putFloat("danmu_rowspacing",rs).apply();
            if(isOn[0]) { danmuOn=true; danmuView.setVisibility(View.VISIBLE); btnDanmu.setText("弹✕");
                danmuView.setAreaPct(a); danmuView.setSpeedMul(sp); danmuView.setOpacity(op);
                danmuView.setFontSize(fs); danmuView.setShowOutline(outline[0]);
                danmuView.setMaxActive(mx); danmuView.setDensityPct(dn);
                danmuView.setRowSpacing(rs);
                danmuView.start();
                if(danmuItems!=null) danmuView.loadDanmu(danmuItems);
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
                            Button b=new Button(this);
                            b.setBackgroundResource(R.drawable.bg_search_item);
                            b.setText(t); b.setTextColor(0xFFEEEEEE);
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
                                if (which >= 0 && which < epCount && epIds[which] > 0)
                                    loadDanmuByEp(epIds[which], animeName != null ? animeName + " " + epLabels[which] : null);
                                else
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
                        if (parts.length >= 4) {
                            try { dc.color = 0xFF000000 | (int) Long.parseLong(parts[3].trim()); }
                            catch (Exception e2) { dc.color = 0xFFFFFFFF; }
                        } else {
                            dc.color = 0xFFFFFFFF;
                        }
                    } else {
                        try { dc.time = Float.parseFloat(pVal); }
                        catch (Exception e2) { dc.time = 0; }
                        dc.color = 0xFF000000 | o.optInt("c", 0xFFFFFF);
                    }

                    list.add(dc);
                }

                java.util.Collections.sort(list, (a, b) -> Float.compare(a.time, b.time));

                final int loaded = list.size();
                runOnUiThread(()->{
                    danmuItems = list;
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

    private void showDanmuStatus(String msg) {
        runOnUiThread(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "[弹幕] " + msg);
        });
    }

    private void loadDanmu(String title, String guid) {
        if (danmuUrl.isEmpty() || title == null) {
            showDanmuStatus("弹幕: 未配置服务器");
            return;
        }
        showDanmuStatus("弹幕: 正在匹配 \"" + title + "\"...");
        new Thread(() -> {
            try {
                int episodeId = 0;
                String matchedName = title;
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
                            String matchAnime = firstMatch.optString("animeTitle", "");
                            String matchEp = firstMatch.optString("episodeTitle", "");
                            if (!matchAnime.isEmpty())
                                matchedName = matchAnime + (matchEp.isEmpty() ? "" : " " + matchEp);
                            Log.d(TAG, "match ok: epId=" + episodeId + " name=" + matchedName);
                        }
                    }
                } catch (Exception ignored) {}
                if (episodeId <= 0) {
                    Log.d(TAG, "match failed, searching: " + title);
                    showDanmuStatus("弹幕: 搜索 \"" + title + "\"...");
                    try {
                        String enc = java.net.URLEncoder.encode(title, "UTF-8");
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
                            org.json.JSONObject first = animes.getJSONObject(0);
                            int aid = first.optInt("animeId", first.optInt("id", 0));
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
                                if (eps != null && eps.length() > 0)
                                    episodeId = eps.getJSONObject(0).optInt("episodeId", 0);
                            }
                        }
                    } catch (Exception e2) {
                        Log.d(TAG, "search fallback failed: " + e2.getMessage());
                    }
                }
                if (episodeId <= 0) {
                    showDanmuStatus("弹幕: 未匹配到番剧");
                    return;
                }
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
                    if (controller.hasFocus() || btnDanmu.hasFocus() || btnLock.hasFocus() || topBar.hasFocus()) {
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
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (seekBar.hasFocus()) {
                        int repL = e.getRepeatCount();
                        seekRel(repL < 3 ? -5000 : repL < 8 ? -15000 : -30000);
                        return true;
                    }
                    return super.onKeyDown(k, e);
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (seekBar.hasFocus()) {
                        int repR = e.getRepeatCount();
                        seekRel(repR < 3 ? 5000 : repR < 8 ? 15000 : 30000);
                        return true;
                    }
                    return super.onKeyDown(k, e);
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (seekBar.hasFocus() || btnRewind.hasFocus() || btnForward.hasFocus()
                            || btnSpeed.hasFocus() || btnRatio.hasFocus() || btnInfo.hasFocus()
                            || btnEpisodeList.hasFocus() || btnNextEp.hasFocus()) {
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
