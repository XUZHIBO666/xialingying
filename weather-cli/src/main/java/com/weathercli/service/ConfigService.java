package com.weathercli.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 配置服务 — 读取 config.properties 和环境变量。
 * 环境变量优先级高于配置文件。
 */
public class ConfigService {

    private static final Logger LOG = Logger.getLogger(ConfigService.class.getName());

    private final Properties props = new Properties();
    private static ConfigService instance;

    private ConfigService() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (is != null) {
                props.load(is);
                LOG.info("配置文件加载成功");
            } else {
                LOG.warning("config.properties 未找到，使用默认配置");
            }
        } catch (IOException e) {
            LOG.warning("配置文件加载失败: " + e.getMessage());
        }
    }

    public static synchronized ConfigService getInstance() {
        if (instance == null) {
            instance = new ConfigService();
        }
        return instance;
    }

    /**
     * 获取配置值，优先环境变量，其次配置文件。
     */
    public String get(String key) {
        // 1. 环境变量（转换为大写 + 点变下划线）
        String envKey = key.toUpperCase().replace('.', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        // 2. 配置文件
        return props.getProperty(key);
    }

    /**
     * 获取配置值，带默认值。
     */
    public String get(String key, String defaultValue) {
        String value = get(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /**
     * 检查 API Key 是否已配置（非占位符）。
     */
    public boolean isApiKeyConfigured(String key) {
        String value = get(key);
        return value != null && !value.isBlank()
            && !value.startsWith("your-")
            && !value.equals("${" + key.toUpperCase().replace('.', '_') + "}");
    }
}
