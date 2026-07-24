package com.demo.demo.controller;

import com.demo.demo.Utils.Response;
import com.demo.demo.Utils.WeatherUtil;
import com.demo.demo.execption.BizException;
import com.demo.demo.execption.ResponseCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 天气查询 REST API
 * 支持单城市查询和多城市批量查询
 */
@Slf4j
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    /**
     * 单城市天气查询
     *
     * GET /api/weather?city=杭州
     * GET /api/weather?city=Beijing
     */
    @GetMapping
    public Response<Map<String, Object>> getWeather(@RequestParam String city) {
        log.info("[WeatherController] 收到天气查询请求，城市: {}", city);

        // 空参数校验
        if (city == null || city.trim().isEmpty()) {
            throw new BizException(ResponseCodeEnum.CITY_NAME_EMPTY);
        }

        String weatherInfo = WeatherUtil.getWeather(city.trim());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city.trim());
        result.put("weatherInfo", weatherInfo);

        return Response.success(result);
    }

    /**
     * 路径参数方式查询
     *
     * GET /api/weather/杭州
     */
    @GetMapping("/{city}")
    public Response<Map<String, Object>> getWeatherByPath(@PathVariable String city) {
        log.info("[WeatherController] 收到天气查询请求(路径参数)，城市: {}", city);
        return getWeather(city);
    }

    /**
     * 多城市批量查询
     *
     * POST /api/weather/batch
     * Body: { "cities": ["杭州", "Beijing", "New York"] }
     */
    @PostMapping("/batch")
    public Response<Map<String, Object>> batchQuery(@RequestBody Map<String, List<String>> body) {
        List<String> cities = body.get("cities");
        if (cities == null || cities.isEmpty()) {
            throw new BizException(ResponseCodeEnum.PARAM_EMPTY, "城市列表不能为空");
        }

        log.info("[WeatherController] 批量天气查询，城市列表: {}", cities);

        List<Map<String, Object>> successList = new ArrayList<>();
        List<Map<String, Object>> failList = new ArrayList<>();

        for (String city : cities) {
            try {
                String weatherInfo = WeatherUtil.getWeather(city);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("city", city);
                item.put("weatherInfo", weatherInfo);
                successList.add(item);
            } catch (BizException e) {
                log.warn("[WeatherController] 批量查询中「{}」失败: {}", city, e.getMessage());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("city", city);
                item.put("error", e.getMessage());
                failList.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", cities.size());
        result.put("successCount", successList.size());
        result.put("failCount", failList.size());
        result.put("success", successList);
        result.put("failed", failList);

        log.info("[WeatherController] 批量查询完成: 成功 {}, 失败 {}",
                successList.size(), failList.size());

        return Response.success(result);
    }
}
