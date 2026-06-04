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

/** 弹幕渲染层 — 自适应刷新率 */
public class DanmuView extends View {
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
    private float rowSpacing = 1.8f;   // 行间距倍数 1.2 ~ 3.0

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

    private void updateStyle() {
        paint.setTextSize(fontSize * screenDensity);
        paint.setAlpha((int)(255 * opacity));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setPlayTime(long ms) { playTime = ms / 1000f; }

    public void loadDanmu(List<DanmuComment> comments) {
        items.clear();
        active.clear();
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

    /** 跳转时重置弹幕状态（对应 JS 版 onSeek） */
    public void seekToTime(long ms) {
        playTime = ms / 1000f;
        active.clear();
        // 二分定位到当前时间对应的弹幕索引
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
        active.clear();
        eIdx = 0;
    }

    private static class DanmuItem {
        String text;
        float time;
        int color;
        int type;
        float fontSize;
        float x, y, speed, tw;
    }

    public static class DanmuComment {
        public String text;
        public float time;
        public int color = 0xFFFFFFFF;
        public int type;
        public float fontSize;
    }

    private final List<DanmuItem> active = new ArrayList<>();
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
            float lnH = fontSize * screenDensity * rowSpacing;  // 用 rowSpacing 替代写死的 1.8
            int maxRow = Math.max(1, (int) (areaH / lnH));

            // 发射
            while (eIdx < items.size()) {
                DanmuItem src = items.get(eIdx);
                float diff = playTime - src.time;

                if (diff > 0.5f) { eIdx++; continue; }
                if (diff < 0) break;

                // 发射错开：每条弹幕需要等一个随机 0~0.3s
                if (diff < Math.random() * 0.3f) break;

                if (Math.random() * 100 >= densityPct) { eIdx++; continue; }
                if (active.size() >= maxActive) break;

                DanmuItem a = new DanmuItem();
                a.text = src.text;
                a.color = src.color;
                a.type = src.type;
                a.tw = paint.measureText(src.text);

                // 速度按文字长度变化 + 随机波动
                int len = Math.max(1, src.text.length());
                float baseSpeed = 120 + len * 18;
                float randomFactor = 0.8f + (float)(Math.random() * 0.4);
                a.speed = baseSpeed * randomFactor * speedMul;

                float rowY = findRow(a.tw, w, lnH, maxRow);
                if (rowY < 0) {
                    eIdx++;
                    continue;
                }

                a.y = rowY;
                a.x = w + 5;
                active.add(a);
                eIdx++;
            }

            // 更新位置
            List<DanmuItem> dead = new ArrayList<>();
            for (DanmuItem a : active) {
                a.x -= a.speed * dt;
                if (a.x + a.tw < -100) dead.add(a);
            }
            active.removeAll(dead);

            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /**
     * 行避让：随机间隔（20dp ~ 60dp）
     */
    private float findRow(float newTw, int screenW, float lnH, int maxRow) {
        float gap = (20f + (float)(Math.random() * 40)) * screenDensity;

        for (int r = 0; r < maxRow; r++) {
            float rowY = lnH + r * lnH;
            boolean blocked = false;

            for (DanmuItem a : active) {
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

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        paint.setTextSize(fontSize * screenDensity);
        for (DanmuItem a : active) {
            if (showOutline) {
                paint.setColor(Color.BLACK);
                paint.setAlpha((int)(255 * opacity));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(0.8f * screenDensity);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                c.drawText(a.text, a.x, a.y, paint);
            }
            paint.setColor(a.color);
            paint.setAlpha((int)(255 * opacity));
            paint.setStyle(Paint.Style.FILL);
            c.drawText(a.text, a.x, a.y, paint);
        }
    }
}
