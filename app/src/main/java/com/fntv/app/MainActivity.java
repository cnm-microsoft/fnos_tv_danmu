package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.LoginRequest;
import com.fntv.app.api.model.LoginResponseData;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    private EditText etHost, etUser, etPass;
    private CheckBox cbRemember;
    private RelativeLayout cbWrapper;
    private Button btnLogin;
    private SharedPreferences prefs;
    private boolean isLoggingIn = false;
    private long loginStartTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // API 21+ 沉浸式状态栏/导航栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(0xFF1A1A1A);
        }

        etHost = findViewById(R.id.etHost);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        cbRemember = findViewById(R.id.cbRememberCheck);
        cbWrapper = findViewById(R.id.cbRemember);
        btnLogin = findViewById(R.id.btnLogin);

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);

        // 恢复数据
        String savedHost = prefs.getString("host", "http://192.168.10.158:5666");
        etHost.setText(savedHost);
        etUser.setText(prefs.getString("user", "video"));
        boolean remember = prefs.getBoolean("remember", false);
        cbRemember.setChecked(remember);
        if (remember) {
            etPass.setText(prefs.getString("pass", ""));
        }

        // 登录按钮点击
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });

        // 遥控器 / DPAD 操作：点击 CheckBox 外层布局时切换
        cbWrapper.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cbRemember.toggle();
            }
        });

        // 默认焦点给第一个输入框
        etHost.requestFocus();

        // 已保存密码 → 自动登录
        if (remember && !savedHost.isEmpty() && !prefs.getString("user", "").isEmpty()
                && !prefs.getString("pass", "").isEmpty()) {
            btnLogin.postDelayed(() -> {
                btnLogin.setText("自动登录中...");
                doLoginInternal(savedHost, prefs.getString("user", ""), prefs.getString("pass", ""));
            }, 300);
        }
    }

    private void doLogin() {
        if (isLoggingIn) return;

        String host = etHost.getText().toString().trim();
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString().trim();

        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "所有字段都不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        // 立即锁定按钮，让用户看到"登录中..."
        isLoggingIn = true;
        btnLogin.setEnabled(false);
        btnLogin.setText("登录中...");

        // 用post确保按钮渲染后再执行后续操作（避免某些设备上卡UI）
        btnLogin.post(new Runnable() {
            @Override
            public void run() {
                doLoginInternal(host, user, pass);
            }
        });
    }

    private void doLoginInternal(String host, String user, String pass) {
        loginStartTime = System.currentTimeMillis();

        // 智能补全 URL 协议头
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("host", host);
        editor.putString("user", user);
        editor.putBoolean("remember", cbRemember.isChecked());
        if (cbRemember.isChecked()) {
            editor.putString("pass", pass);
        } else {
            editor.remove("pass");
        }
        editor.apply();

        FnApiManager.getInstance().updateBaseUrl(host);
        Log.d(TAG, "尝试登录到: " + host + ", 用户: " + user);

        FnApiManager.getInstance().getApi().login(new LoginRequest(user, pass)).enqueue(new Callback<ApiResponse<LoginResponseData>>() {
            @Override
            public void onResponse(Call<ApiResponse<LoginResponseData>> call, Response<ApiResponse<LoginResponseData>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().code == 0) {
                        String token = response.body().data.token;
                        FnApiManager.getInstance().setToken(token);
                        long elapsed = System.currentTimeMillis() - loginStartTime;
                        Log.i(TAG, "登录成功！耗时 " + elapsed + "ms, Token: " + token);
                        Toast.makeText(MainActivity.this, "登录成功！", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(MainActivity.this, HomeActivity.class));
                        finish();
                        return;
                    } else {
                        Log.w(TAG, "登录失败，Code: " + response.body().code + ", Msg: " + response.body().msg);
                        Toast.makeText(MainActivity.this, "登录失败: " + response.body().msg, Toast.LENGTH_LONG).show();
                    }
                } else {
                    Log.e(TAG, "API 响应异常，Code: " + response.code() + ", Message: " + response.message());
                    Toast.makeText(MainActivity.this, "服务器响应异常，请重试", Toast.LENGTH_LONG).show();
                }
                resetLoginState();
            }

            @Override
            public void onFailure(Call<ApiResponse<LoginResponseData>> call, Throwable t) {
                Log.e(TAG, "网络请求失败: " + t.getMessage(), t);
                Toast.makeText(MainActivity.this, "网络连接失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
                resetLoginState();
            }
        });
    }

    /** 登录失败后恢复按钮状态 */
    private void resetLoginState() {
        isLoggingIn = false;
        btnLogin.setEnabled(true);
        btnLogin.setText("登 录");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 遥控器返回键处理
        if (keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
