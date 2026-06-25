package com.fntv.app;

import android.content.Context;
import android.util.AttributeSet;

import com.shuyu.gsyvideoplayer.video.NormalGSYVideoPlayer;

/**
 * 自定义GSYVideoPlayer，暴露changeTextureViewShowType()用于运行时切换画面比例。
 */
public class CustomGSYVideoPlayer extends NormalGSYVideoPlayer {

    public CustomGSYVideoPlayer(Context context, Boolean fullFlag) {
        super(context, fullFlag);
    }

    public CustomGSYVideoPlayer(Context context) {
        super(context);
    }

    public CustomGSYVideoPlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 暴露protected方法，供Activity切换比例时调用 */
    public void applyTextureViewShowType() {
        changeTextureViewShowType();
    }
}
