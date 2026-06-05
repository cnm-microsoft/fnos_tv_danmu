package com.fntv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 弹幕渲染层 — 滚动 + 顶部/底部固定弹幕，自适应刷新率 */
public class DanmuView extends View {
    private static final String TAG = "DanmuView";

    private final Paint paint;
    private final List<DanmuItem> items = new ArrayList<>();
    private float screenDensity;
    private boolean running = false;
    private long lastFrame;
    private volatile float playTime = 0;
    private int maxActive = 40;
    private float speedMul = 1f;
    private float opacity = 0.85f;
    private int areaPct = 35;
    private float fontSize = 22f;
    private boolean showOutline = true;
    private int densityPct = 100;
    private float rowSpacing = 1.8f;
    private boolean showScroll = true;
    private boolean showTop = true;
    private boolean showBottom = true;

    private final List<DanmuItem> activeScroll = new ArrayList<>();
    private final List<DanmuItem> activeStatic = new ArrayList<>();

    public DanmuView(Context c) { this(c, null); }
    public DanmuView(Context c, android.util.AttributeSet a) { this(c, a, 0); }
    public DanmuView(Context c, android.util.AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        screenDensity = c.getResources().getDisplayMetrics().density;
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        updateStyle();
    }

    public void setMaxActive(int v) { maxActive = v; }
    public void setSpeedMul(float v) { speedMul = v; }
    public void setOpacity(float v) { opacity = v; updateStyle(); }
    public void setAreaPct(int v) { areaPct = v; }
    public void setFontSize(float v) { fontSize = v; updateStyle(); }
    public void setShowOutline(boolean v) { showOutline = v; updateStyle(); }
    public void setDensityPct(int v) { densityPct = v; }
    public void setRowSpacing(float v) { rowSpacing = v; }
    public void setShowScroll(boolean v) { showScroll = v; }
    public void setShowTop(boolean v) { showTop = v; }
    public void setShowBottom(boolean v) { showBottom = v; }

    private void updateStyle() {
        paint.setTextSize(fontSize * screenDensity);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setPlayTime(long ms) { playTime = ms / 1000f; }

    public void loadDanmu(List<DanmuComment> comments) {
        items.clear();
        activeScroll.clear();
        activeStatic.clear();
        eIdx = 0;
        if (comments == null) return;
        for (DanmuComment c : comments) {
            DanmuItem item = new DanmuItem();
            item.text = c.text;
            item.time = c.time;
            item.color = c.color != 0 ? c.color : 0xFFFFFFFF;
            item.type = c.type;
            item.fontSize = c.fontSize > 0 ? c.fontSize : fontSize;
            items.add(item);
        }
        Collections.sort(items, (a, b) -> Float.compare(a.time, b.time));
    }

    public void start() {
        if (running) return;
        running = true;
        lastFrame = System.nanoTime();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    public void pause() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    public void seekToTime(long ms) {
        playTime = ms / 1000f;
        activeScroll.clear();
        activeStatic.clear();
        int lo = 0, hi = items.size();
        while (lo < hi) {
            int mid = (lo + hi) >> 1;
            if (items.get(mid).time <= playTime) lo = mid + 1;
            else hi = mid;
        }
        eIdx = lo;
    }

    public void resume() {
        if (items.isEmpty()) return;
        running = true;
        lastFrame = System.nanoTime();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    public void clear() {
        items.clear();
        activeScroll.clear();
        activeStatic.clear();
        eIdx = 0;
    }

    private static class DanmuItem {
        String text;
        float time;
        int color;
        int type;     // 1=滚动 4=底部 5=顶部
        float fontSize;
        float x, y, speed, tw;
        float ttl;
    }

    public static class DanmuComment {
        public String text;
        public float time;
        public int color = 0xFFFFFFFF;
        public int type = 1;
        public float fontSize;
    }

    private int eIdx = 0;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;

            long now = System.nanoTime();
            float dt = (now - lastFrame) / 1_000_000_000f;
            lastFrame = now;
            if (dt > 0.1f) dt = 0.016f;

            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) {
                Choreographer.getInstance().postFrameCallback(this);
                return;
            }

            float areaH = h * areaPct / 100f;
            float lnH = fontSize * screenDensity * rowSpacing;
            int maxRow = Math.max(1, (int) (areaH / lnH));
            int maxRowBottom = Math.max(1, Math.min(3, (int) (h / lnH)));

            // ── 发射 ──
            while (eIdx < items.size()) {
                DanmuItem src = items.get(eIdx);
                float diff = playTime - src.time;

                if (diff > 0.5f) { eIdx++; continue; }
                if (diff < 0) break;
                if (diff < Math.random() * 0.3f) break;
                if (Math.random() * 100 >= densityPct) { eIdx++; continue; }
                if (activeScroll.size() + activeStatic.size() >= maxActive) break;

                // 按类型开关过滤
                if (src.type == 1 && !showScroll) { eIdx++; continue; }
                if (src.type == 5 && !showTop) { eIdx++; continue; }
                if (src.type == 4 && !showBottom) { eIdx++; continue; }

                boolean isStatic = (src.type == 4 || src.type == 5);

                if (isStatic) {
                    DanmuItem a = new DanmuItem();
                    a.text = src.text;
                    a.color = src.color;
                    a.type = src.type;
                    a.tw = paint.measureText(src.text);
                    a.speed = 0;
                    a.ttl = 5.0f;

                    boolean isTop = (src.type == 5);
                    float rowY;

                    if (isTop) {
                        rowY = findStaticRowTop(lnH, maxRow);
                    } else {
                        rowY = findStaticRowBottom(h, lnH, maxRowBottom);
                    }

                    if (rowY < 0) { eIdx++; continue; }

                    a.y = rowY;
                    a.x = w / 2f - a.tw / 2f;
                    activeStatic.add(a);
                    eIdx++;
                } else {
                    DanmuItem a = new DanmuItem();
                    a.text = src.text;
                    a.color = src.color;
                    a.type = src.type;
                    a.tw = paint.measureText(src.text);

                    int len = Math.max(1, src.text.length());
                    float baseSpeed = 250 + len * 5;
                    a.speed = baseSpeed * speedMul;

                    float rowY = findScrollRow(a.tw, w, lnH, maxRow);
                    if (rowY < 0) { eIdx++; continue; }

                    a.y = rowY;
                    a.x = w + 5;
                    activeScroll.add(a);
                    eIdx++;
                }
            }

            // ── 更新滚动弹幕位置 ──
            List<DanmuItem> deadScroll = new ArrayList<>();
            for (DanmuItem a : activeScroll) {
                a.x -= a.speed * dt;
                if (a.x + a.tw < -100) deadScroll.add(a);
            }
            activeScroll.removeAll(deadScroll);

            // ── 更新固定弹幕 TTL ──
            List<DanmuItem> deadStatic = new ArrayList<>();
            for (DanmuItem a : activeStatic) {
                a.ttl -= dt;
                if (a.ttl <= 0) deadStatic.add(a);
            }
            activeStatic.removeAll(deadStatic);

            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /**
     * 滚动弹幕行避让：随机间隔（20dp ~ 60dp）
     */
    private float findScrollRow(float newTw, int screenW, float lnH, int maxRow) {
        float gap = (20f + (float)(Math.random() * 40)) * screenDensity;

        for (int r = 0; r < maxRow; r++) {
            float rowY = lnH + r * lnH;
            boolean blocked = false;

            for (DanmuItem a : activeScroll) {
                if (Math.abs(a.y - rowY) < lnH * 0.5f) {
                    if (a.x + a.tw + gap > screenW) {
                        blocked = true;
                        break;
                    }
                }
            }
            if (!blocked) return rowY;
        }
        return -1;
    }

    /**
     * 顶部固定弹幕：从上往下找空行，受显示区域限制
     */
    private float findStaticRowTop(float lnH, int maxRow) {
        for (int r = 0; r < maxRow; r++) {
            float rowY = lnH + r * lnH;
            boolean blocked = false;
            for (DanmuItem a : activeStatic) {
                if (Math.abs(a.y - rowY) < lnH * 0.5f) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) return rowY;
        }
        return -1;
    }

    /**
     * 底部固定弹幕：从屏幕底部往上找空行，最多3行，不受显示区域限制
     * row0 = 屏幕最底部, row1 = 往上一行, row2 = 再往上一行
     */
    private float findStaticRowBottom(int screenH, float lnH, int maxRow) {
        for (int attempt = 0; attempt < maxRow; attempt++) {
            float rowY = screenH - lnH * 0.2f - attempt * lnH;
            boolean blocked = false;
            for (DanmuItem a : activeStatic) {
                if (Math.abs(a.y - rowY) < lnH * 0.5f) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) return rowY;
        }
        return -1;
    }

    /** 绘制单条弹幕（描边 + 填充） */
    private void drawDanmu(Canvas c, DanmuItem a, float alphaMul) {
        int alpha = (int)(255 * opacity * alphaMul);
        int baseR = Color.red(a.color);
        int baseG = Color.green(a.color);
        int baseB = Color.blue(a.color);

        if (showOutline) {
            paint.setColor(Color.argb(alpha, 0, 0, 0));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.8f * screenDensity);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawText(a.text, a.x, a.y, paint);
        }
        paint.setColor(Color.argb(alpha, baseR, baseG, baseB));
        paint.setStyle(Paint.Style.FILL);
        c.drawText(a.text, a.x, a.y, paint);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        paint.setTextSize(fontSize * screenDensity);

        // 底层：滚动弹幕
        for (DanmuItem a : activeScroll) {
            drawDanmu(c, a, 1f);
        }

        // 顶层：固定弹幕
        for (DanmuItem a : activeStatic) {
            drawDanmu(c, a, 1f);
        }
    }
}
