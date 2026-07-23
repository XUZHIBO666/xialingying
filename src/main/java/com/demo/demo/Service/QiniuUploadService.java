package com.demo.demo.Service;

import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

/**
 * 七牛云上传服务 —— 将图片 byte[] 上传到七牛并返回公网 URL。
 * 百炼多模态 API 不支持 Data URI，必须用公网 URL 传图。
 */
@Slf4j
@Service
public class QiniuUploadService {

    @Value("${qiniu.access-key}")
    private String accessKey;

    @Value("${qiniu.secret-key}")
    private String secretKey;

    @Value("${qiniu.bucket}")
    private String bucket;

    @Value("${qiniu.domain}")
    private String domain;

    private UploadManager uploadManager;
    private Auth auth;

    @PostConstruct
    public void init() {
        Configuration cfg = new Configuration(Region.autoRegion());
        this.uploadManager = new UploadManager(cfg);
        this.auth = Auth.create(accessKey, secretKey);
        log.info("[七牛云] 初始化完成 bucket={} domain={}", bucket, domain);
    }

    /**
     * 上传图片到七牛云，返回公网访问 URL。
     *
     * @param imageBytes 图片字节数组
     * @param mimeType   MIME 类型（用于生成文件扩展名）
     * @return 公网 URL
     */
    public String upload(byte[] imageBytes, String mimeType) throws IOException {
        String ext = mimeType.equals("image/jpeg") ? "jpg"
                : mimeType.equals("image/png") ? "png" : "webp";
        String fileKey = "vision/" + UUID.randomUUID() + "." + ext;

        String token = auth.uploadToken(bucket);
        com.qiniu.http.Response response = uploadManager.put(imageBytes, fileKey, token);

        if (response.isOK()) {
            String url = domain + "/" + fileKey;
            log.info("[七牛云] 上传成功 fileKey={} size={}KB url={}",
                    fileKey, imageBytes.length / 1024, url);
            return url;
        }
        throw new IOException("七牛云上传失败: " + response.statusCode + " " + response.bodyString());
    }
}
