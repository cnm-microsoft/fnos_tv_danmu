package com.fntv.app.api;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class FnApiManager {
    private static FnApiManager instance;
    private FnApiService apiService;
    private final AuthInterceptor authInterceptor;
    private OkHttpClient okHttpClient; // 保留引用，供图片加载等复用

    private FnApiManager() {
        authInterceptor = new AuthInterceptor();
    }

    public static synchronized FnApiManager getInstance() {
        if (instance == null) instance = new FnApiManager();
        return instance;
    }

    public void updateBaseUrl(String baseUrl) {
        String effectiveBaseUrl = baseUrl;
        if (effectiveBaseUrl.endsWith("/")) {
            effectiveBaseUrl = effectiveBaseUrl.substring(0, effectiveBaseUrl.length() - 1);
        }
        int vIndex = effectiveBaseUrl.indexOf("/v");
        if (vIndex != -1) {
            effectiveBaseUrl = effectiveBaseUrl.substring(0, vIndex);
        }

        okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .build();

        retrofit2.Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(effectiveBaseUrl + "/v/")
                .client(okHttpClient)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build();
        apiService = retrofit.create(FnApiService.class);
    }

    public void setToken(String token) { authInterceptor.setToken(token); }
    public String getToken() { return authInterceptor.getToken(); }
    public FnApiService getApi() { return apiService; }

    /** 获取 OkHttpClient（图片加载直接复用，AuthInterceptor 自动处理签名） */
    public OkHttpClient getClient() { return okHttpClient; }
}
