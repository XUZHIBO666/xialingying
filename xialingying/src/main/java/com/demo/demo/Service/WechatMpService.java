package com.demo.demo.Service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 微信公众号 API 服务：素材上传 + 客服消息发送
 * 用于通过公众号发送可下载/可播放的语音消息
 *
 * 配置（application-local.yml）：
 * wechat.mp.app-id: 你的AppID
 * wechat.mp.app-secret: 你的AppSecret
 */
@Slf4j
@Service
public class WechatMpService {

    @Value("${wechat.mp.app-id:}")
    private String appId;

    @Value("${wechat.mp.app-secret:}")
    private String appSecret;

    @Value("${wechat.mp.my-open-id:}")
    private String myOpenId;

    private volatile String accessToken;
    private volatile long tokenExpiresAt;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public boolean isConfigured() {
        return appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank()
                && myOpenId != null && !myOpenId.isBlank();
    }

    // ==================== Access Token ====================

    private synchronized String getAccessToken() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 300_000) {
            return accessToken;
        }
        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential"
                + "&appid=" + appId + "&secret=" + appSecret;
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            String json = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("[公众号] 获取 token 失败 HTTP {} body={}", response.code(), json);
                throw new RuntimeException("获取 access_token 失败: " + json);
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            accessToken = obj.get("access_token").getAsString();
            int expiresIn = obj.get("expires_in").getAsInt();
            tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L;
            log.info("[公众号] access_token 已刷新 expiresIn={}s", expiresIn);
            return accessToken;
        }
    }

    // ==================== 素材上传 ====================

    /**
     * 上传 MP3 语音到公众号素材库，返回 media_id
     * 公众号语音格式支持：AMR、MP3（16K/44.1K/48K采样率，单声道）
     */
    public String uploadVoice(byte[] mp3Bytes) throws Exception {
        String token = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/media/upload?access_token="
                + token + "&type=voice";

        RequestBody fileBody = RequestBody.create(mp3Bytes,
                MediaType.parse("audio/mpeg"));
        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("media", "voice.mp3", fileBody)
                .build();

        Request request = new Request.Builder().url(url).post(multipartBody).build();

        log.info("[公众号] 上传语音 size={} bytes", mp3Bytes.length);
        try (Response response = client.newCall(request).execute()) {
            String json = response.body() != null ? response.body().string() : "";
            log.info("[公众号] 上传语音响应 HTTP {} body={}",
                    response.code(),
                    json.length() > 500 ? json.substring(0, 500) : json);
            if (!response.isSuccessful()) {
                log.error("[公众号] 上传语音失败 HTTP {} body={}", response.code(), json);
                throw new RuntimeException("上传语音失败: " + json);
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("errcode") && obj.get("errcode").getAsInt() != 0) {
                log.error("[公众号] 上传语音 API 错误 errcode={} errmsg={}",
                        obj.get("errcode").getAsInt(),
                        obj.has("errmsg") ? obj.get("errmsg").getAsString() : "unknown");
                throw new RuntimeException("上传语音失败: " + json);
            }
            String mediaId = obj.get("media_id").getAsString();
            log.info("[公众号] 上传语音成功 media_id={}", mediaId);
            return mediaId;
        }
    }

    // ==================== 发送客服消息 ====================

    /**
     * 把 iLink 用户 ID 转成公众号 OpenID
     * iLink 格式：o9cq804FVXr5aKYnYSwhO-HSaUeM@im.wechat → OpenID: o9cq804FVXr5aKYnYSwhO-HSaUeM
     */
    public static String toOpenId(String ilinkUserId) {
        if (ilinkUserId != null && ilinkUserId.contains("@")) {
            return ilinkUserId.substring(0, ilinkUserId.indexOf("@"));
        }
        return ilinkUserId;
    }

    /** 发送语音给配置的 my-open-id（测试用） */
    public void sendVoiceMessage(String mediaId) throws Exception {
        sendVoiceMessage(myOpenId, mediaId);
    }

    /**
     * 发送语音客服消息（用户需在 48h 内与公众号有过互动）
     */
    public void sendVoiceMessage(String openId, String mediaId) throws Exception {
        String token = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=" + token;

        JsonObject voice = new JsonObject();
        voice.addProperty("media_id", mediaId);

        JsonObject msg = new JsonObject();
        msg.addProperty("touser", openId);
        msg.addProperty("msgtype", "voice");
        msg.add("voice", voice);

        RequestBody body = RequestBody.create(msg.toString(),
                MediaType.parse("application/json"));
        Request request = new Request.Builder().url(url).post(body).build();

        log.info("[公众号] 发送语音消息 openId={} media_id={}", openId, mediaId);
        try (Response response = client.newCall(request).execute()) {
            String json = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("[公众号] 发送语音失败 HTTP {} body={}", response.code(), json);
                throw new RuntimeException("发送语音失败: " + json);
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            int errCode = obj.get("errcode").getAsInt();
            if (errCode != 0) {
                log.error("[公众号] 发送语音失败 errcode={} errmsg={}",
                        errCode, obj.get("errmsg").getAsString());
                throw new RuntimeException("发送语音失败: " + obj.get("errmsg").getAsString());
            }
            log.info("[公众号] 语音消息发送成功 openId={}", openId);
        }
    }

    /**
     * 发送文本客服消息
     */
    public void sendTextMessage(String openId, String text) throws Exception {
        String token = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=" + token;

        JsonObject textObj = new JsonObject();
        textObj.addProperty("content", text);

        JsonObject msg = new JsonObject();
        msg.addProperty("touser", openId);
        msg.addProperty("msgtype", "text");
        msg.add("text", textObj);

        RequestBody body = RequestBody.create(msg.toString(),
                MediaType.parse("application/json"));
        Request request = new Request.Builder().url(url).post(body).build();

        log.info("[公众号] 发送文本消息 openId={} text={}", openId,
                text.length() > 50 ? text.substring(0, 50) + "..." : text);
        try (Response response = client.newCall(request).execute()) {
            String json = response.body() != null ? response.body().string() : "";
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.get("errcode").getAsInt() != 0) {
                log.warn("[公众号] 发送文本失败 errmsg={}", obj.get("errmsg").getAsString());
            }
        }
    }
}
