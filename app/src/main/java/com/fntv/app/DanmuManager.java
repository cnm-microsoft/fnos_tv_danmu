package com.fntv.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.Player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 弹幕管理器 — 处理弹幕加载、匹配、搜索、设置等全部弹幕相关逻辑。
 */
public class DanmuManager {

    public interface DataProvider {
        Player getPlayer();
        long getItemDuration();
        String getItemTV();
        String getItemTitle();
        String getItemGuid();
        String getParentGuid();
    }

    private final Activity activity;
    private final DataProvider data;
    private final DanmuView danmuView;
    private final TextView tvDanmuStatus;
    private final TextView tvDanmuMatch;
    private final Button btnDanmu;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 弹幕状态
    private boolean danmuOn = false;
    private List<DanmuView.DanmuComment> danmuItems;
    private String pendingDanmuTitle;
    private String pendingDanmuGuid;
    private String danmuMatchedName = "";
    private String danmuUrl = "";

    private static final String TAG = "Player";

    private Runnable hideDanmuStatus;

    public DanmuManager(Activity activity, DataProvider data, DanmuView danmuView,
                        TextView tvDanmuStatus, TextView tvDanmuMatch, Button btnDanmu,
                        SharedPreferences prefs) {
        this.activity = activity;
        this.data = data;
        this.danmuView = danmuView;
        this.tvDanmuStatus = tvDanmuStatus;
        this.tvDanmuMatch = tvDanmuMatch;
        this.btnDanmu = btnDanmu;
        this.prefs = prefs;

        // 初始化弹幕服务器 URL
        String savedUrl = prefs.getString("danmu_url", "");
        if (savedUrl.isEmpty()) {
            String host = prefs.getString("host", "");
            host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
            savedUrl = "http://" + host + ":9321";
        }
        this.danmuUrl = savedUrl;

        // 弹幕匹配名跑马灯速度 hack
        if (tvDanmuMatch != null) {
            try {
                java.lang.reflect.Field f = TextView.class.getDeclaredField("mMarqueeSpeed");
                f.setAccessible(true);
                f.set(tvDanmuMatch, 2.0f);
            } catch (Exception ignored) {
            }
        }

        // hideDanmuStatus 必须在 tvDanmuStatus 赋值之后创建
        this.hideDanmuStatus = () -> {
            if (tvDanmuStatus != null) tvDanmuStatus.setVisibility(View.GONE);
        };
    }

    /** 从 SharedPreferences 恢复弹幕初始状态（onCreate 时调用） */
    public void initFromPrefs() {
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
    }

    /** 弹幕是否开启 */
    public boolean isEnabled() {
        return danmuOn;
    }

    /** 显示弹幕状态提示（可被外部调用，如 HDR/片头跳过提示） */
    public void showDanmuStatus(String msg) {
        activity.runOnUiThread(() -> {
            if (tvDanmuStatus != null) {
                tvDanmuStatus.setText(msg);
                tvDanmuStatus.setVisibility(View.VISIBLE);
                handler.removeCallbacks(hideDanmuStatus);
                handler.postDelayed(hideDanmuStatus, 6000);
            }
            Log.d(TAG, "[弹幕] " + msg);
        });
    }

    /** 更新弹幕匹配名显示 */
    private void updateDanmuMatchDisplay(String name) {
        danmuMatchedName = name != null ? name : "";
        activity.runOnUiThread(() -> {
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

    // ========== 弹幕主入口 ==========

    /** 加载弹幕（由 PlayerActivity 的 loadPlayInfo 回调中调用） */
    public void loadDanmu(String title, String guid) {
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
        java.util.regex.Matcher cacheEpM = Pattern.compile("[Ee](\\d+)").matcher(title);
        if (cacheEpM.find()) cacheTargetEp = Integer.parseInt(cacheEpM.group(1));

        // 检查缓存（异步，避免主线程网络请求）
        if (data.getItemTV() != null && !data.getItemTV().isEmpty() && cacheTargetEp > 0) {
            final int fTargetEp = cacheTargetEp;
            try {
                String cacheJson = prefs.getString("danmu_match_cache", "{}");
                JSONObject cache = new JSONObject(cacheJson);
                if (cache.has(data.getItemTV())) {
                    String val = cache.getString(data.getItemTV());
                    String[] parts = val.split("\\|", 2);
                    if (parts.length == 2) {
                        final int cachedAid = Integer.parseInt(parts[0]);
                        final String cachedName = parts[1];
                        showDanmuStatus("弹幕: 缓存命中 " + cachedName + "，匹配第" + fTargetEp + "集...");
                        Log.d(TAG, "缓存命中: " + data.getItemTV() + " -> " + cachedName + " (aid=" + cachedAid + ")");
                        new Thread(() -> {
                            try {
                                URL bu = new URL(danmuUrl + "/api/v2/bangumi/" + cachedAid);
                                HttpURLConnection bc = (HttpURLConnection) bu.openConnection();
                                bc.connect();
                                java.io.BufferedReader br2 = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(bc.getInputStream(), "UTF-8"));
                                StringBuilder bp2 = new StringBuilder();
                                String l3;
                                while ((l3 = br2.readLine()) != null) bp2.append(l3);
                                br2.close();
                                JSONObject bj2 = new JSONObject(bp2.toString());
                                JSONArray eps2 = null;
                                if (bj2.has("bangumi") && bj2.getJSONObject("bangumi").has("episodes"))
                                    eps2 = bj2.getJSONObject("bangumi").getJSONArray("episodes");
                                else if (bj2.has("episodes")) eps2 = bj2.getJSONArray("episodes");
                                else if (bj2.has("data") && bj2.getJSONObject("data").has("episodes"))
                                    eps2 = bj2.getJSONObject("data").getJSONArray("episodes");
                                if (eps2 != null) {
                                    for (int ei = 0; ei < eps2.length(); ei++) {
                                        JSONObject epo = eps2.getJSONObject(ei);
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
            } catch (Exception ignored) {
            }
        }

        // 缓存未命中或无缓存 → 直接自动匹配
        startAutoMatch(title, cacheTargetEp);
    }

    /** 播放器就绪时调用（恢复弹幕） */
    public void onPlayerReady() {
        if (danmuOn && danmuView != null) danmuView.resume();
    }

    /** 播放器暂停/缓冲时调用 */
    public void onPlayerPause() {
        if (danmuOn && danmuView != null) danmuView.pause();
    }

    /** 用户播放/暂停切换时调用 */
    public void onTogglePlay(boolean isPlaying) {
        if (isPlaying) {
            if (danmuOn && danmuView != null) danmuView.resume();
        } else {
            if (danmuOn && danmuView != null) danmuView.pause();
        }
    }

    /** seek 同步弹幕时间 */
    public void onSeekTo(long positionMs) {
        if (danmuView != null) danmuView.seekToTime(positionMs);
    }

    /** 更新时间同步弹幕进度（由 updateTime 定时调用） */
    public void setPlayTime(long positionMs) {
        if (danmuView != null) danmuView.setPlayTime(positionMs);
    }

    /** 释放资源 */
    public void destroy() {
        if (danmuView != null) {
            danmuView.stop();
            danmuView.clear();
        }
        handler.removeCallbacksAndMessages(null);
    }

    // ========== 弹幕设置对话框 ==========

    /** 显示弹幕设置弹窗 */
    public void showSettings() {
        SharedPreferences p = prefs;
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

        final android.app.Dialog dialog = new android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
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
        matchBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showDanmuSearch();
        });

        int opVal = Math.min(100, Math.max(0, (int) (opacity[0] * 100)));
        setupSlider(dialog, R.id.dm_opacity, "不透明度", opVal, 0, 100, "%");
        setupSlider(dialog, R.id.dm_area, "显示区域", area[0], 10, 80, "%");
        setupSlider(dialog, R.id.dm_fontsize, "字号", (int) fontSize[0], 12, 40, "");
        setupSlider(dialog, R.id.dm_rowspacing, "行间距", (int) (rowSpacing[0] * 100), 120, 300, "x");
        setupSlider(dialog, R.id.dm_speed, "速度", (int) (speed[0] * 100), 30, 300, "x");
        setupSlider(dialog, R.id.dm_density, "密度", density[0], 50, 100, "%");
        setupSlider(dialog, R.id.dm_maxactive, "同屏最大", maxActive[0], 10, 80, "");
        setupSlider(dialog, R.id.dm_offset, "时间偏移", offset[0] + 30, 0, 60, "s");
        setupSlider(dialog, R.id.dm_maxcomments, "加载上限", maxComments[0], 100, 50000, "");

        final Switch olSw = dialog.findViewById(R.id.dm_outline);
        olSw.setChecked(outline[0]);

        dialog.findViewById(R.id.dm_cancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.dm_ok).setOnClickListener(v -> {
            isOn[0] = sw.isChecked();
            outline[0] = olSw.isChecked();
            int a = readSlider(dialog, R.id.dm_area, 10);
            float sp = readSlider(dialog, R.id.dm_speed, 30) / 100f;
            float op = readSlider(dialog, R.id.dm_opacity, 0) / 100f;
            float fs = readSlider(dialog, R.id.dm_fontsize, 12);
            float rs = readSlider(dialog, R.id.dm_rowspacing, 120) / 100f;
            int dn = readSlider(dialog, R.id.dm_density, 50);
            int mx = readSlider(dialog, R.id.dm_maxactive, 10);
            int of = readSlider(dialog, R.id.dm_offset, 0) - 30;
            int mc = readSlider(dialog, R.id.dm_maxcomments, 100);

            p.edit().putBoolean("danmu_on", isOn[0]).putInt("danmu_area", a)
                    .putFloat("danmu_speed", sp).putFloat("danmu_opacity", op)
                    .putFloat("danmu_fontsize", fs).putBoolean("danmu_outline", outline[0])
                    .putInt("danmu_density", dn).putInt("danmu_maxactive", mx)
                    .putInt("danmu_offset", of).putInt("danmu_maxcomments", mc)
                    .putFloat("danmu_rowspacing", rs)
                    .putBoolean("danmu_scroll", swScroll.isChecked())
                    .putBoolean("danmu_top", swTop.isChecked())
                    .putBoolean("danmu_bottom", swBottom.isChecked()).apply();
            danmuView.setShowScroll(swScroll.isChecked());
            danmuView.setShowTop(swTop.isChecked());
            danmuView.setShowBottom(swBottom.isChecked());
            if (isOn[0]) {
                boolean wasOff = !danmuOn;
                danmuOn = true;
                danmuView.setVisibility(View.VISIBLE);
                btnDanmu.setText("弹✕");
                danmuView.setAreaPct(a);
                danmuView.setSpeedMul(sp);
                danmuView.setOpacity(op);
                danmuView.setFontSize(fs);
                danmuView.setShowOutline(outline[0]);
                danmuView.setMaxActive(mx);
                danmuView.setDensityPct(dn);
                danmuView.setRowSpacing(rs);
                danmuView.start();
                if (danmuItems != null) danmuView.loadDanmu(danmuItems);
                // 从关闭→打开时，触发一次匹配
                if (wasOff && pendingDanmuTitle != null) loadDanmu(pendingDanmuTitle, pendingDanmuGuid);
            } else {
                danmuOn = false;
                danmuView.setVisibility(View.GONE);
                btnDanmu.setText("弹");
                danmuView.stop();
                danmuView.clear();
            }
            dialog.dismiss();
        });
        dialog.show();
    }

    private void setupSlider(android.app.Dialog d, int id, String label, int val, int min, int max, String unit) {
        ViewGroup v = d.findViewById(id);
        if (v == null) return;
        TextView tv = v.findViewById(R.id.dm_label);
        SeekBar sb = v.findViewById(R.id.dm_seekbar);
        if (tv != null) {
            String display = String.valueOf(val);
            if (unit.equals("x")) display = String.format("%.1f", val / 100f);
            else if (unit.equals("%")) display = val + "%";
            else if (unit.equals("s")) display = (val - 30) + "s";
            tv.setText(label + "  " + display);
        }
        if (sb != null) {
            sb.setMax(max - min);
            sb.setProgress(val - min);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean u) {
                    int real = p + min;
                    String suffix;
                    if (unit.equals("x")) suffix = String.format("%.1f", real / 100f);
                    else if (unit.equals("%")) suffix = real + "%";
                    else if (unit.equals("s")) suffix = (real - 30) + "s";
                    else suffix = String.valueOf(real);
                    if (tv != null) tv.setText(label + "  " + suffix);
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
        }
    }

    private int readSlider(android.app.Dialog d, int id, int min) {
        SeekBar sb = d.findViewById(id).findViewById(R.id.dm_seekbar);
        return sb != null ? sb.getProgress() + min : min;
    }

    // ========== 弹幕搜索 ==========

    private void showDanmuSearch() {
        final android.app.Dialog dialog = new android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(R.layout.dialog_danmu_search);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xDD1A1A1A));
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        // 标题上加当前剧集信息
        TextView titleView = dialog.findViewById(R.id.dm_search_title);
        if (titleView != null) {
            String epInfo = "";
            if (pendingDanmuTitle != null) {
                java.util.regex.Matcher epM = java.util.regex.Pattern.compile("[Ee](\\d+)").matcher(pendingDanmuTitle);
                if (epM.find()) epInfo = " - 第" + epM.group(1) + "集";
            }
            if (data.getItemTV() != null && !data.getItemTV().isEmpty()) {
                titleView.setText("搜索弹幕 " + data.getItemTV() + epInfo);
            } else if (data.getItemTitle() != null) {
                titleView.setText("搜索弹幕 " + data.getItemTitle() + epInfo);
            }
        }

        final EditText input = dialog.findViewById(R.id.dm_search_input);
        final Button sBtn = dialog.findViewById(R.id.dm_search_btn);
        final LinearLayout results = dialog.findViewById(R.id.dm_search_results);
        final Button cancelBtn = dialog.findViewById(R.id.dm_search_cancel);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        String autoFill = data.getItemTV() != null && !data.getItemTV().isEmpty() ? data.getItemTV() : (data.getItemTitle() != null ? data.getItemTitle() : "");
        input.setText(autoFill);
        if (!autoFill.isEmpty()) input.setSelection(autoFill.length());

        dialog.show();

        sBtn.setOnClickListener(v -> {
            final String kw = input.getText().toString().trim();
            if (kw.isEmpty()) {
                Toast.makeText(activity, "请输入番剧名", Toast.LENGTH_SHORT).show();
                return;
            }
            results.removeAllViews();
            sBtn.setEnabled(false);
            sBtn.setText("搜索中...");
            sBtn.setTextColor(0xFF808080);
            Log.d(TAG, "搜索: " + kw);
            TextView ld = new TextView(activity);
            ld.setText("正在搜索  " + kw + "...");
            ld.setTextColor(0xFF808080);
            ld.setPadding(0, 12, 0, 10);
            ld.setTextSize(14);
            results.addView(ld);
            new Thread(() -> {
                try {
                    String enc = URLEncoder.encode(kw, "UTF-8");
                    URL u = new URL(danmuUrl + "/api/v2/search/anime?keyword=" + enc);
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.connect();
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                    r.close();
                    String raw = sb.toString();
                    JSONArray arr = null;
                    if (raw.startsWith("{")) {
                        JSONObject obj = new JSONObject(raw);
                        if (obj.has("data")) arr = obj.getJSONArray("data");
                        else if (obj.has("animes")) arr = obj.getJSONArray("animes");
                    }
                    if (arr == null) arr = new JSONArray(raw.trim());
                    final JSONArray finalArr = arr;
                    activity.runOnUiThread(() -> {
                        results.removeAllViews();
                        sBtn.setEnabled(true);
                        sBtn.setText("搜索");
                        if (finalArr.length() == 0) {
                            sBtn.setEnabled(true);
                            sBtn.setText("搜索");
                            sBtn.setTextColor(0xFFFFFFFF);
                            TextView e = new TextView(activity);
                            e.setText("未找到匹配结果");
                            e.setTextColor(0xFF808080);
                            e.setPadding(0, 20, 0, 10);
                            results.addView(e);
                            return;
                        }
                        // 当前剧集信息，拼接到搜索结果后面
                        for (int i = 0; i < Math.min(finalArr.length(), 20); i++) {
                            JSONObject o = finalArr.optJSONObject(i);
                            if (o == null) continue;
                            int aid = o.optInt("animeId", o.optInt("id", 0));
                            String t = o.optString("animeTitle", "");
                            if (t.isEmpty()) t = o.optString("title", "");
                            if (t.isEmpty()) t = o.optString("name", "?");
                            int epCount = o.optInt("episodeCount", 0);
                            String labelStr = epCount > 0 ? t + "  (" + epCount + "集)" : t;
                            Button b = new Button(activity);
                            b.setBackgroundResource(R.drawable.bg_search_item);
                            b.setText(labelStr);
                            b.setTextColor(0xFFEEEEEE);
                            b.setPadding(16, 14, 16, 14);
                            b.setAllCaps(false);
                            b.setTextSize(14);
                            b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                            b.setLayoutParams(new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                            ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(0, 0, 0, 6);
                            final String animeName = t;
                            b.setOnClickListener(btn -> {
                                dialog.dismiss();
                                loadDanmuById(aid, animeName);
                            });
                            results.addView(b);
                        }
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        results.removeAllViews();
                        sBtn.setEnabled(true);
                        sBtn.setText("搜索");
                        TextView er = new TextView(activity);
                        er.setText("搜索失败: " + e.getMessage());
                        er.setTextColor(0xFFFF6B6B);
                        er.setPadding(0, 20, 0, 10);
                        results.addView(er);
                    });
                }
            }).start();
        });
    }

    // ========== 弹幕加载 ==========

    private void loadDanmuById(int animeId) {
        loadDanmuById(animeId, null);
    }

    private void loadDanmuById(int animeId, String animeName) {
        showDanmuStatus("弹幕: 正在加载...");
        new Thread(() -> {
            try {
                URL u = new URL(danmuUrl + "/api/v2/bangumi/" + animeId);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.connect();
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
                r.close();
                JSONObject j = new JSONObject(sb.toString());
                JSONArray eps = null;
                if (j.has("bangumi") && j.getJSONObject("bangumi").has("episodes"))
                    eps = j.getJSONObject("bangumi").getJSONArray("episodes");
                else if (j.has("episodes")) eps = j.getJSONArray("episodes");
                else if (j.has("data") && j.getJSONObject("data").has("episodes"))
                    eps = j.getJSONObject("data").getJSONArray("episodes");
                if (eps == null || eps.length() == 0) {
                    showDanmuStatus("弹幕: 无剧集");
                    return;
                }

                final int epCount = eps.length();
                final String[] epLabels = new String[epCount];
                final int[] epIds = new int[epCount];
                for (int i = 0; i < epCount; i++) {
                    JSONObject epo = eps.getJSONObject(i);
                    int epNum = epo.optInt("episodeNumber", epo.optInt("ep", i + 1));
                    epIds[i] = epo.optInt("episodeId", epo.optInt("id", 0));
                    String epTitle = epo.optString("episodeTitle", "");
                    epLabels[i] = !epTitle.isEmpty() ? epTitle : "第" + epNum + "集";
                }

                activity.runOnUiThread(() -> {
                    final android.app.Dialog dialog = new android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
                    dialog.setContentView(R.layout.dialog_danmu_search);
                    dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xDD1A1A1A));

                    // 改标题（加上剧集信息）
                    TextView titleView2 = dialog.findViewById(R.id.dm_search_title);
                    if (titleView2 != null) {
                        String epInfo2 = "";
                        if (pendingDanmuTitle != null) {
                            java.util.regex.Matcher epM2 = java.util.regex.Pattern.compile("[Ee](\\d+)").matcher(pendingDanmuTitle);
                            if (epM2.find()) epInfo2 = " - 第" + epM2.group(1) + "集";
                        }
                        String name2 = data.getItemTV() != null ? data.getItemTV() : data.getItemTitle();
                        titleView2.setText("选择剧集 - " + (name2 != null ? name2 : "") + epInfo2);
                    }

                    // 隐藏搜索栏
                    ((View) dialog.findViewById(R.id.dm_search_input).getParent()).setVisibility(View.GONE);

                    // 限制弹窗高度，避免底部按钮被裁切
                    dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                            (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.9f));

                    // 填充剧集列表
                    LinearLayout results2 = dialog.findViewById(R.id.dm_search_results);
                    results2.removeAllViews();

                    for (int ei = 0; ei < epCount; ei++) {
                        int epIndex = ei;
                        Button b = new Button(activity);
                        b.setBackgroundResource(R.drawable.bg_search_item);
                        b.setText(epLabels[ei]);
                        b.setTextColor(0xFFEEEEEE);
                        b.setPadding(16, 14, 16, 14);
                        b.setAllCaps(false);
                        b.setTextSize(14);
                        b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                        b.setSingleLine(true);
                        b.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
                        b.setMarqueeRepeatLimit(-1);
                        b.setFocusable(true);
                        b.setSelected(true);
                        b.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(0, 0, 0, 6);
                        b.setOnClickListener(v -> {
                            if (epIndex < epCount && epIds[epIndex] > 0) {
                                if (animeId > 0 && animeName != null && data.getItemTV() != null && !data.getItemTV().isEmpty()) {
                                    try {
                                        String cacheJson = prefs.getString("danmu_match_cache", "{}");
                                        JSONObject cache = new JSONObject(cacheJson);
                                        cache.put(data.getItemTV(), animeId + "|" + animeName);
                                        prefs.edit().putString("danmu_match_cache", cache.toString()).apply();
                                    } catch (Exception ignored) {}
                                }
                                dialog.dismiss();
                                loadDanmuByEp(epIds[epIndex], animeName != null ? animeName + " " + epLabels[epIndex] : null);
                            } else {
                                showDanmuStatus("弹幕: 无效剧集ID");
                            }
                        });
                        results2.addView(b);
                    }

                    // 取消 → 返回搜索界面
                    Button cancelBtn2 = dialog.findViewById(R.id.dm_search_cancel);
                    cancelBtn2.setText("返回搜索");
                    cancelBtn2.setOnClickListener(v -> {
                        dialog.dismiss();
                        showDanmuSearch();
                    });

                    dialog.show();
                });
            } catch (Exception e) {
                showDanmuStatus("弹幕失败: " + e.getMessage());
            }
        }).start();
    }

    private void loadDanmuByEp(int epId) {
        loadDanmuByEp(epId, null);
    }

    private void loadDanmuByEp(int epId, String epName) {
        updateDanmuMatchDisplay(epName);
        showDanmuStatus(epName != null ? "弹幕: " + epName + " 获取数据..." : "弹幕: 获取数据...");
        new Thread(() -> {
            try {
                URL u = new URL(danmuUrl + "/api/v2/comment/" + epId + "?format=json");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.connect();
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
                r.close();
                String raw = sb.toString();
                JSONArray arr;
                if (raw.trim().startsWith("{")) {
                    JSONObject jo = new JSONObject(raw);
                    if (jo.has("comments")) arr = jo.getJSONArray("comments");
                    else if (jo.has("data")) arr = jo.getJSONArray("data");
                    else arr = new JSONArray();
                } else {
                    arr = new JSONArray(raw);
                }
                int maxCom = prefs.getInt("danmu_maxcomments", 50000);
                int total = arr.length();
                float keepRate = total > maxCom ? (float) maxCom / total : 1f;
                final List<DanmuView.DanmuComment> list = new java.util.ArrayList<>(Math.min(total, maxCom));
                for (int i = 0; i < total; i++) {
                    // 超过上限时按比例稀疏，保留均匀分布
                    if (keepRate < 1f && Math.random() >= keepRate) continue;

                    JSONObject o = arr.getJSONObject(i);
                    DanmuView.DanmuComment dc = new DanmuView.DanmuComment();
                    dc.text = o.optString("m", "");

                    String pVal = o.optString("p", "0");
                    if (pVal.contains(",")) {
                        String[] parts = pVal.split(",");
                        try {
                            dc.time = Float.parseFloat(parts[0].trim());
                        } catch (Exception e2) {
                            dc.time = 0;
                        }
                        // 解析弹幕模式：1=滚动 4=底部 5=顶部
                        if (parts.length >= 2) {
                            try {
                                dc.type = Integer.parseInt(parts[1].trim());
                            } catch (Exception e2) {
                                dc.type = 1;
                            }
                        }
                        if (parts.length >= 4) {
                            try {
                                dc.color = 0xFF000000 | (int) Long.parseLong(parts[2].trim());
                            } catch (Exception e2) {
                                dc.color = 0xFFFFFFFF;
                            }
                        } else {
                            dc.color = 0xFFFFFFFF;
                        }
                    } else {
                        try {
                            dc.time = Float.parseFloat(pVal);
                        } catch (Exception e2) {
                            dc.time = 0;
                        }
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
                activity.runOnUiThread(() -> {
                    danmuItems = list;
                    // 弹幕时间戳压缩匹配视频时长
                    long videoDur = 0;
                    Player p = data.getPlayer();
                    if (p != null && p.getDuration() > 0)
                        videoDur = p.getDuration() / 1000;
                    else if (data.getItemDuration() > 0)
                        videoDur = data.getItemDuration();
                    if (videoDur > 0 && !list.isEmpty()) {
                        float maxDanmuTime = list.get(list.size() - 1).time;
                        if (maxDanmuTime > 0) {
                            float ratio = (float) videoDur / maxDanmuTime;
                            for (DanmuView.DanmuComment dc2 : list) {
                                dc2.time *= ratio;
                            }
                            Log.d(TAG, "[弹幕] 时间匹配 " + maxDanmuTime + "s → " + videoDur + "s ratio=" + String.format("%.3f", ratio));

                            // 差距超过 ±5% 时警告
                            if (ratio < 0.95f || ratio > 1.05f) {
                                float diffPct = Math.abs((1f - ratio) * 100f);
                                String direction = ratio < 1f ? "弹幕偏长" : "弹幕偏短";
                                showDanmuStatus(direction
                                        + " 弹幕=" + (int) maxDanmuTime + "s"
                                        + " 视频=" + (int) videoDur + "s"
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
                    if (loaded < total) msg += "（显示前" + loaded + "条）";
                    showDanmuStatus(msg);
                });
            } catch (Exception e) {
                showDanmuStatus("弹幕失败: " + e.getMessage());
            }
        }).start();
    }

    // ========== 自动匹配 ==========

    private void startAutoMatch(String title, int targetEp) {
        showDanmuStatus("弹幕: 正在匹配 \"" + title + "\"...");
        new Thread(() -> {
            try {
                int episodeId = 0;
                String matchedName = title;
                Log.d(TAG, "目标集数: " + targetEp + " 来自: " + title);

                // match 返回的番剧信息
                int matchAnimeId = 0;
                String matchAnimeTitle = "";
                try {
                    URL url = new URL(danmuUrl + "/api/v2/match");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    String body = "{\"fileName\":\"" + title + "\"}";
                    conn.getOutputStream().write(body.getBytes("UTF-8"));
                    conn.connect();
                    if (conn.getResponseCode() == 200) {
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder resp = new StringBuilder();
                        String l;
                        while ((l = br.readLine()) != null) resp.append(l);
                        br.close();
                        JSONObject j = new JSONObject(resp.toString());
                        Log.d(TAG, "match resp: " + resp.toString().substring(0, Math.min(200, resp.length())));
                        JSONArray matches = j.optJSONArray("matches");
                        if (matches != null && matches.length() > 0) {
                            JSONObject firstMatch = matches.getJSONObject(0);
                            episodeId = firstMatch.optInt("episodeId", 0);
                            matchAnimeId = firstMatch.optInt("animeId", 0);
                            matchAnimeTitle = firstMatch.optString("animeTitle", "");
                            String matchEp = firstMatch.optString("episodeTitle", "");
                            if (!matchAnimeTitle.isEmpty())
                                matchedName = matchAnimeTitle + (matchEp.isEmpty() ? "" : " " + matchEp);
                            updateDanmuMatchDisplay(matchedName);
                            // 验证集数是否匹配
                            int matchedEpNum = 0;
                            java.util.regex.Matcher mEp = Pattern.compile("[第](\\d+)[集]").matcher(matchEp);
                            if (mEp.find()) matchedEpNum = Integer.parseInt(mEp.group(1));
                            Log.d(TAG, "match ok: epId=" + episodeId + " matchedEp=" + matchedEpNum
                                    + " targetEp=" + targetEp + " name=" + matchedName);
                            if (targetEp > 0 && matchedEpNum > 0 && matchedEpNum != targetEp) {
                                showDanmuStatus("弹幕: match 匹配到第" + matchedEpNum + "集，需要第" + targetEp + "集，丢弃");
                                episodeId = -1;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                // match 失败时重试一次
                if (episodeId <= 0 && matchAnimeId == 0) {
                    Log.d(TAG, "match retry...");
                    try {
                        URL url = new URL(danmuUrl + "/api/v2/match");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setDoOutput(true);
                        String body = "{\"fileName\":\"" + title + "\"}";
                        conn.getOutputStream().write(body.getBytes("UTF-8"));
                        conn.connect();
                        if (conn.getResponseCode() == 200) {
                            java.io.BufferedReader br = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                            StringBuilder resp = new StringBuilder();
                            String l;
                            while ((l = br.readLine()) != null) resp.append(l);
                            br.close();
                            JSONObject j = new JSONObject(resp.toString());
                            JSONArray matches = j.optJSONArray("matches");
                            if (matches != null && matches.length() > 0) {
                                JSONObject firstMatch = matches.getJSONObject(0);
                                episodeId = firstMatch.optInt("episodeId", 0);
                                matchAnimeId = firstMatch.optInt("animeId", 0);
                                matchAnimeTitle = firstMatch.optString("animeTitle", "");
                                if (episodeId > 0) {
                                    String matchEp = firstMatch.optString("episodeTitle", "");
                                    int matchedEpNum = 0;
                                    java.util.regex.Matcher mEp = Pattern.compile("[第](\\d+)[集]").matcher(matchEp);
                                    if (mEp.find()) matchedEpNum = Integer.parseInt(mEp.group(1));
                                    if (targetEp > 0 && matchedEpNum > 0 && matchedEpNum != targetEp) {
                                        showDanmuStatus("弹幕: 重试 match 匹配到第" + matchedEpNum + "集，需要第" + targetEp + "集，丢弃");
                                        episodeId = -1;
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (episodeId <= 0) {
                    // 优先用 match 返回的番剧名/ID 搜索
                    String searchKw = matchAnimeTitle.isEmpty() ? title : matchAnimeTitle;
                    Log.d(TAG, "match failed, searching: " + searchKw + " (animeId=" + matchAnimeId + ")");
                    showDanmuStatus("弹幕: 搜索 \"" + searchKw + "\"...");
                    try {
                        // 如果有 animeId 直接取剧集列表
                        if (matchAnimeId > 0) {
                            URL bu = new URL(danmuUrl + "/api/v2/bangumi/" + matchAnimeId);
                            HttpURLConnection bc = (HttpURLConnection) bu.openConnection();
                            bc.connect();
                            java.io.BufferedReader br2 = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(bc.getInputStream(), "UTF-8"));
                            StringBuilder bp = new StringBuilder();
                            String l3;
                            while ((l3 = br2.readLine()) != null) bp.append(l3);
                            br2.close();
                            JSONObject bj = new JSONObject(bp.toString());
                            JSONArray eps = null;
                            if (bj.has("bangumi") && bj.getJSONObject("bangumi").has("episodes"))
                                eps = bj.getJSONObject("bangumi").getJSONArray("episodes");
                            else if (bj.has("episodes")) eps = bj.getJSONArray("episodes");
                            else if (bj.has("data") && bj.getJSONObject("data").has("episodes"))
                                eps = bj.getJSONObject("data").getJSONArray("episodes");
                            if (eps != null && eps.length() > 0) {
                                if (targetEp > 0) {
                                    showDanmuStatus("弹幕: 从剧集列表中找第" + targetEp + "集...");
                                    for (int ei = 0; ei < eps.length(); ei++) {
                                        JSONObject epo = eps.getJSONObject(ei);
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
                            String enc = URLEncoder.encode(searchKw, "UTF-8");
                            URL su = new URL(danmuUrl + "/api/v2/search/anime?keyword=" + enc);
                            HttpURLConnection sc = (HttpURLConnection) su.openConnection();
                            sc.connect();
                            java.io.BufferedReader sr = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(sc.getInputStream(), "UTF-8"));
                            StringBuilder srp = new StringBuilder();
                            String l2;
                            while ((l2 = sr.readLine()) != null) srp.append(l2);
                            sr.close();
                            String raw = srp.toString().trim();
                            Log.d(TAG, "search resp: " + raw.substring(0, Math.min(200, raw.length())));
                            JSONArray animes;
                            if (raw.startsWith("[")) animes = new JSONArray(raw);
                            else {
                                JSONObject jo = new JSONObject(raw);
                                animes = jo.has("animes") ? jo.getJSONArray("animes")
                                        : jo.has("data") ? jo.getJSONArray("data")
                                        : new JSONArray();
                            }
                            if (animes.length() > 0) {
                                // 找 episodeCount >= targetEp 的番剧
                                int aid = 0;
                                for (int ai = 0; ai < animes.length(); ai++) {
                                    JSONObject aobj = animes.getJSONObject(ai);
                                    int ac = aobj.optInt("episodeCount", 0);
                                    if (targetEp <= 0 || ac >= targetEp) {
                                        aid = aobj.optInt("animeId", aobj.optInt("id", 0));
                                        Log.d(TAG, "found anime: " + aobj.optString("animeTitle", "") + " epCount=" + ac);
                                        break;
                                    }
                                }
                                Log.d(TAG, "search result: selected animeId=" + aid + " (targetEp=" + targetEp + ")");
                                if (aid > 0) {
                                    URL bu = new URL(danmuUrl + "/api/v2/bangumi/" + aid);
                                    HttpURLConnection bc = (HttpURLConnection) bu.openConnection();
                                    bc.connect();
                                    java.io.BufferedReader br2 = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(bc.getInputStream(), "UTF-8"));
                                    StringBuilder bp = new StringBuilder();
                                    String l3;
                                    while ((l3 = br2.readLine()) != null) bp.append(l3);
                                    br2.close();
                                    JSONObject bj = new JSONObject(bp.toString());
                                    JSONArray eps = null;
                                    if (bj.has("bangumi") && bj.getJSONObject("bangumi").has("episodes"))
                                        eps = bj.getJSONObject("bangumi").getJSONArray("episodes");
                                    else if (bj.has("episodes")) eps = bj.getJSONArray("episodes");
                                    else if (bj.has("data") && bj.getJSONObject("data").has("episodes"))
                                        eps = bj.getJSONObject("data").getJSONArray("episodes");
                                    if (eps != null && eps.length() > 0) {
                                        if (targetEp > 0) {
                                            showDanmuStatus("弹幕: 从剧集列表中找第" + targetEp + "集...");
                                            for (int ei = 0; ei < eps.length(); ei++) {
                                                JSONObject epo = eps.getJSONObject(ei);
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
                if (episodeId <= 0 && data.getItemTV() != null && !data.getItemTV().isEmpty()) {
                    try {
                        String cacheJson = prefs.getString("danmu_match_cache", "{}");
                        JSONObject cache = new JSONObject(cacheJson);
                        if (cache.has(data.getItemTV())) {
                            String val = cache.getString(data.getItemTV());
                            String[] parts = val.split("\\|", 2);
                            if (parts.length == 2) {
                                final int cachedAid = Integer.parseInt(parts[0]);
                                final String cachedName = parts[1];
                                showDanmuStatus("弹幕: 使用缓存 \"" + cachedName + "\" 匹配第" + targetEp + "集...");
                                Log.d(TAG, "缓存命中: " + data.getItemTV() + " -> " + cachedName + " (aid=" + cachedAid + ")");
                                URL bu = new URL(danmuUrl + "/api/v2/bangumi/" + cachedAid);
                                HttpURLConnection bc = (HttpURLConnection) bu.openConnection();
                                bc.connect();
                                java.io.BufferedReader br2 = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(bc.getInputStream(), "UTF-8"));
                                StringBuilder bp2 = new StringBuilder();
                                String l3;
                                while ((l3 = br2.readLine()) != null) bp2.append(l3);
                                br2.close();
                                JSONObject bj2 = new JSONObject(bp2.toString());
                                JSONArray eps2 = null;
                                if (bj2.has("bangumi") && bj2.getJSONObject("bangumi").has("episodes"))
                                    eps2 = bj2.getJSONObject("bangumi").getJSONArray("episodes");
                                else if (bj2.has("episodes")) eps2 = bj2.getJSONArray("episodes");
                                else if (bj2.has("data") && bj2.getJSONObject("data").has("episodes"))
                                    eps2 = bj2.getJSONObject("data").getJSONArray("episodes");
                                if (eps2 != null) {
                                    for (int ei = 0; ei < eps2.length(); ei++) {
                                        JSONObject epo = eps2.getJSONObject(ei);
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

}
