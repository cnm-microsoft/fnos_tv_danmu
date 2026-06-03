package com.fntv.app.model;

import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 观看历史管理器
 * 在 SharedPreferences 中存储最近 100 条观看记录
 */
public class WatchHistoryManager {

    private static final String KEY = "watch_history";
    private static final int MAX_RECORDS = 100;

    private final SharedPreferences prefs;
    private final Gson gson;

    public WatchHistoryManager(SharedPreferences prefs) {
        this.prefs = prefs;
        this.gson = new Gson();
    }

    /** 获取所有观看记录（按更新时间倒序） */
    public List<WatchRecord> getAll() {
        String json = prefs.getString(KEY, "[]");
        Type type = new TypeToken<List<WatchRecord>>() {}.getType();
        List<WatchRecord> list = gson.fromJson(json, type);
        if (list == null) return new ArrayList<>();

        // 按 updatedAt 倒序
        Collections.sort(list, new Comparator<WatchRecord>() {
            @Override
            public int compare(WatchRecord a, WatchRecord b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return list;
    }

    /** 获取前 N 条记录 */
    public List<WatchRecord> getTop(int n) {
        List<WatchRecord> all = getAll();
        if (all.size() > n) {
            all = all.subList(0, n);
        }
        return all;
    }

    /** 添加或更新观看记录 */
    public void put(WatchRecord record) {
        record.updatedAt = System.currentTimeMillis();

        List<WatchRecord> list = getAll();

        // 同系列（同 dedupKey）只保留最新的
        String dedupKey = record.getDedupKey();
        int existIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getDedupKey().equals(dedupKey)) {
                existIndex = i;
                break;
            }
        }
        if (existIndex >= 0) {
            list.remove(existIndex);
        }

        list.add(0, record);

        if (list.size() > MAX_RECORDS) {
            list = list.subList(0, MAX_RECORDS);
        }

        save(list);
    }

    /** 删除一条记录 */
    public void remove(String guid) {
        List<WatchRecord> list = getAll();
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).guid.equals(guid)) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            list.remove(idx);
            save(list);
        }
    }

    /** 清空所有记录 */
    public void clear() {
        prefs.edit().remove(KEY).apply();
    }

    private void save(List<WatchRecord> list) {
        String json = gson.toJson(list);
        prefs.edit().putString(KEY, json).apply();
    }
}
