package com.fntv.app.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * 飞牛影视 API 签名工具类
 */
public class FnAuthUtils {
    private static final String API_KEY = "vD2P9mXkL3Qr5YtUwEa6FbHcJdN1zR0Wg";
    private static final String API_SECRET = "CA8CEF1E-5B91-4F82-9DB7-E8D6A9B1C2D4";

    public static String getMd5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String generateNonce() {
        Random rand = new Random();
        return String.valueOf(rand.nextInt(900000) + 100000);
    }

    /**
     * 生成请求所需的 Authx 签名字符串
     */
    public static String genAuthx(String url, String jsonBody) {
        String nonce = generateNonce();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String dataMd5 = jsonBody != null ? getMd5(jsonBody) : "";

        String signStr = API_KEY + "_" + url + "_" + nonce + "_" + timestamp + "_" + dataMd5 + "_" + API_SECRET;

        return "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + getMd5(signStr);
    }
}
