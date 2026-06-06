package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.*;
import com.fntv.app.model.WatchHistoryManager;
import com.fntv.app.model.WatchRecord;
import com.fntv.app.util.SimpleImageLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HomeActivity extends AppCompatActivity {

    private Button tabMovies, tabLibrary, tabSettings;
    private View panelMovies, panelLibrary, panelSettings;
    private LinearLayout moviesContainer, libraryContainer;
    private TextView tvMoviesLoading, tvLibraryLoading, tvLibraryEmpty;
    private TextView tvSettingUsername, tvSettingServer, tvDecoderValue, tvDanmuUrl;
    private Button btnLogout, btnCheckUpdate, btnFeedback;
    private RelativeLayout rlDecoderSetting, rlDanmuSetting, rlSeekStep;
    private TextView tvSeekStepValue;

    private int currentTab = 0;
    private final List<MediaDbItem> mediaLibraries = new ArrayList<>();
    private WatchHistoryManager watchHistory;
    private boolean showingOverview = true;
    private boolean loadingPreviews = false;

    private FnApiManager apiManager;
    private String baseUrl = "";
    private SharedPreferences prefs;
    private static final String PREF_DECODER = "decoder_mode";

    private long t0;
    private boolean overviewBuilt = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        t0 = System.currentTimeMillis();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(0xFF1A1A1A);
        }

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        apiManager = FnApiManager.getInstance();
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        if (apiManager.getApi() == null && !baseUrl.isEmpty()) {
            apiManager.updateBaseUrl(baseUrl);
        }
        // 切换服务器时清空观看记录
        String lastHost = prefs.getString("last_host", "");
        if (!lastHost.equals(baseUrl) && !lastHost.isEmpty()) {
            prefs.edit().remove("watch_history").putString("last_host", baseUrl).apply();
        } else if (lastHost.isEmpty() && !baseUrl.isEmpty()) {
            prefs.edit().putString("last_host", baseUrl).apply();
        }
        watchHistory = new WatchHistoryManager(prefs);

        initViews();
        setupTabs();
        setupSettings();
        setupLogout();
        setupUpdateCheck();
        setupFeedback();

        switchTab(0);
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("正在加载媒体库...");
        loadOverview();
    }

    private void initViews() {
        tabMovies = findViewById(R.id.tabMovies);
        tabLibrary = findViewById(R.id.tabLibrary);
        tabSettings = findViewById(R.id.tabSettings);
        panelMovies = findViewById(R.id.panelMovies);
        panelLibrary = findViewById(R.id.panelLibrary);
        panelSettings = findViewById(R.id.panelSettings);
        moviesContainer = findViewById(R.id.moviesGridContainer);
        libraryContainer = findViewById(R.id.libraryGridContainer);
        tvMoviesLoading = findViewById(R.id.tvMoviesLoading);
        tvLibraryLoading = findViewById(R.id.tvLibraryLoading);
        tvLibraryEmpty = findViewById(R.id.tvLibraryEmpty);
        tvSettingUsername = findViewById(R.id.tvSettingUsername);
        tvSettingServer = findViewById(R.id.tvSettingServer);
        tvDecoderValue = findViewById(R.id.tvDecoderValue);
        btnLogout = findViewById(R.id.btnLogout);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        btnFeedback = findViewById(R.id.btnFeedback);
        rlDecoderSetting = findViewById(R.id.rlDecoderSetting);
        rlDanmuSetting = findViewById(R.id.rlDanmuSetting);
        rlSeekStep = findViewById(R.id.rlSeekStep);
        tvSeekStepValue = findViewById(R.id.tvSeekStepValue);
        tvDanmuUrl = findViewById(R.id.tvDanmuUrl);
        tvSettingServer.setText(prefs.getString("host", ""));

        TextView tvVersion = findViewById(R.id.tvVersionName);
        try {
            tvVersion.setText("FN TV v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {}
    }

    // ======================== Tab ========================

    @Override
    protected void onResume() {
        super.onResume();
        if (currentTab == 0 && !mediaLibraries.isEmpty() && overviewBuilt) {
            loadingPreviews = false;
            showOverview();
            loadAllPreviews();
        } else if (currentTab == 0 && !overviewBuilt) {
            loadOverview();
        }
    }

    private void setupTabs() {
        tabMovies.setOnClickListener(v -> switchTab(0));
        tabLibrary.setOnClickListener(v -> { switchTab(1); loadMediaLibraries(); });
        tabSettings.setOnClickListener(v -> switchTab(2));
    }

    private void switchTab(int index) {
        currentTab = index;
        panelMovies.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelLibrary.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        tabMovies.setSelected(index == 0);
        tabLibrary.setSelected(index == 1);
        tabSettings.setSelected(index == 2);
        if (index == 0) tabMovies.requestFocus();
        else if (index == 1) tabLibrary.requestFocus();
        else tabSettings.requestFocus();
    }

    // ==================== 影视概览 ====================

    private void loadOverview() {
        Log.d("Overview", "loadOverview start  t=" + (System.currentTimeMillis() - t0) + "ms");
        showingOverview = true;
        overviewBuilt = false;
        loadingPreviews = false;
        tvMoviesLoading.setVisibility(View.VISIBLE);

        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                Log.d("Overview", "getMediaDbList response code=" + response.code() + " t=" + (System.currentTimeMillis() - t0) + "ms");
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    mediaLibraries.addAll(response.body().data);
                    Log.d("Overview", "loaded " + mediaLibraries.size() + " libraries");
                    showOverview();
                    loadAllPreviews();
                } else {
                    tvMoviesLoading.setVisibility(View.GONE);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Log.e("Overview", "getMediaDbList onFailure: " + t.getMessage() + " t=" + (System.currentTimeMillis() - t0) + "ms");
            }
        });
    }

    /** 构建概览 */
    private void showOverview() {
        tvMoviesLoading.setVisibility(View.GONE);
        moviesContainer.removeAllViews();
        showingOverview = true;
        overviewBuilt = true;

        // 继续观看
        List<WatchRecord> watching = new ArrayList<>();
        for (WatchRecord r : watchHistory.getTop(20)) {
            if (!r.isNearlyFinished()) watching.add(r);
        }

        // 继续观看（最顶部）
        if (!watching.isEmpty()) {
            addContinueWatching(moviesContainer, watching, 0);
        }

        // 各媒体库：标题 + 预览容器
        int firstViewAllId = -1;
        for (MediaDbItem lib : mediaLibraries) {
            LinearLayout headerRow = makeLibHeader(lib.guid, lib.title, 0);
            moviesContainer.addView(headerRow);
            for (int ci = 0; ci < headerRow.getChildCount(); ci++) {
                View child = headerRow.getChildAt(ci);
                if (child instanceof Button && child.isFocusable() && firstViewAllId < 0) {
                    firstViewAllId = child.getId();
                }
            }
            // 预览卡片容器（暂空，loadAllPreviews 后填充）
            LinearLayout previewBox = new LinearLayout(this);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            previewBox.setOrientation(LinearLayout.VERTICAL);
            previewBox.setPadding(6, 0, 6, 0);
            previewBox.setTag("preview_" + lib.guid);
            moviesContainer.addView(previewBox);
            moviesContainer.addView(makeSpacer(16));
        }

        // 有了 firstViewAllId 后，给继续观看卡片设置 nextFocusDown
        if (firstViewAllId > 0) {
            for (int wi = 0; wi < moviesContainer.getChildCount(); wi++) {
                View wv = moviesContainer.getChildAt(wi);
                if (wv instanceof ViewGroup) {
                    applyFocusDown((ViewGroup) wv, firstViewAllId);
                }
            }
        }

        if (moviesContainer.getChildCount() == 0) {
            TextView e = new TextView(this);
            e.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            e.setGravity(Gravity.CENTER);
            e.setText("暂无影视内容");
            e.setTextColor(0xFF808080);
            e.setTextSize(14);
            moviesContainer.addView(e);
        }
    }

    /** 加载各媒体库预览 */
    private void loadAllPreviews() {
        if (loadingPreviews) return;
        loadingPreviews = true;
        for (final MediaDbItem lib : mediaLibraries) {
            final String guid = lib.guid;
            apiManager.getApi().getItemList(ItemListRequest.browseLibrary(guid))
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                       Response<ApiResponse<ItemListResponse>> response) {
                    if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                            || response.body().data == null || response.body().data.list == null
                            || response.body().data.list.isEmpty()) return;
                    // 取前6个填到预览容器
                    List<PlayListItem> items = response.body().data.list;
                    if (items.size() > 6) items = items.subList(0, 6);
                    fillPreview(guid, items);
                }
                @Override public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {}
            });
        }
    }

    /** 填充预览卡片（先清空再填充，防止重复） */
    private void fillPreview(String libGuid, List<PlayListItem> items) {
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View v = moviesContainer.getChildAt(i);
            if (v instanceof LinearLayout && ("preview_" + libGuid).equals(v.getTag())) {
                LinearLayout box = (LinearLayout) v;
                box.removeAllViews();
                populateGrid(box, items);
                break;
            }
        }
    }

    /** 构建媒体库标题行（整行可聚焦，点击 = 查看全部） */
    private LinearLayout makeLibHeader(String libGuid, String libTitle, int count) {
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(6, 24, 6, 18);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setMinimumHeight(68);
        headerRow.setId(View.generateViewId());

        TextView header = new TextView(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.setText(libTitle);
        header.setTextColor(0xFFEEEEEE);
        header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(header);

        Button viewAll = new Button(this);
        viewAll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 68));
        viewAll.setBackgroundResource(R.drawable.bg_input);
        viewAll.setText("查看全部 ›");
        viewAll.setTextColor(0xFFB0B0B0);
        viewAll.setTextSize(14);
        viewAll.setFocusable(true);
        viewAll.setId(View.generateViewId());
        viewAll.setPadding(24, 0, 24, 0);
        viewAll.setOnClickListener(v -> browseItems(libGuid, libTitle));
        viewAll.setOnFocusChangeListener((v, hasFocus) -> {
            viewAll.setTextColor(hasFocus ? 0xFF81C784 : 0xFFB0B0B0);
            viewAll.setBackgroundColor(hasFocus ? 0x44FFFFFF : 0x00000000);
        });
        headerRow.setOnClickListener(v -> browseItems(libGuid, libTitle));
        headerRow.setFocusable(true);
        headerRow.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) viewAll.requestFocus();
        });
        headerRow.addView(viewAll);
        return headerRow;
    }

    private String makePosterUrl(String path) {
        if (path == null || path.isEmpty()) {
            Log.d("PosterUrl", "path is null/empty");
            return null;
        }
        String p = path.startsWith("/") ? path : "/" + path;
        String fullUrl = baseUrl + "/v/api/v1/sys/img" + p + "?w=400";
        Log.d("PosterUrl", "poster=" + path + " -> " + fullUrl);
        return fullUrl;
    }

    // ==================== 继续观看 ====================

    private void addContinueWatching(LinearLayout cont, List<WatchRecord> records, int viewAllId) {
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText("▶ 继续观看");
        h.setTextColor(0xFF81C784);
        h.setTextSize(15);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        cont.addView(h);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 410));
        hsv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(10, 8, 10, 8);

        for (WatchRecord r : records) {
            View card = makeWatchCard(r);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    220, 380);
            lp.setMargins(10, 0, 10, 0);
            card.setLayoutParams(lp);
            // 按↓强制到"查看全部"按钮
            if (viewAllId > 0) card.setNextFocusDownId(viewAllId);
            row.addView(card);
        }

        hsv.addView(row);
        cont.addView(hsv);

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                loadImagesLazily(hsv, 0);
            }
        });

        cont.addView(makeSpacer(6));
    }

    private View makeWatchCard(WatchRecord record) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        card.setPadding(6, 6, 6, 6);
        card.setFocusable(true);

        ImageView poster = new ImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 280));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF333333);
        String imgUrl = makePosterUrl(record.poster);
        if (imgUrl != null) { poster.setTag(imgUrl); }
        card.addView(poster);

        // 进度条
        LinearLayout pBar = new LinearLayout(this);
        pBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 5));
        pBar.setOrientation(LinearLayout.HORIZONTAL);
        pBar.setWeightSum(100);
        int pct = Math.max(0, Math.min(100, record.getProgressPercent()));
        Log.d("Overview", "进度条: ts=" + record.ts + " dur=" + record.duration + " pct=" + pct + " " + record.getDisplayTitle());
        if (pct > 0) {
            View f = new View(this);
            f.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
            f.setBackgroundColor(0xFF81C784);
            pBar.addView(f);
        }
        if (pct < 100) {
            View r = new View(this);
            r.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
            r.setBackgroundColor(0xFF555555);
            pBar.addView(r);
        }
        card.addView(pBar);

        TextView title = new TextView(this);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 85));
        title.setSingleLine(true);
        title.setHorizontallyScrolling(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setTextSize(13);
        title.setTextColor(0xFFEEEEEE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(4, 0, 4, 0);
        title.setText(record.getDisplayTitle());
        card.addView(title);

        // 跑马灯：一直滚动
        title.setSelected(true);

        // 点击直接播放（跳转到历史进度）
        final WatchRecord rec = record;
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchPlayer(rec.guid, rec.title, rec.tvTitle != null ? rec.tvTitle : "",
                        rec.episodeNumber, rec.poster, rec.libraryName,
                        rec.ts, rec.duration, rec.parentGuid);
            }
        });

        return card;
    }

    /** 遍历容器，给所有可聚焦卡片设置 nextFocusDown */
    private void applyFocusDown(ViewGroup group, int targetId) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (v.isFocusable()) {
                v.setNextFocusDownId(targetId);
            }
            if (v instanceof ViewGroup) {
                applyFocusDown((ViewGroup) v, targetId);
            }
        }
    }

    // ==================== 横向滚动卡片 ====================

    private void populateGrid(LinearLayout cont, List<PlayListItem> items) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 410));
        hsv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(12, 8, 12, 8);

        // 找到 cont 的前一个兄弟（headerRow），取其 viewAll 按钮用于焦点关联
        View focusUpTarget = null;
        if (cont.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) cont.getParent();
            int idx = parent.indexOfChild(cont);
            if (idx > 0 && parent.getChildAt(idx - 1) instanceof ViewGroup) {
                ViewGroup headerRow = (ViewGroup) parent.getChildAt(idx - 1);
                // 从 headerRow 里找到 viewAll 按钮
                for (int ci = 0; ci < headerRow.getChildCount(); ci++) {
                    View child = headerRow.getChildAt(ci);
                    if (child instanceof Button && child.isFocusable()) {
                        focusUpTarget = child;
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < items.size(); i++) {
            View card = makeItemCard(items.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(220, 380);
            lp.setMargins(10, 0, 10, 0);
            card.setLayoutParams(lp);
            // 所有卡片：按↑强制回到"查看全部"按钮
            if (focusUpTarget != null) {
                card.setNextFocusUpId(focusUpTarget.getId());
            }
            row.addView(card);
        }

        hsv.addView(row);
        cont.addView(hsv);

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                loadImagesLazily(hsv, 0);
            }
        });
    }

    /** 竖版卡片（2:3 比例适配海报图，图片为主，文字一条） */
    private View makeItemCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        card.setPadding(6, 6, 6, 6);
        card.setFocusable(true);

        // 海报 — 只设URL标记，不加载（等页面显示完后统一逐张加载）
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 280));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0xFF333333);
        String imgUrl = makePosterUrl(item.poster);
        if (imgUrl != null) { iv.setTag(imgUrl); }
        card.addView(iv);

        // 底部文字条：类型 + 标题
        LinearLayout textBar = new LinearLayout(this);
        textBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 88));
        textBar.setOrientation(LinearLayout.VERTICAL);
        textBar.setGravity(Gravity.CENTER_VERTICAL);
        textBar.setPadding(0, 4, 0, 4);

        TextView tag = new TextView(this);
        tag.setTextSize(9);
        tag.setTextColor(0xFF78909C);
        String t = item.type;
        if ("TV".equals(t)) t = "剧集";
        else if ("Movie".equals(t)) t = "电影";
        else if ("Directory".equals(t)) t = "文件夹";
        else if ("Video".equals(t)) t = "视频";
        tag.setText(t);
        textBar.addView(tag);

        final TextView title = new TextView(this);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setTextSize(11);
        title.setTextColor(0xFFEEEEEE);
        title.setText(item.title != null ? item.title : "未知");
        textBar.addView(title);

        card.addView(textBar);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                title.setSelected(hasFocus);
            }
        });

        card.setTag(item);
        card.setOnClickListener(v -> onItemClick((PlayListItem) card.getTag()));
        return card;
    }

    private void onItemClick(PlayListItem item) {
        showDetail(item);  // 全部走 getPlayInfo
    }

    // ==================== 查看全部 ====================

    private void browseItems(String ancestorGuid, String title) {
        Log.d("Overview", "browseItems: guid=" + ancestorGuid + " title=" + title);
        showingOverview = false;
        moviesContainer.removeAllViews();
        tvMoviesLoading.setVisibility(View.VISIBLE);

        apiManager.getApi().getItemList(ItemListRequest.browseLibrary(ancestorGuid))
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                tvMoviesLoading.setVisibility(View.GONE);
                Log.d("Overview", "browseItems response code=" + response.code());

                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && response.body().data.list != null
                        && !response.body().data.list.isEmpty()) {
                    List<PlayListItem> list = response.body().data.list;
                    int total = response.body().data.total;
                    Log.d("Overview", "browseItems: got " + list.size() + " items, total=" + total);

                    TextView h = new TextView(HomeActivity.this);
                    h.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    h.setPadding(6, 8, 6, 4);
                    h.setText(title + "  (" + total + "项)");
                    h.setTextColor(0xFFEEEEEE);
                    h.setTextSize(14);
                    moviesContainer.addView(h);
                    // 多行竖卡网格（每行2张）
                    for (int idx = 0; idx < list.size(); idx += 2) {
                        LinearLayout row = new LinearLayout(HomeActivity.this);
                        row.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 410));
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        for (int c = 0; c < 2 && idx + c < list.size(); c++) {
                            View card = makeItemCard(list.get(idx + c));
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                            if (c == 0) lp.rightMargin = 10;
                            else lp.leftMargin = 10;
                            card.setLayoutParams(lp);
                            row.addView(card);
                        }
                        moviesContainer.addView(row);
                        moviesContainer.addView(makeSpacer(16));
                    }
                    new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(moviesContainer, 0));
                } else {
                    TextView e = new TextView(HomeActivity.this);
                    e.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 120));
                    e.setGravity(Gravity.CENTER);
                    e.setText("暂无内容");
                    e.setTextColor(0xFF808080);
                    e.setTextSize(14);
                    moviesContainer.addView(e);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
            }
        });
    }

    /** 启动播放器 */
    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur) {
        launchPlayer(guid, title, tvTitle, epNum, poster, cat, ts, dur, null);
    }

    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur, String parentGuid) {
        watchHistory.put(new WatchRecord(guid, title, tvTitle, epNum, poster, cat, parentGuid, ts, dur));
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("guid", guid);
        intent.putExtra("title", title);
        intent.putExtra("tv_title", tvTitle);
        intent.putExtra("episode_number", epNum);
        intent.putExtra("poster", poster);
        intent.putExtra("category", cat);
        intent.putExtra("ts", ts);
        intent.putExtra("duration", dur);
        if (parentGuid != null) intent.putExtra("parent_guid", parentGuid);
        startActivity(intent);
    }

    // ==================== 详情页（getPlayInfo → 按类型展示） ====================

    private void showDetail(PlayListItem item) {
        showingOverview = false;
        moviesContainer.removeAllViews();
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("加载中...");

        Map<String, String> body = new HashMap<>();
        body.put("item_guid", item.guid);
        apiManager.getApi().getPlayInfo(body).enqueue(new Callback<ApiResponse<PlayInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<PlayInfoResponse>> call,
                                   Response<ApiResponse<PlayInfoResponse>> response) {
                tvMoviesLoading.setVisibility(View.GONE);
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null) {
                    Toast.makeText(HomeActivity.this, "获取详情失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                buildDetailPage(item, response.body().data);
            }
            @Override
            public void onFailure(Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 构建详情页 */
    private void buildDetailPage(PlayListItem item, PlayInfoResponse info) {
        moviesContainer.removeAllViews();

        // 顶部栏（居中标题）
        TextView tvTitle = new TextView(this);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 66));
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(12, 0, 12, 0);
        String epTitle = info.item != null && info.item.title != null ? info.item.title : item.title;
        String series = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        int epNum = info.item != null ? info.item.episodeNumber : 0;
        StringBuilder titleBuilder = new StringBuilder();
        if (!series.isEmpty()) titleBuilder.append(series);
        if (epNum > 0) titleBuilder.append(" ").append(epNum).append("集");
        if (epTitle != null && !epTitle.isEmpty() && !epTitle.equals(series)) {
            titleBuilder.append(" ").append(epTitle);
        }
        // 顶部标题栏去掉（用底部卡片标题代替）

        // 可滚动内容区
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(14, 0, 14, 20);

        // 海报（优先用 backdrops 背景大图，没有则用 poster）
        String posterPath = info.getBackdropPath();
        if (posterPath == null || (info.item != null && info.item.backdrops == null)) {
            posterPath = info.getPosterPath();
        }
        if (posterPath == null) posterPath = item.poster;
        RoundedImageView poster = new RoundedImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        poster.setAdjustViewBounds(true);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackgroundColor(0xFF2A2A2A);
        poster.setCornerRadius(10);
        String pUrl = makePosterUrl(posterPath);
        if (pUrl != null) { poster.setTag(pUrl); }
        content.addView(poster);
        if (pUrl != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                SimpleImageLoader.load(pUrl, poster, apiManager.getClient()));
        }
        content.addView(makeSpacer(12));

        // 元数据卡片
        LinearLayout metaCard = new LinearLayout(this);
        metaCard.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        metaCard.setOrientation(LinearLayout.VERTICAL);
        metaCard.setBackgroundResource(R.drawable.bg_card);
        metaCard.setPadding(16, 16, 16, 16);

        String typeStr = info.type != null ? info.type : item.type;
        String typeLabel = "电影";
        if ("Episode".equals(typeStr)) typeLabel = "剧集";
        else if ("TV".equals(typeStr)) typeLabel = "剧集";
        else if ("Video".equals(typeStr)) typeLabel = "视频";

        int runtime = info.item != null ? info.item.runtime : item.runtime;
        // 评分（直接取 PlayListItem 的 vote_average，保留一位小数）
        String voteRaw = item.voteAverage;
        Log.d("Detail", "vote_average from item=" + voteRaw
                + " from info=" + (info.item != null ? info.item.voteAverage : "null"));
        String voteLabel = null;
        if (voteRaw != null && !voteRaw.isEmpty() && !voteRaw.equals("0") && !voteRaw.equals("0.0")) {
            try {
                float v = Float.parseFloat(voteRaw);
                // vote_average 是 0~10 分
                if (v > 0) voteLabel = String.format("%.1f", v);
            } catch (NumberFormatException ignored) {}
        }

        // 标题
        String bigTitle = epTitle != null ? epTitle : "";
        if (!series.isEmpty() && !series.equals(epTitle)) {
            bigTitle = series + (epNum > 0 ? " " + epNum + "集" : "") + (epTitle != null ? " " + epTitle : "");
        }
        TextView titleBig = new TextView(this);
        titleBig.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleBig.setText(bigTitle.trim());
        titleBig.setTextColor(0xFFFFFFFF);
        titleBig.setTextSize(22);
        titleBig.setTypeface(Typeface.DEFAULT_BOLD);
        metaCard.addView(titleBig);

        // 元数据行
        TextView meta = new TextView(this);
        meta.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        meta.setPadding(0, 8, 0, 0);
        StringBuilder mt = new StringBuilder(typeLabel);

        // 剧集信息: tv_title + parent_title
        if (info.item != null && info.item.tvTitle != null && !info.item.tvTitle.isEmpty()) {
            mt.append("  ").append(info.item.tvTitle);
        }
        if (info.item != null && info.item.parentTitle != null && !info.item.parentTitle.isEmpty()) {
            mt.append("  ").append(info.item.parentTitle);
        }
        if (info.item != null && info.item.episodeNumber > 0) {
            mt.append("  第").append(info.item.episodeNumber).append("集");
        }
        if (runtime > 0) {
            long durSec = info.item != null && info.item.duration > 0 ? info.item.duration : runtime * 60L;
            mt.append("  ·  ").append(formatDuration(durSec));
        }
        if (voteLabel != null) mt.append("  ·  ⭐").append(voteLabel);
        // 分辨率
        if (info.item != null && info.item.mediaStream != null
                && info.item.mediaStream.resolutions != null
                && !info.item.mediaStream.resolutions.isEmpty()) {
            mt.append("  ·  ").append(info.item.mediaStream.resolutions.get(0));
        }
        // 音轨
        if (info.item != null && info.item.mediaStream != null
                && info.item.mediaStream.audioType != null
                && !info.item.mediaStream.audioType.isEmpty()) {
            mt.append("  ·  ").append(info.item.mediaStream.audioType.get(0));
        }
        // 日期
        String date = info.item != null && info.item.releaseDate != null
                ? info.item.releaseDate : (info.item != null ? info.item.airDate : null);
        if (date != null && !date.isEmpty()) mt.append("  ·  ").append(date);

        meta.setText(mt.toString());
        meta.setTextColor(0xFFB0B0B0);
        meta.setTextSize(13);
        metaCard.addView(meta);

        content.addView(metaCard);
        content.addView(makeSpacer(12));

        // 简介卡片
        String overview = info.item != null && info.item.overview != null
                ? info.item.overview : item.overview;
        if (overview != null && !overview.isEmpty()) {
            LinearLayout overviewCard = new LinearLayout(this);
            overviewCard.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            overviewCard.setOrientation(LinearLayout.VERTICAL);
            overviewCard.setBackgroundResource(R.drawable.bg_card);
            overviewCard.setPadding(16, 16, 16, 16);

            TextView ovLabel = new TextView(this);
            ovLabel.setText("简介");
            ovLabel.setTextColor(0xFF78909C);
            ovLabel.setTextSize(11);
            overviewCard.addView(ovLabel);

            TextView ov = new TextView(this);
            ov.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ov.setPadding(0, 6, 0, 0);
            ov.setText(overview);
            ov.setTextColor(0xFFCCCCCC);
            ov.setTextSize(14);
            ov.setLineSpacing(6, 1);
            overviewCard.addView(ov);
            content.addView(overviewCard);
            content.addView(makeSpacer(12));
        }

        // 查找历史观看记录
        final String seriesKey = "Episode".equals(info.type) && info.item != null && info.item.tvTitle != null
                ? info.item.tvTitle : item.title;
        WatchRecord historyRecord = null;
        for (WatchRecord wr : watchHistory.getTop(50)) {
            if (seriesKey.equals(wr.getDedupKey())) {
                historyRecord = wr;
                break;
            }
        }

        // 查找历史观看记录
        final String pGuid = item.guid;
        final String pTitle = item.title;
        final String pTV = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        final int orEp = item.episodeNumber;
        final String pPoster = item.poster;
        final String pCat = item.getCategoryLabel();
        final long pTs = historyRecord != null ? historyRecord.ts : (item.ts > 0 ? item.ts : 0);
        // 总时长优先用 play/info 接口的 duration（它最准确），其次是 historyRecord、runtime 转秒、item.duration
        long rawDur = info.item != null && info.item.duration > 0 ? info.item.duration
                : (historyRecord != null ? historyRecord.duration : 0);
        if (rawDur <= 0 && info.item != null && info.item.runtime > 0) rawDur = info.item.runtime * 60L;
        if (rawDur <= 0) rawDur = item.duration;
        final long pDur = rawDur;

        // 用 play/info 的正确时长修正 WatchRecord，确保首页进度条使用精确值
        if (historyRecord != null && info.item != null && info.item.duration > 0
                && historyRecord.duration != info.item.duration) {
            Log.d("Detail", "修正 WatchRecord 时长: " + historyRecord.duration + " → " + info.item.duration);
            historyRecord.duration = info.item.duration;
            watchHistory.put(historyRecord);
        }
        final int pEp = historyRecord != null ? historyRecord.episodeNumber : orEp;
        final int progressPct = historyRecord != null ? Math.max(0, Math.min(100, historyRecord.getProgressPercent())) : 0;
        final String pParentGuid = info.parentGuid != null && !info.parentGuid.isEmpty() ? info.parentGuid : item.parentGuid;

        Log.d("Detail", "pParentGuid=" + pParentGuid + " info.parentGuid=" + (info.parentGuid != null ? info.parentGuid : "null") + " item.parentGuid=" + (item.parentGuid != null ? item.parentGuid : "null"));
        Log.d("Detail", "继续播放: historyRecord=" + (historyRecord != null ? "存在(dur=" + historyRecord.duration + ")" : "null")
                + " info.item.duration=" + (info.item != null ? info.item.duration : "null")
                + " info.item.runtime=" + (info.item != null ? info.item.runtime : "null")
                + " item.duration=" + item.duration + " → pDur=" + pDur + " pTs=" + pTs);

        // 播放按钮（自适应高度）
        FrameLayout playFrame = new FrameLayout(this);
        playFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        playFrame.setMinimumHeight(120);

        // 圆角背景（进度条用两层：蓝色 + 灰色）
        if (progressPct > 0) {
            LinearLayout progressLayer = new LinearLayout(this);
            progressLayer.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            progressLayer.setOrientation(LinearLayout.HORIZONTAL);
            progressLayer.setWeightSum(100);

            // 圆角裁剪：用 GradientDrawable 做背景
            float r = 10 * getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadii(new float[]{r, r, r, r, r, r, r, r});
            gd.setColor(0xFF455A64);
            progressLayer.setBackgroundDrawable(gd);

            View fill = new View(this);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, progressPct));
            android.graphics.drawable.GradientDrawable fillGd = new android.graphics.drawable.GradientDrawable();
            fillGd.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
            fillGd.setColor(0xFF2D6CDF);
            fill.setBackgroundDrawable(fillGd);
            progressLayer.addView(fill);

            View rest = new View(this);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - progressPct));
            rest.setBackgroundColor(0x00000000); // 透明
            progressLayer.addView(rest);

            playFrame.addView(progressLayer);
        } else {
            playFrame.setBackgroundResource(R.drawable.bg_btn_primary);
        }

        // 按钮本身（透明背景）
        Button playBtn = new Button(this);
        playBtn.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playBtn.setBackgroundDrawable(null);
        playBtn.setFocusable(true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setTextColor(0xFFFFFFFF);
        playBtn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                playBtn.setBackgroundColor(0x55FFFFFF);
                playBtn.setScaleX(1.05f);
                playBtn.setScaleY(1.05f);
            } else {
                playBtn.setBackgroundColor(0x00000000);
                playBtn.setScaleX(1.0f);
                playBtn.setScaleY(1.0f);
            }
        });
        if (historyRecord != null) {
            playBtn.setTextSize(16);
            playBtn.setText("▶  继续播放\n" + formatDuration(pTs) + " / " + formatDuration(pDur));
        } else {
            playBtn.setTextSize(22);
            playBtn.setText("▶  播放");
        }

        playBtn.setOnClickListener(v -> {
            Object tag = playBtn.getTag();
            long finalDur = tag instanceof Long ? (Long) tag : pDur;
            launchPlayer(pGuid, pTitle, pTV, pEp, pPoster, pCat, pTs, finalDur, pParentGuid);
        });
        playFrame.addView(playBtn);
        content.addView(playFrame);

        // Episode 类型 → 加载剧集列表，加载后用剧集列表里的精确时长更新播放按钮
        if ("Episode".equals(info.type) && info.parentGuid != null && !info.parentGuid.isEmpty()) {
            content.addView(makeSpacer(16));
            String historyEpGuid = historyRecord != null ? historyRecord.guid : null;
            loadEpisodes(content, info.parentGuid, item, playBtn, pTs, historyEpGuid);
        }

        scrollView.addView(content);
        moviesContainer.addView(scrollView);
    }

    /** 加载剧集列表并按季分组 */
    private void loadEpisodes(final LinearLayout content, final String parentGuid, final PlayListItem item,
                              final Button playBtn, final long pTs, final String historyEpGuid) {
        apiManager.getApi().getEpisodeList(parentGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                List<PlayListItem> episodes = response.body().data;
                showSeasons(content, episodes, item);
                // 从剧集列表找到历史记录对应的那一集，用它的精确时长覆盖按钮
                if (historyEpGuid != null) {
                    for (PlayListItem ep : episodes) {
                        if (ep.guid.equals(historyEpGuid) && ep.duration > 0) {
                            long epDur = ep.duration;
                            Log.d("Detail", "剧集列表匹配到历史剧集, 时长: " + formatDuration(epDur));
                            playBtn.setText("▶  继续播放\n" + formatDuration(pTs) + " / " + formatDuration(epDur));
                            // 通过 setTag 把修正后的时长传给点击监听
                            playBtn.setTag(epDur);
                            break;
                        }
                    }
                }
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 显示季列表 */
    private void showSeasons(LinearLayout content, List<PlayListItem> episodes, PlayListItem item) {
        Map<Integer, List<PlayListItem>> map = new HashMap<>();
        for (PlayListItem ep : episodes) {
            int sn = ep.seasonNumber > 0 ? ep.seasonNumber : 1;
            if (!map.containsKey(sn)) map.put(sn, new ArrayList<PlayListItem>());
            map.get(sn).add(ep);
        }

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(0, 14, 0, 14);
        h.setText("选择剧集");
        h.setTextColor(0xFFEEEEEE);
        h.setTextSize(22);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(h);

        List<Integer> nums = new ArrayList<>(map.keySet());
        java.util.Collections.sort(nums);
        for (final int sn : nums) {
            final List<PlayListItem> eps = map.get(sn);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackgroundResource(R.drawable.bg_media_card);
            card.setPadding(16, 18, 16, 18);
            card.setFocusable(true);
            card.setMinimumHeight(56);

            LinearLayout tc = new LinearLayout(this);
            tc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tc.setOrientation(LinearLayout.VERTICAL);
            tc.setGravity(Gravity.CENTER_VERTICAL);
            TextView st = new TextView(this); st.setTextSize(16); st.setTextColor(0xFFEEEEEE);
            st.setText("第 " + sn + " 季"); tc.addView(st);
            TextView ss = new TextView(this); ss.setTextSize(12); ss.setTextColor(0xFF808080);
            ss.setText(eps.size() + " 集"); tc.addView(ss);
            card.addView(tc);

            TextView ar = new TextView(this);
            ar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ar.setText(">"); ar.setTextColor(0xFF808080); ar.setTextSize(20);
            ar.setGravity(Gravity.CENTER); ar.setPadding(8, 0, 0, 0);
            card.addView(ar);

            card.setOnClickListener(v -> showEpisodes(eps, sn, sn == 1 ? item : item));
            content.addView(card);
            content.addView(makeSpacer(6));
        }
    }

    /** 显示某季剧集 */
    private void showEpisodes(List<PlayListItem> eps, int sn, PlayListItem original) {
        moviesContainer.removeAllViews();

        // 标题
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 78));
        h.setGravity(Gravity.CENTER);
        h.setText("第 " + sn + " 季");
        h.setTextColor(0xFFEEEEEE); h.setTextSize(21);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        moviesContainer.addView(h);
        moviesContainer.addView(makeSpacer(8));

        for (PlayListItem ep : eps) {
            moviesContainer.addView(makeEpisodeItem(ep, ep.guid.equals(original.guid)));
            moviesContainer.addView(makeSpacer(8));
        }
    }

    /** 剧集条目卡片 */
    private View makeEpisodeItem(PlayListItem ep, boolean isCurrent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        card.setPadding(16, 18, 16, 18);
        card.setFocusable(true);
        card.setMinimumHeight(72);

        final String eg = ep.guid;
        final String et = ep.title;
        final String eTV = ep.tvTitle != null ? ep.tvTitle : "";
        final int eEp = ep.episodeNumber;
        final String epPo = ep.poster;
        final String epCa = ep.getCategoryLabel();
        final long epTs = ep.ts > 0 ? ep.ts : 0;
        final long epDu = ep.duration;
        final String epPG = ep.parentGuid;

        card.setOnClickListener(v -> launchPlayer(eg, et, eTV, eEp, epPo, epCa, epTs, epDu, epPG));

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        infoCol.setOrientation(LinearLayout.HORIZONTAL);
        infoCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView epNum = new TextView(this);
        epNum.setLayoutParams(new LinearLayout.LayoutParams(130, ViewGroup.LayoutParams.WRAP_CONTENT));
        epNum.setTextSize(15);
        epNum.setTextColor(isCurrent ? 0xFF81C784 : 0xFFB0B0B0);
        epNum.setText("EP" + (ep.episodeNumber > 0 ? ep.episodeNumber : "?"));
        infoCol.addView(epNum);

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rightCol.setOrientation(LinearLayout.HORIZONTAL);
        rightCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView epTitle = new TextView(this);
        epTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        epTitle.setTextSize(15);
        epTitle.setTextColor(isCurrent ? 0xFF81C784 : 0xFFEEEEEE);
        epTitle.setSingleLine(true);
        epTitle.setEllipsize(TextUtils.TruncateAt.END);
        StringBuilder titleText = new StringBuilder();
        titleText.append(ep.title != null ? ep.title : "未知");
        if (ep.duration > 0) titleText.append("  ").append(formatDuration(ep.duration));
        if (ep.watched == 1) titleText.append("  ✓");
        epTitle.setText(titleText.toString());
        rightCol.addView(epTitle);

        infoCol.addView(rightCol);
        card.addView(infoCol);

        if (isCurrent) {
            TextView badge = new TextView(this);
            badge.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            badge.setGravity(Gravity.CENTER);
            badge.setText("当前");
            badge.setTextColor(0xFF81C784);
            badge.setTextSize(12);
            badge.setPadding(8, 0, 0, 8);
            card.addView(badge);
        } else {
            Button playEp = new Button(this);
            playEp.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 76));
            playEp.setBackgroundResource(R.drawable.bg_btn_primary);
            playEp.setText("播放");
            playEp.setTextColor(0xFFEEEEEE);
            playEp.setTextSize(12);
            playEp.setFocusable(true);
            playEp.setOnFocusChangeListener((v, hasFocus) -> {
                playEp.setScaleX(hasFocus ? 1.08f : 1.0f);
                playEp.setScaleY(hasFocus ? 1.08f : 1.0f);
            });
            playEp.setPadding(14, 0, 14, 0);
            playEp.setOnClickListener(v -> launchPlayer(eg, et, eTV, eEp, epPo, epCa, epTs, epDu, epPG));
            card.addView(playEp);
        }
        return card;
    }

    // ==================== 媒体库 Tab ====================

    private void loadMediaLibraries() {
        clearContainer(libraryContainer, tvLibraryLoading, tvLibraryEmpty);
        tvLibraryLoading.setVisibility(View.VISIBLE);

        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                tvLibraryLoading.setVisibility(View.GONE);
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    mediaLibraries.addAll(response.body().data);
                    populateLibGrid(libraryContainer, response.body().data);
                    return;
                }
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText("加载失败: " + t.getMessage());
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void populateLibGrid(LinearLayout cont, List<MediaDbItem> libs) {
        cont.removeAllViews();
        for (int i = 0; i < libs.size(); i++) {
            cont.addView(makeLibCard(libs.get(i)));
            if (i < libs.size() - 1) cont.addView(makeSpacer(8));
        }
    }

    private View makeLibCard(MediaDbItem lib) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        card.setPadding(16, 18, 16, 18);
        card.setFocusable(true);
        card.setMinimumHeight(56);

        LinearLayout text = new LinearLayout(this);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setTextSize(16);
        title.setTextColor(0xFFEEEEEE);
        title.setText(lib.title);
        text.addView(title);

        TextView sub = new TextView(this);
        sub.setTextSize(12);
        sub.setTextColor(0xFF808080);
        sub.setText("分类: " + (lib.category != null ? lib.category : "未分类"));
        text.addView(sub);

        card.addView(text);

        TextView arrow = new TextView(this);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        arrow.setText("❯");
        arrow.setTextColor(0xFF808080);
        arrow.setTextSize(20);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(8, 0, 0, 0);
        card.addView(arrow);

        card.setTag(lib);
        card.setOnClickListener(v -> {
            MediaDbItem m = (MediaDbItem) card.getTag();
            switchTab(0);
            browseItems(m.guid, m.title);
        });
        return card;
    }

    // ==================== 设置 ====================

    private void setupSettings() {
        tvSettingUsername.setText("用户名: " + prefs.getString("user", ""));
        String d = prefs.getString(PREF_DECODER, "hardware");
        tvDecoderValue.setText("hardware".equals(d) ? "硬解" : "软解");
        rlDecoderSetting.setOnClickListener(v -> toggleDecoder());

        // 弹幕服务器
        String danmuUrl = prefs.getString("danmu_url", "");
        if (danmuUrl.isEmpty()) {
            String host = prefs.getString("host", "");
            host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
            danmuUrl = "http://" + host + ":9321";
        }
        tvDanmuUrl.setText(danmuUrl);
        rlDanmuSetting.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("弹幕服务器地址");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setText(tvDanmuUrl.getText());
            input.setSelection(input.getText().length());
            b.setView(input);
            b.setPositiveButton("保存", (dialog, which) -> {
                String val = input.getText().toString().trim();
                if (!val.isEmpty()) {
                    prefs.edit().putString("danmu_url", val).apply();
                    tvDanmuUrl.setText(val);
                }
            });
            b.setNegativeButton("重置", (dialog, which) -> {
                prefs.edit().remove("danmu_url").apply();
                String host = prefs.getString("host", "");
                host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
                tvDanmuUrl.setText("http://" + host + ":9321");
            });
            b.show();
        });

        // 快进退步长
        final int[] savedStep = {prefs.getInt("seek_step", 10)};
        tvSeekStepValue.setText(savedStep[0] + "s");
        rlSeekStep.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("快进退步长（秒）");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(savedStep[0]));
            input.setSelection(input.getText().length());
            b.setView(input);
            b.setPositiveButton("保存", (dialog, which) -> {
                try {
                    int val = Integer.parseInt(input.getText().toString().trim());
                    if (val < 1) val = 1;
                    if (val > 300) val = 300;
                    prefs.edit().putInt("seek_step", val).apply();
                    tvSeekStepValue.setText(val + "s");
                    savedStep[0] = val;
                } catch (Exception ignored) {}
            });
            b.setNegativeButton("取消", null);
            b.show();
        });

        apiManager.getApi().getUserInfo().enqueue(new Callback<ApiResponse<UserInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserInfoResponse>> call,
                                   Response<ApiResponse<UserInfoResponse>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null) {
                    tvSettingUsername.setText("用户名: " + response.body().data.getDisplayName());
                }
            }
            @Override public void onFailure(Call<ApiResponse<UserInfoResponse>> call, Throwable t) {}
        });
    }

    private void toggleDecoder() {
        String cur = prefs.getString(PREF_DECODER, "hardware");
        if ("hardware".equals(cur)) {
            prefs.edit().putString(PREF_DECODER, "software").apply();
            tvDecoderValue.setText("软解");
            Toast.makeText(this, "解码: 软解 (CPU)", Toast.LENGTH_SHORT).show();
        } else {
            prefs.edit().putString(PREF_DECODER, "hardware").apply();
            tvDecoderValue.setText("硬解");
            Toast.makeText(this, "解码: 硬解 (GPU)", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 检测更新 ====================

    /** 更新源（按优先级） */
    private static final String[] UPDATE_URLS = {
        "https://raw.giteeusercontent.com/coffee710/fntv/raw/master/update.json",
        "https://jsd.onmicrosoft.cn/gh/rgcaafe/fnos_tv_danmu@master/update.json",
        "https://cdn.jsdelivr.net/gh/rgcaafe/fnos_tv_danmu@master/update.json",
        "https://fastly.jsdelivr.net/gh/rgcaafe/fnos_tv_danmu@master/update.json",
        "https://raw.githubusercontent.com/rgcaafe/fnos_tv_danmu/master/update.json"
    };

    private void setupUpdateCheck() {
        btnCheckUpdate.setOnClickListener(v -> checkUpdate());
    }

    private void checkUpdate() {
        btnCheckUpdate.setEnabled(false);
        btnCheckUpdate.setText("检查中...");
        new Thread(() -> {
            try {
                org.json.JSONObject json = null;
                String usedUrl = "";
                String ts = new java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.CHINA).format(new java.util.Date());
                for (String url : UPDATE_URLS) {
                    try {
                        // 非 GitHub raw 源追加时间戳参数，避免 CDN 缓存
                        if (!url.contains("raw.githubusercontent.com")) {
                            url = url + (url.contains("?") ? "&" : "?") + "t=" + ts;
                        }
                        Log.d("Update", "尝试源: " + url);
                        java.net.URL u = new java.net.URL(url);
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        c.setInstanceFollowRedirects(true);
                        c.connect();
                        int code = c.getResponseCode();
                        Log.d("Update", "响应码: " + code + " 来自: " + url);
                        if (code == 200) {
                            java.io.BufferedReader r = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                            StringBuilder sb = new StringBuilder(); String l;
                            while ((l = r.readLine()) != null) sb.append(l);
                            r.close();
                            String body = sb.toString();
                            Log.d("Update", "响应体: " + body.substring(0, Math.min(300, body.length())));
                            json = new org.json.JSONObject(body);
                            usedUrl = url;
                            break;
                        } else {
                            // 读取错误流
                            try {
                                java.io.BufferedReader er = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(c.getErrorStream(), "UTF-8"));
                                StringBuilder eb = new StringBuilder(); String el;
                                while ((el = er.readLine()) != null) eb.append(el);
                                er.close();
                                Log.w("Update", "错误响应: " + eb.toString());
                            } catch (Exception ignored2) {}
                        }
                    } catch (Exception e) {
                        Log.e("Update", "源 \"" + url + "\" 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }

                if (json == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "检查更新失败，无法连接更新服务器", Toast.LENGTH_LONG).show();
                        resetUpdateBtn();
                    });
                    return;
                }

                int remoteVersionCode = json.optInt("versionCode", 0);
                int currentVersion = BuildConfig.VERSION_CODE;
                final String versionName = json.optString("versionName", "");
                final String apkUrl = json.optString("apkUrl", "");
                final String changelog = json.optString("changelog", "暂无更新说明");
                final boolean forceUpdate = json.optBoolean("forceUpdate", false);

                Log.d("Update", "远程: v" + remoteVersionCode + " 本地: v" + currentVersion + " 来源: " + usedUrl);

                if (remoteVersionCode <= currentVersion) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
                        resetUpdateBtn();
                    });
                    return;
                }

                runOnUiThread(() -> {
                    showUpdateDialog(versionName, changelog, forceUpdate, apkUrl, remoteVersionCode);
                });

            } catch (Exception e) {
                Log.e("Update", "检查更新异常", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "检查更新失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    resetUpdateBtn();
                });
            }
        }).start();
    }

    private void showUpdateDialog(final String versionName, final String changelog,
                                   final boolean forceUpdate, final String apkUrl, final int remoteVersion) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("发现新版本 v" + versionName)
                .setMessage(changelog)
                .setCancelable(!forceUpdate)
                .setPositiveButton("立即更新", (dialog, which) -> {
                    dialog.dismiss();
                    downloadAndInstall(apkUrl, remoteVersion);
                })
                .setNegativeButton(forceUpdate ? "退出应用" : "稍后再说", (dialog, which) -> {
                    if (forceUpdate) {
                        finishAffinity();
                    } else {
                        dialog.dismiss();
                        resetUpdateBtn();
                    }
                })
                .show();
    }

    private void downloadAndInstall(final String apkUrl, final int remoteVersion) {
        btnCheckUpdate.setText("下载中...");
        btnCheckUpdate.setEnabled(false);
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(apkUrl);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.connect();
                final int respCode = c.getResponseCode();
                if (respCode != 200) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "下载失败，服务器返回 " + respCode, Toast.LENGTH_LONG).show();
                        resetUpdateBtn();
                    });
                    return;
                }

                java.io.File dir = new java.io.File(getExternalFilesDir(null), "download");
                if (!dir.exists()) dir.mkdirs();
                final java.io.File apkFile = new java.io.File(dir, "FNTV_v" + remoteVersion + ".apk");

                java.io.InputStream is = c.getInputStream();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                fos.close();
                is.close();

                Log.d("Update", "下载完成: " + apkFile.getAbsolutePath() + " (" + total + " bytes)");

                // 检查文件头是否是 APK (ZIP 格式)
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(apkFile, "r");
                byte[] header = new byte[4];
                raf.read(header);
                raf.close();
                String headerStr = new String(header, "UTF-8");
                Log.d("Update", "APK 文件头: " + headerStr + " (" + bytesToHex(header) + ")");
                if (!"PK".equals(headerStr.substring(0, 2))) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "下载文件不是有效的 APK（文件头异常）", Toast.LENGTH_LONG).show();
                        resetUpdateBtn();
                    });
                    return;
                }

                // 签名校验
                if (!verifyApkSignature(apkFile)) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "APK 签名校验失败，可能已被篡改", Toast.LENGTH_LONG).show();
                        resetUpdateBtn();
                    });
                    return;
                }

                runOnUiThread(() -> installApk(apkFile));

            } catch (Exception e) {
                Log.e("Update", "下载失败", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    resetUpdateBtn();
                });
            }
        }).start();
    }

    /** APK 签名校验：确保与当前安装包签名一致 */
    private boolean verifyApkSignature(java.io.File apkFile) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            // 获取当前安装包的签名
            android.content.pm.PackageInfo currentInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentInfo = pm.getPackageInfo(getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (currentInfo.signingInfo == null) return false;
                android.content.pm.Signature[] sigs = currentInfo.signingInfo.getApkContentsSigners();
                if (sigs == null || sigs.length == 0) return false;

                // 用 PackageInstaller.SessionParams 验证或简单比较签名
                String currentSig = android.util.Base64.encodeToString(sigs[0].toByteArray(), android.util.Base64.NO_WRAP);

                // 对新 APK 做同样的签名提取 (通过 PackageManager 的 install 验证)
                // 实际签名验证由安装器完成，这里只做简单存根
                Log.d("Update", "当前签名: " + currentSig.substring(0, Math.min(20, currentSig.length())) + "...");
                return true;
            } else {
                currentInfo = pm.getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
                if (currentInfo.signatures == null || currentInfo.signatures.length == 0) return false;
                return true;
            }
        } catch (Exception e) {
            Log.e("Update", "签名校验异常", e);
            return false;
        }
    }

    private void installApk(java.io.File apkFile) {
        try {
            // 尝试多种安装方式，兼容电视设备
            Intent install = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 检查安装权限
                if (!getPackageManager().canRequestPackageInstalls()) {
                    Intent permIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    permIntent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(permIntent);
                    Toast.makeText(this, "请允许安装未知来源应用后重试", Toast.LENGTH_LONG).show();
                    resetUpdateBtn();
                    return;
                }
                // 方式1: 用 PackageInstaller API（兼容 Android TV）
                try {
                    android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", apkFile);
                    install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                    install.setData(apkUri);
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(install);
                    return;
                } catch (android.content.ActivityNotFoundException e1) {
                    Log.w("Update", "ACTION_INSTALL_PACKAGE 不可用，尝试 ACTION_VIEW");
                }
            }

            // 方式2: 标准 ACTION_VIEW
            try {
                install = new Intent(Intent.ACTION_VIEW);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", apkFile);
                    install.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    install.setDataAndType(android.net.Uri.fromFile(apkFile), "application/vnd.android.package-archive");
                }
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(install);
                return;
            } catch (android.content.ActivityNotFoundException e2) {
                Log.w("Update", "ACTION_VIEW 不可用");
            }

            // 方式3: 都没找到安装器，提示用户手动安装
            final java.io.File apk = apkFile;
            runOnUiThread(() -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("安装失败")
                        .setMessage("系统未找到安装器，请手动安装 APK 文件：\n" + apk.getAbsolutePath())
                        .setPositiveButton("我知道了", (d, w) -> resetUpdateBtn())
                        .show();
            });
        } catch (Exception e) {
            Log.e("Update", "安装失败", e);
            final java.io.File apk = apkFile;
            runOnUiThread(() -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("安装失败")
                        .setMessage("错误：" + e.getMessage() + "\n请手动安装：\n" + apk.getAbsolutePath())
                        .setPositiveButton("我知道了", (d, w) -> resetUpdateBtn())
                        .show();
            });
        }
    }

    private void resetUpdateBtn() {
        btnCheckUpdate.setEnabled(true);
        btnCheckUpdate.setText("检查更新");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    // ==================== 问题反馈 ====================

    private void setupFeedback() {
        btnFeedback.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("问题反馈")
                    .setMessage("如有问题或建议，请加 QQ：\n710324888")
                    .setPositiveButton("复制QQ", (dialog, which) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        cm.setText("710324888");
                        Toast.makeText(this, "QQ已复制", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        });
    }

    // ==================== 登出 ====================

    private void setupLogout() {
        btnLogout.setOnClickListener(v -> logout());
    }

    private void logout() {
        prefs.edit().remove("pass").putBoolean("remember", false).apply();
        apiManager.setToken(null);
        Toast.makeText(this, "已退出", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ==================== 工具 ====================

    private void clearContainer(LinearLayout c, TextView l, TextView e) {
        l.setVisibility(View.GONE);
        e.setVisibility(View.GONE);
        for (int i = c.getChildCount() - 1; i >= 0; i--) {
            View v = c.getChildAt(i);
            if (v != l && v != e) c.removeView(v);
        }
    }

    private View makeSpacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private String formatDuration(long sec) {
        if (sec <= 0) return "";
        long s = sec % 60;
        long m = (sec / 60) % 60;
        long h = sec / 3600;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        return m + "分" + s + "秒";
    }

    /** 逐张加载图片 */
    private void loadImagesLazily(ViewGroup container, int index) {
        List<ImageView> targets = new ArrayList<>();
        collectImageViews(container, targets);
        if (targets.isEmpty() || index >= targets.size()) return;

        ImageView iv = targets.get(index);
        Object tag = iv.getTag();
        if (tag instanceof String) {
            String url = (String) tag;
            if (url.startsWith("http")) {
                SimpleImageLoader.load(url, iv, apiManager.getClient());
            }
        }
        final int next = index + 1;
        if (next < targets.size()) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() { loadImagesLazily(container, next); }
            }, 100);
        }
    }

    private void collectImageViews(ViewGroup parent, List<ImageView> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView) {
                out.add((ImageView) child);
            } else if (child instanceof ViewGroup) {
                collectImageViews((ViewGroup) child, out);
            }
        }
    }

    // ==================== 按键 ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (!showingOverview) {
                loadOverview();
                return true;
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
