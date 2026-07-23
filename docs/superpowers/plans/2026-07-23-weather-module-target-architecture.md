# Weather Module Target Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the duplicated wttr.in/keyword weather flow with one Open-Meteo-backed weather service shared by Spring AI Tool Calling and the REST API, supporting current conditions and a three-day forecast.

**Architecture:** `WeatherTool` and `WeatherController` are inbound adapters over `WeatherService`. `WeatherService` resolves relative dates, enforces the three-day range, caches bounded results, and depends only on `WeatherProvider`; `OpenMeteoWeatherProvider` owns geocoding, HTTP, JSON parsing, and provider error translation. All WeChat weather requests go through the existing `ReactAgent`.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring AI Alibaba 1.1.2.3, Spring AI 1.1.8, OkHttp 4.12.0, Jackson, Caffeine, JUnit 5, Mockito, MockWebServer, MockMvc.

## Global Constraints

- Keep Java 21, Spring Boot 3.4.5, Spring AI Alibaba 1.1.2.3, Spring AI 1.1.8, and iLink SDK 1.0.1 unchanged.
- Use Spring AI Alibaba native `@Tool` as the only Tool system.
- Use Open-Meteo as the only weather provider; do not implement fallback providers.
- Support current weather and today/tomorrow/day-after-tomorrow only.
- Do not add a microservice, MCP Server, Redis cache, distributed lock, streaming flow, or new monitoring platform.
- Keep internal weather results structured; only the LLM produces user-facing prose.
- Do not log complete user messages, complete location lists, Tool arguments, provider response bodies, URLs with query parameters, or secrets.
- Every task follows red-green-refactor TDD and ends with a focused commit.
- Do not stage or modify the existing user-owned `.idea/jarRepositories.xml` change.

## Claude Code + DeepSeek Execution Contract

- Claude Code is the coding client; DeepSeek is the model configured behind Claude Code.
- Execute one task at a time in one Claude Code implementation context.
- Before each commit, open a fresh Claude Code context using the same DeepSeek model and give it the task-specific review prompt. The review context is read-only.
- The review context reports concrete file/line findings and must not edit the worktree.
- Return to the implementation context, address valid findings, and rerun the task verification command before committing.
- Start the next task only after the previous task's tests pass and its review findings are resolved.
- Do not run implementation and review contexts concurrently against the same worktree.
- The governing design is `docs/superpowers/specs/2026-07-23-weather-module-target-architecture-design.md`.

## Target File Map

### New production files

- `src/main/java/com/demo/demo/Service/weather/WeatherError.java` — stable failure categories.
- `src/main/java/com/demo/demo/Service/weather/WeatherException.java` — typed application/provider exception.
- `src/main/java/com/demo/demo/Service/weather/WeatherQuery.java` — raw location and optional date expression.
- `src/main/java/com/demo/demo/Service/weather/WeatherLocation.java` — normalized place, coordinates, and time zone.
- `src/main/java/com/demo/demo/Service/weather/CurrentConditions.java` — current measurements.
- `src/main/java/com/demo/demo/Service/weather/DailyForecast.java` — one daily forecast.
- `src/main/java/com/demo/demo/Service/weather/WeatherSnapshot.java` — provider result before date selection.
- `src/main/java/com/demo/demo/Service/weather/WeatherReport.java` — selected current/forecast result returned to adapters.
- `src/main/java/com/demo/demo/Service/weather/WeatherProvider.java` — provider port.
- `src/main/java/com/demo/demo/Service/weather/WeatherProperties.java` — URLs, timeouts, cache TTLs, limits.
- `src/main/java/com/demo/demo/Service/weather/WeatherConfiguration.java` — `Clock` and named OkHttp client beans.
- `src/main/java/com/demo/demo/Service/weather/OpenMeteoWeatherProvider.java` — geocoding and forecast adapter.
- `src/main/java/com/demo/demo/Service/weather/WeatherService.java` — date resolution, cache, and use-case orchestration.
- `src/main/java/com/demo/demo/Service/tool/WeatherToolResult.java` — structured Tool result.
- `src/main/java/com/demo/demo/controller/dto/WeatherBatchRequest.java` — typed batch request.
- `src/main/java/com/demo/demo/controller/dto/WeatherBatchItem.java` — typed per-city batch result.
- `src/main/java/com/demo/demo/controller/dto/WeatherBatchResponse.java` — typed batch response.

### New test files

- `src/test/java/com/demo/demo/Service/weather/WeatherDomainTest.java`
- `src/test/java/com/demo/demo/Service/weather/OpenMeteoWeatherProviderTest.java`
- `src/test/java/com/demo/demo/Service/weather/WeatherServiceTest.java`
- `src/test/java/com/demo/demo/Service/tool/WeatherToolTest.java`
- `src/test/java/com/demo/demo/controller/WeatherControllerTest.java`
- `src/test/java/com/demo/demo/Service/WeatherAgentRoutingTest.java`

### Modified files

- `pom.xml` — add Caffeine and MockWebServer.
- `src/main/resources/application.yml` — safe weather defaults.
- `src/main/resources/application-local.example.yml` — optional weather overrides without secrets.
- `src/main/java/com/demo/demo/Service/tool/WeatherTool.java` — thin structured adapter.
- `src/main/java/com/demo/demo/controller/WeatherController.java` — use WeatherService and typed DTOs.
- `src/main/java/com/demo/demo/execption/GlobalExpectionHandler.java` — map WeatherException for REST.
- `src/main/java/com/demo/demo/execption/ResponseCodeEnum.java` — add ambiguous-location and invalid-date codes.
- `src/main/java/com/demo/demo/controller/BotController.java` — remove keyword weather route.
- `src/main/java/com/demo/demo/Service/AIService.java` — expose one agent chat entry point.
- `src/test/java/com/demo/demo/Service/tool/ToolRegistryTest.java` — retain only time/image annotation tests or rename it.
- `src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java` — inspect the new provider/service instead of WeatherUtil.
- `docs/CURRENT_STATE.md` — describe the new flow and test boundaries.
- `docs/FEATURE_MATRIX.md` — record Tool routing and deterministic tests.
- `docs/MANUAL_TEST_CHECKLIST.md` — update weather cases.

### Deleted files

- `src/main/java/com/demo/demo/Utils/WeatherUtil.java`
- `src/main/java/com/demo/demo/Service/tool/Tool.java`
- `src/main/java/com/demo/demo/Service/tool/ToolRegistry.java`
- `src/test/java/com/demo/demo/WeatherUtilTest.java`
- `src/test/java/com/demo/demo/WeatherStabilityTest.java`

---

### Task 1: Define weather contracts, configuration, and error vocabulary

**Files:**

- Create all domain/configuration files listed under `Service/weather`, except `OpenMeteoWeatherProvider.java` and `WeatherService.java`.
- Modify: `src/main/java/com/demo/demo/execption/ResponseCodeEnum.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Test: `src/test/java/com/demo/demo/Service/weather/WeatherDomainTest.java`

**Interfaces:**

- Produces: `WeatherProvider.resolveLocation(String)` and `WeatherProvider.fetch(WeatherLocation)`.
- Produces: immutable records consumed by all later tasks.
- Produces: a `Clock` bean named by type and an `OkHttpClient` bean qualified as `weatherHttpClient`.

- [ ] **Step 1: Write the failing domain-contract test**

```java
package com.demo.demo.Service.weather;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeatherDomainTest {

    @Test
    void queryRejectsBlankLocation() {
        WeatherException error = assertThrows(
                WeatherException.class,
                () -> new WeatherQuery(" ", "明天"));
        assertEquals(WeatherError.LOCATION_REQUIRED, error.getError());
    }

    @Test
    void locationKeepsProviderTimeZone() {
        WeatherLocation location = new WeatherLocation(
                "杭州", "Hangzhou", "浙江", "中国",
                30.2741, 120.1551, ZoneId.of("Asia/Shanghai"));
        assertEquals("Asia/Shanghai", location.zoneId().getId());
    }

    @Test
    void dailyForecastKeepsTypedUnits() {
        DailyForecast forecast = new DailyForecast(
                LocalDate.of(2026, 7, 24), 31.5, 24.2, 61, 70);
        assertEquals(70, forecast.precipitationProbability());
    }
}
```

- [ ] **Step 2: Run the test and verify the contracts do not exist**

Run:

```powershell
.\mvnw.cmd -Dtest=WeatherDomainTest test
```

Expected: compilation fails because `WeatherQuery`, `WeatherException`, `WeatherLocation`, and `DailyForecast` do not exist.

- [ ] **Step 3: Add the exact domain types**

Use these signatures:

```java
public enum WeatherError {
    LOCATION_REQUIRED,
    LOCATION_AMBIGUOUS,
    LOCATION_NOT_FOUND,
    INVALID_DATE,
    PROVIDER_TIMEOUT,
    PROVIDER_UNAVAILABLE,
    PROVIDER_RESPONSE_INVALID
}
```

```java
@Getter
public final class WeatherException extends RuntimeException {
    private final WeatherError error;

    public WeatherException(WeatherError error, String message) {
        super(message);
        this.error = error;
    }

    public WeatherException(WeatherError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }
}
```

```java
public record WeatherQuery(String location, String dateExpression) {
    public WeatherQuery {
        if (location == null || location.isBlank()) {
            throw new WeatherException(WeatherError.LOCATION_REQUIRED, "请提供要查询的城市");
        }
        location = location.trim();
        dateExpression = dateExpression == null ? "" : dateExpression.trim();
    }
}
```

```java
public record WeatherLocation(
        String requestedName,
        String name,
        String adminArea,
        String country,
        double latitude,
        double longitude,
        ZoneId zoneId) {}
```

```java
public record CurrentConditions(
        double temperatureCelsius,
        double apparentTemperatureCelsius,
        int relativeHumidityPercent,
        double windSpeedKmh,
        int windDirectionDegrees,
        int weatherCode) {}
```

```java
public record DailyForecast(
        LocalDate date,
        double maxTemperatureCelsius,
        double minTemperatureCelsius,
        int weatherCode,
        int precipitationProbability) {}
```

```java
public record WeatherSnapshot(
        WeatherLocation location,
        Instant observedAt,
        CurrentConditions current,
        List<DailyForecast> dailyForecasts,
        String source) {
    public WeatherSnapshot {
        dailyForecasts = List.copyOf(dailyForecasts);
    }
}
```

```java
public enum WeatherReportType { CURRENT, FORECAST }
```

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherReport(
        WeatherReportType type,
        WeatherLocation location,
        LocalDate targetDate,
        Instant observedAt,
        CurrentConditions current,
        DailyForecast forecast,
        String source) {}
```

```java
public interface WeatherProvider {
    WeatherLocation resolveLocation(String requestedLocation);
    WeatherSnapshot fetch(WeatherLocation location);
}
```

- [ ] **Step 4: Add exact configuration defaults**

`WeatherProperties` must be a mutable `@ConfigurationProperties(prefix = "weather")` Spring component with:

```java
private String geocodingBaseUrl = "https://geocoding-api.open-meteo.com/v1/search";
private String forecastBaseUrl = "https://api.open-meteo.com/v1/forecast";
private Duration connectTimeout = Duration.ofSeconds(3);
private Duration readTimeout = Duration.ofSeconds(5);
private Duration locationCacheTtl = Duration.ofHours(24);
private Duration currentCacheTtl = Duration.ofMinutes(10);
private Duration forecastCacheTtl = Duration.ofMinutes(60);
private int maxCacheEntries = 256;
private int batchLimit = 10;
```

`WeatherConfiguration` must expose:

```java
@Bean
Clock weatherClock() {
    return Clock.systemUTC();
}

@Bean
@Qualifier("weatherHttpClient")
OkHttpClient weatherHttpClient(WeatherProperties properties) {
    return new OkHttpClient.Builder()
            .connectTimeout(properties.getConnectTimeout())
            .readTimeout(properties.getReadTimeout())
            .build();
}
```

Add matching `weather:` YAML keys to both configuration files. Do not add credentials because Open-Meteo needs none.

- [ ] **Step 5: Add REST error codes**

Add:

```java
CITY_AMBIGUOUS("40006", "城市名称不明确，请补充省份或国家"),
WEATHER_DATE_INVALID("40007", "仅支持查询今天至后天的天气"),
```

- [ ] **Step 6: Run focused and full contract tests**

Run:

```powershell
.\mvnw.cmd -Dtest=WeatherDomainTest,GlobalExceptionHandlerTest test
```

Expected: both test classes pass with zero failures.

- [ ] **Step 7: Review in a fresh Claude Code context using DeepSeek**

Use this prompt:

```text
Review Task 1 against docs/superpowers/specs/2026-07-23-weather-module-target-architecture-design.md.
Check record immutability, exact units, Spring configuration binding, secret-free defaults, and whether any type leaks Open-Meteo JSON.
Report only concrete findings with file and line. Do not edit files.
```

- [ ] **Step 8: Commit**

```powershell
git add pom.xml src/main/java/com/demo/demo/Service/weather src/main/java/com/demo/demo/execption/ResponseCodeEnum.java src/main/resources/application.yml src/main/resources/application-local.example.yml src/test/java/com/demo/demo/Service/weather/WeatherDomainTest.java
git commit -m "feat: define weather domain contracts"
```

Do not include `.idea/jarRepositories.xml`.

---

### Task 2: Implement the Open-Meteo provider with deterministic HTTP tests

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/com/demo/demo/Service/weather/OpenMeteoWeatherProvider.java`
- Test: `src/test/java/com/demo/demo/Service/weather/OpenMeteoWeatherProviderTest.java`

**Interfaces:**

- Consumes: `WeatherProvider`, `WeatherLocation`, `WeatherSnapshot`, `WeatherProperties`.
- Produces: `resolveLocation(String)` and `fetch(WeatherLocation)` without exposing provider JSON.

- [ ] **Step 1: Add MockWebServer test dependency**

```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>4.12.0</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write failing provider tests**

Use two `MockWebServer` instances so geocoding and forecast URLs are independently asserted. Enqueue these bodies:

```json
{"results":[{"name":"Hangzhou","admin1":"Zhejiang","country":"China","latitude":30.2741,"longitude":120.1551,"timezone":"Asia/Shanghai"}]}
```

```json
{
  "timezone":"Asia/Shanghai",
  "current":{"time":"2026-07-23T14:00","temperature_2m":33.1,"relative_humidity_2m":58,"apparent_temperature":37.0,"weather_code":2,"wind_speed_10m":12.4,"wind_direction_10m":135},
  "daily":{"time":["2026-07-23","2026-07-24","2026-07-25"],"weather_code":[2,61,3],"temperature_2m_max":[35.0,32.0,31.0],"temperature_2m_min":[27.0,25.0,24.0],"precipitation_probability_max":[20,70,30]}
}
```

The tests must assert:

```java
assertEquals("Hangzhou", location.name());
assertEquals(ZoneId.of("Asia/Shanghai"), location.zoneId());
assertEquals(33.1, snapshot.current().temperatureCelsius());
assertEquals(3, snapshot.dailyForecasts().size());
assertEquals(70, snapshot.dailyForecasts().get(1).precipitationProbability());
assertEquals("open-meteo", snapshot.source());
```

Also add one test per failure class:

- empty `results` → `LOCATION_NOT_FOUND`;
- malformed JSON → `PROVIDER_RESPONSE_INVALID`;
- HTTP 429/503 → `PROVIDER_UNAVAILABLE`;
- `SocketTimeoutException` → `PROVIDER_TIMEOUT`.

- [ ] **Step 3: Run tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=OpenMeteoWeatherProviderTest test
```

Expected: compilation fails because `OpenMeteoWeatherProvider` does not exist.

- [ ] **Step 4: Implement geocoding**

Constructor:

```java
public OpenMeteoWeatherProvider(
        @Qualifier("weatherHttpClient") OkHttpClient httpClient,
        ObjectMapper objectMapper,
        WeatherProperties properties)
```

Build the geocoding request with `HttpUrl.Builder`, never string concatenation:

```java
HttpUrl url = requireBaseUrl(properties.getGeocodingBaseUrl()).newBuilder()
        .addQueryParameter("name", requestedLocation)
        .addQueryParameter("count", "5")
        .addQueryParameter("language", "zh")
        .addQueryParameter("format", "json")
        .build();
```

Rules:

- no result → `LOCATION_NOT_FOUND`;
- more than one result with different `admin1`/`country` and the first result is not an exact case-insensitive name match → `LOCATION_AMBIGUOUS`;
- missing coordinates or timezone → `PROVIDER_RESPONSE_INVALID`;
- preserve the original user input in `requestedName`.

- [ ] **Step 5: Implement forecast fetching**

Request exactly these fields:

```text
current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m
daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max
timezone=<resolved time zone>
forecast_days=3
temperature_unit=celsius
wind_speed_unit=kmh
```

Parse `current.time` using the resolved location zone and convert it to `Instant`. Validate that every daily array has the same length as `daily.time`; otherwise throw `PROVIDER_RESPONSE_INVALID`.

- [ ] **Step 6: Centralize safe HTTP error translation**

The provider's request helper must:

```java
catch (SocketTimeoutException e) {
    throw new WeatherException(WeatherError.PROVIDER_TIMEOUT,
            "天气服务响应超时", e);
} catch (IOException e) {
    throw new WeatherException(WeatherError.PROVIDER_UNAVAILABLE,
            "天气服务暂时不可用", e);
}
```

For non-2xx responses, inspect only the status code. Do not read, return, or log the response body. Logs may contain operation name, status code, and elapsed milliseconds only.

- [ ] **Step 7: Run provider and privacy tests**

Run:

```powershell
.\mvnw.cmd -Dtest=OpenMeteoWeatherProviderTest,ServerLogPrivacyTest test
```

Expected: provider tests pass; privacy test may still fail because it references the old WeatherUtil path. If it fails only for that missing migration, record the exact failure and complete Task 7 before claiming the full privacy suite passes.

- [ ] **Step 8: Review in a fresh Claude Code context using DeepSeek**

```text
Review Task 2 only. Verify URL encoding, response closure, timeout/error mapping, array-length validation, time-zone conversion, unit parameters, and that logs never include URLs, query parameters, locations, or response bodies. Report file/line findings; do not edit.
```

- [ ] **Step 9: Commit**

```powershell
git add pom.xml src/main/java/com/demo/demo/Service/weather/OpenMeteoWeatherProvider.java src/test/java/com/demo/demo/Service/weather/OpenMeteoWeatherProviderTest.java
git commit -m "feat: add Open-Meteo weather provider"
```

---

### Task 3: Add date resolution and bounded caching in WeatherService

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/com/demo/demo/Service/weather/WeatherService.java`
- Test: `src/test/java/com/demo/demo/Service/weather/WeatherServiceTest.java`

**Interfaces:**

- Consumes: `WeatherProvider.resolveLocation`, `WeatherProvider.fetch`, `Clock`, `WeatherProperties`.
- Produces: `WeatherReport query(WeatherQuery query)`.

- [ ] **Step 1: Add Caffeine**

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

- [ ] **Step 2: Write failing service tests with a fake provider**

Use a fixed clock:

```java
Clock clock = Clock.fixed(
        Instant.parse("2026-07-23T06:00:00Z"),
        ZoneOffset.UTC);
```

The fake location uses `Asia/Shanghai`, making local today `2026-07-23`. Required behaviors:

```java
assertEquals(WeatherReportType.CURRENT,
        service.query(new WeatherQuery("杭州", "")).type());
assertEquals(LocalDate.of(2026, 7, 24),
        service.query(new WeatherQuery("杭州", "明天")).targetDate());
assertEquals(LocalDate.of(2026, 7, 25),
        service.query(new WeatherQuery("杭州", "后天")).targetDate());
```

Also assert:

- ISO `2026-07-24` selects the same forecast as “明天”;
- yesterday and day+3 throw `INVALID_DATE`;
- a missing requested forecast date throws `PROVIDER_RESPONSE_INVALID`;
- two identical current queries call `provider.fetch()` once;
- two equivalent forecast queries (`明天` and `2026-07-24`) call `provider.fetch()` once after normalization;
- more than `maxCacheEntries` distinct keys never makes a cache exceed the configured bound.

- [ ] **Step 3: Run service tests and verify failure**

```powershell
.\mvnw.cmd -Dtest=WeatherServiceTest test
```

Expected: compilation fails because `WeatherService` does not exist.

- [ ] **Step 4: Implement three bounded caches**

Create:

```java
private final Cache<String, WeatherLocation> locationCache;
private final Cache<String, WeatherReport> currentCache;
private final Cache<String, WeatherReport> forecastCache;
```

Build them with:

```java
Caffeine.newBuilder()
        .maximumSize(properties.getMaxCacheEntries())
        .expireAfterWrite(properties.getLocationCacheTtl())
        .build();
```

Use `currentCacheTtl` and `forecastCacheTtl` for their respective caches. Normalize location cache keys with `trim().toLowerCase(Locale.ROOT)`.

- [ ] **Step 5: Implement exact date semantics**

```java
private LocalDate resolveDate(String expression, ZoneId zoneId) {
    LocalDate today = LocalDate.now(clock.withZone(zoneId));
    if (expression == null || expression.isBlank() || "今天".equals(expression)) {
        return today;
    }
    return switch (expression) {
        case "明天" -> today.plusDays(1);
        case "后天" -> today.plusDays(2);
        default -> parseIsoDate(expression);
    };
}
```

Reject dates before `today` or after `today.plusDays(2)` with `WeatherError.INVALID_DATE`. A target equal to today returns `WeatherReportType.CURRENT`; tomorrow or the day after returns `FORECAST`.

- [ ] **Step 6: Implement orchestration**

Public signature:

```java
public WeatherReport query(WeatherQuery query)
```

Sequence:

1. resolve/cached location;
2. resolve target date in the location time zone;
3. build a normalized cache key from normalized location and target date;
4. return cached report if present;
5. fetch one three-day snapshot;
6. select current or matching daily forecast;
7. cache and return the selected report.

Do not convert weather codes to Chinese prose in this class.

- [ ] **Step 7: Run tests**

```powershell
.\mvnw.cmd -Dtest=WeatherDomainTest,WeatherServiceTest,OpenMeteoWeatherProviderTest test
```

Expected: all three test classes pass with zero failures.

- [ ] **Step 8: Review in a fresh Claude Code context using DeepSeek**

```text
Review Task 3. Check target-location time-zone semantics, today/tomorrow/day-after range, cache-key equivalence, maximum size, TTL choice, null handling, and whether provider calls can be duplicated. Report concrete file/line findings only.
```

- [ ] **Step 9: Commit**

```powershell
git add pom.xml src/main/java/com/demo/demo/Service/weather/WeatherService.java src/test/java/com/demo/demo/Service/weather/WeatherServiceTest.java
git commit -m "feat: orchestrate weather queries"
```

---

### Task 4: Convert WeatherTool into a structured Spring AI adapter

**Files:**

- Modify: `src/main/java/com/demo/demo/Service/tool/WeatherTool.java`
- Create: `src/main/java/com/demo/demo/Service/tool/WeatherToolResult.java`
- Create: `src/test/java/com/demo/demo/Service/tool/WeatherToolTest.java`
- Modify: `src/test/java/com/demo/demo/Service/tool/ToolRegistryTest.java`

**Interfaces:**

- Consumes: `WeatherService.query(new WeatherQuery(location, date))`.
- Produces: `WeatherToolResult queryWeather(String location, String date)`.

- [ ] **Step 1: Write failing Tool tests**

```java
WeatherService service = mock(WeatherService.class);
WeatherTool tool = new WeatherTool(service);
when(service.query(any())).thenReturn(report);

WeatherToolResult result = tool.queryWeather("杭州", "明天");

assertEquals(WeatherToolResult.Status.SUCCESS, result.status());
assertSame(report, result.data());
verify(service).query(new WeatherQuery("杭州", "明天"));
```

Add parameterized exception mapping assertions:

| WeatherError | Tool status |
|---|---|
| LOCATION_REQUIRED | LOCATION_REQUIRED |
| LOCATION_AMBIGUOUS | LOCATION_AMBIGUOUS |
| LOCATION_NOT_FOUND | LOCATION_NOT_FOUND |
| INVALID_DATE | INVALID_DATE |
| PROVIDER_TIMEOUT | PROVIDER_TIMEOUT |
| PROVIDER_UNAVAILABLE | PROVIDER_UNAVAILABLE |
| PROVIDER_RESPONSE_INVALID | PROVIDER_UNAVAILABLE |

Reflection must assert method `queryWeather(String, String)` has `@Tool` and that the optional date parameter has `@ToolParam(required = false)`.

- [ ] **Step 2: Run and verify failure**

```powershell
.\mvnw.cmd -Dtest=WeatherToolTest test
```

Expected: compilation fails because the new constructor, method, and result type do not exist.

- [ ] **Step 3: Implement the result protocol**

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherToolResult(Status status, String message, WeatherReport data) {
    public enum Status {
        SUCCESS,
        LOCATION_REQUIRED,
        LOCATION_AMBIGUOUS,
        LOCATION_NOT_FOUND,
        INVALID_DATE,
        PROVIDER_TIMEOUT,
        PROVIDER_UNAVAILABLE
    }

    public static WeatherToolResult success(WeatherReport data) {
        return new WeatherToolResult(Status.SUCCESS, "天气查询成功", data);
    }

    public static WeatherToolResult failure(Status status, String message) {
        return new WeatherToolResult(status, message, null);
    }
}
```

- [ ] **Step 4: Implement the thin Tool adapter**

```java
@Tool(description = "查询城市或地区的当前天气及未来三天预报。用户询问温度、冷热、降雨、是否带伞、今天、明天或后天天气时必须使用。缺少地点时不要猜测，应先询问用户。")
public WeatherToolResult queryWeather(
        @ToolParam(description = "城市或地区名称，例如杭州、浙江杭州、London") String location,
        @ToolParam(description = "可选日期：今天、明天、后天或 yyyy-MM-dd；不填表示当前天气", required = false) String date)
```

Catch only `WeatherException` and map it to the table above. Let unexpected programming exceptions propagate to the Agent boundary; do not expose `exception.getMessage()` blindly.

- [ ] **Step 5: Remove obsolete weather assertions from ToolRegistryTest**

Keep time and image annotation tests. Rename the class/file to `ToolAnnotationTest` if the class no longer tests a registry, and update the Maven test name used later.

- [ ] **Step 6: Run Tool tests**

```powershell
.\mvnw.cmd -Dtest=WeatherToolTest,ToolAnnotationTest test
```

Expected: both classes pass. If the old filename is retained, substitute `ToolRegistryTest` for `ToolAnnotationTest`.

- [ ] **Step 7: Review in a fresh Claude Code context using DeepSeek**

```text
Review Task 4 for Spring AI schema correctness, optional date handling, stable machine-readable statuses, safe exception mapping, and accidental natural-language formatting in the Tool. Report file/line findings only.
```

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/demo/demo/Service/tool/WeatherTool.java src/main/java/com/demo/demo/Service/tool/WeatherToolResult.java src/test/java/com/demo/demo/Service/tool
git commit -m "feat: expose structured weather tool"
```

---

### Task 5: Move the REST API onto WeatherService and typed DTOs

**Files:**

- Modify: `src/main/java/com/demo/demo/controller/WeatherController.java`
- Create: `src/main/java/com/demo/demo/controller/dto/WeatherBatchRequest.java`
- Create: `src/main/java/com/demo/demo/controller/dto/WeatherBatchItem.java`
- Create: `src/main/java/com/demo/demo/controller/dto/WeatherBatchResponse.java`
- Modify: `src/main/java/com/demo/demo/execption/GlobalExpectionHandler.java`
- Test: `src/test/java/com/demo/demo/controller/WeatherControllerTest.java`

**Interfaces:**

- Consumes: `WeatherService.query(WeatherQuery)`.
- Produces: `Response<WeatherReport>` for single queries and `Response<WeatherBatchResponse>` for batch queries.

- [ ] **Step 1: Write failing MockMvc tests**

Build standalone MockMvc with a mocked WeatherService and `GlobalExpectionHandler`. Cover:

```text
GET /api/weather?city=杭州&date=明天
GET /api/weather/杭州?date=明天
POST /api/weather/batch {"cities":["杭州","北京"],"date":"明天"}
```

Assertions:

- success JSON contains `data.type`, `data.location.name`, `data.targetDate`, and `data.source`;
- missing city is rejected;
- batch size 11 returns code `40002`;
- mixed batch success/failure keeps both items and does not expose exception causes;
- `WeatherException(LOCATION_NOT_FOUND)` maps to `CITY_NOT_FOUND`;
- `WeatherException(INVALID_DATE)` maps to `WEATHER_DATE_INVALID`;
- timeout maps to `THIRD_PARTY_TIMEOUT`.

- [ ] **Step 2: Run and verify failure**

```powershell
.\mvnw.cmd -Dtest=WeatherControllerTest test
```

Expected: tests fail because the Controller still returns maps and calls WeatherUtil.

- [ ] **Step 3: Add typed batch DTOs**

```java
public record WeatherBatchRequest(List<String> cities, String date) {}
```

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherBatchItem(
        String city,
        boolean success,
        WeatherReport data,
        String errorCode,
        String errorMessage) {}
```

```java
public record WeatherBatchResponse(
        int total,
        int successCount,
        int failureCount,
        List<WeatherBatchItem> items) {
    public WeatherBatchResponse {
        items = List.copyOf(items);
    }
}
```

- [ ] **Step 4: Refactor WeatherController**

Constructor-inject `WeatherService` and `WeatherProperties`. Implement:

```java
@GetMapping
public Response<WeatherReport> getWeather(
        @RequestParam String city,
        @RequestParam(required = false, defaultValue = "") String date)
```

The path endpoint delegates with both `city` and `date`. Batch rules:

- reject null/empty cities;
- reject more than `properties.getBatchLimit()`;
- trim each city;
- call WeatherService sequentially for at most 10 items;
- map each `WeatherException` to a stable item error code;
- log counts and duration only, never the city list.

- [ ] **Step 5: Add a WeatherException handler**

In `GlobalExpectionHandler`, add an `@ExceptionHandler(WeatherException.class)` before the generic handler. Map:

```text
LOCATION_REQUIRED       -> CITY_NAME_EMPTY
LOCATION_AMBIGUOUS      -> CITY_AMBIGUOUS
LOCATION_NOT_FOUND      -> CITY_NOT_FOUND
INVALID_DATE            -> WEATHER_DATE_INVALID
PROVIDER_TIMEOUT        -> THIRD_PARTY_TIMEOUT
PROVIDER_UNAVAILABLE    -> THIRD_PARTY_UNAVAILABLE
PROVIDER_RESPONSE_INVALID -> WEATHER_PARSE_ERROR
```

Return safe enum messages. Log request path and WeatherError only.

- [ ] **Step 6: Run Controller tests**

```powershell
.\mvnw.cmd -Dtest=WeatherControllerTest,GlobalExceptionHandlerTest test
```

Expected: all tests pass with zero failures.

- [ ] **Step 7: Review in a fresh Claude Code context using DeepSeek**

```text
Review Task 5 for DTO stability, batch limit enforcement, partial-failure behavior, exception-to-response mapping, and location privacy in logs. Report concrete findings only.
```

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/demo/demo/controller/WeatherController.java src/main/java/com/demo/demo/controller/dto src/main/java/com/demo/demo/execption/GlobalExpectionHandler.java src/test/java/com/demo/demo/controller/WeatherControllerTest.java
git commit -m "feat: serve weather through application service"
```

---

### Task 6: Make ReactAgent the only WeChat weather router

**Files:**

- Modify: `src/main/java/com/demo/demo/controller/BotController.java`
- Modify: `src/main/java/com/demo/demo/Service/AIService.java`
- Create: `src/test/java/com/demo/demo/Service/WeatherAgentRoutingTest.java`

**Interfaces:**

- Consumes: `AIService.chat(userId, originalMessage)`.
- Produces: one Agent call for every ordinary weather message.

- [ ] **Step 1: Write the failing routing regression test**

Use the same `BotService`, mocked `ILinkClient`, `ReflectionTestUtils`, and login setup pattern as `ImageAutoReplyTest`. Configure:

```java
when(imageGenerationService.isConfigured()).thenReturn(false);
when(aiService.isConfigured()).thenReturn(true);
when(aiService.chat("wx-user", "明天会下雨吗")).thenReturn("请告诉我想查询的城市。");
```

After `controller.initAutoReply()` and `botService.processTextMessage(...)`, assert:

```java
verify(aiService, timeout(1000)).chat("wx-user", "明天会下雨吗");
verify(aiService, never()).chat(eq("wx-user"), contains("以下是实时天气数据"));
verify(client, timeout(1000)).sendTextMessage(
        any(LoginCredentials.class),
        eq("wx-user"),
        eq("ctx-token"),
        eq("请告诉我想查询的城市。"));
```

Add cases for “今天热不热” and “那后天呢？”, asserting the original text reaches the Agent unchanged.

- [ ] **Step 2: Run and verify failure**

```powershell
.\mvnw.cmd -Dtest=WeatherAgentRoutingTest test
```

Expected: at least the existing “天气” keyword case fails because BotController queries WeatherUtil and synthesizes a prompt.

- [ ] **Step 3: Remove the keyword route**

From `BotController` remove:

- `WeatherUtil` and `BizException` imports;
- the entire weather `if (text.contains("天气"))` branch;
- `extractCity()`.

Leave the existing AI configuration guard. Replace:

```java
String aiReply = aiService.chatWithTools(fromUser, text);
```

with:

```java
String aiReply = aiService.chat(fromUser, text);
```

- [ ] **Step 4: Remove the redundant AIService alias**

Delete `chatWithTools()`. Keep Tool registration in `ReactAgent.builder().tools(...)`; therefore `chat()` remains Tool-capable.

Add this weather policy to the Agent system prompt configuration:

```text
涉及实时天气、温度、冷热、降雨或未来天气时必须调用天气工具，不得凭记忆回答；缺少地点时先询问用户；不得修改工具返回的日期和数值。
```

- [ ] **Step 5: Run routing and existing media tests**

```powershell
.\mvnw.cmd -Dtest=WeatherAgentRoutingTest,ImageAutoReplyTest,VoiceMessageReplyTest,VoiceFallbackReplyTest test
```

Expected: all listed tests pass with zero failures.

- [ ] **Step 6: Review in a fresh Claude Code context using DeepSeek**

```text
Review Task 6. Prove there is one WeChat weather route, the original message reaches ReactAgent unchanged, Tool registration remains active, image/voice routes are not broken, and no synthetic weather prompt pollutes conversation memory. Report file/line findings only.
```

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/demo/demo/controller/BotController.java src/main/java/com/demo/demo/Service/AIService.java src/main/resources/application.yml src/test/java/com/demo/demo/Service/WeatherAgentRoutingTest.java
git commit -m "fix: route weather requests through agent tools"
```

---

### Task 7: Remove obsolete weather and legacy Tool implementations

**Files:**

- Delete: `src/main/java/com/demo/demo/Utils/WeatherUtil.java`
- Delete: `src/main/java/com/demo/demo/Service/tool/Tool.java`
- Delete: `src/main/java/com/demo/demo/Service/tool/ToolRegistry.java`
- Delete: `src/test/java/com/demo/demo/WeatherUtilTest.java`
- Delete: `src/test/java/com/demo/demo/WeatherStabilityTest.java`
- Modify: `src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java`

**Interfaces:**

- Consumes: all call sites migrated in Tasks 4–6.
- Produces: one Spring AI Tool system and zero default-CI network weather tests.

- [ ] **Step 1: Add a failing source hygiene assertion**

Update `ServerLogPrivacyTest` to read:

```java
source("Service/weather/OpenMeteoWeatherProvider.java"),
source("Service/weather/WeatherService.java"),
source("Service/tool/WeatherTool.java"),
```

Remove `source("Utils/WeatherUtil.java")`. Add:

```java
assertFalse(serverLogs.contains("requestedLocation"));
assertFalse(serverLogs.contains("response.body().string()"));
assertFalse(serverLogs.contains("request.url()"));
```

- [ ] **Step 2: Verify no production references remain**

Run:

```powershell
rg -n "WeatherUtil|chatWithTools|implements Tool|new ToolRegistry|Service\.tool\.Tool" src/main src/test
```

Expected before deletion: matches exist only in the files scheduled for deletion or in stale tests. Any other production match must be migrated before continuing.

- [ ] **Step 3: Delete obsolete files**

Delete exactly the five files listed above. Do not edit historical changelogs or prior design documents; they describe past states.

- [ ] **Step 4: Run hygiene and privacy tests**

```powershell
.\mvnw.cmd -Dtest=ServerLogPrivacyTest,WeatherDomainTest,OpenMeteoWeatherProviderTest,WeatherServiceTest,WeatherToolTest,WeatherControllerTest,WeatherAgentRoutingTest test
```

Expected: all listed tests pass without accessing the public internet.

- [ ] **Step 5: Prove the old architecture is absent**

```powershell
rg -n "WeatherUtil|chatWithTools|implements Tool|class ToolRegistry" src/main src/test
```

Expected: no matches and `rg` exits with code 1.

- [ ] **Step 6: Review in a fresh Claude Code context using DeepSeek**

```text
Review Task 7 as a deletion audit. Search for remaining WeatherUtil, legacy ToolRegistry, direct wttr.in calls, live-network JUnit tests, and privacy-test blind spots. Report exact files/lines. Do not request changes to historical docs that intentionally record old behavior.
```

- [ ] **Step 7: Commit**

```powershell
git add -- src/main/java/com/demo/demo/Utils/WeatherUtil.java src/main/java/com/demo/demo/Service/tool/Tool.java src/main/java/com/demo/demo/Service/tool/ToolRegistry.java src/test/java/com/demo/demo/WeatherUtilTest.java src/test/java/com/demo/demo/WeatherStabilityTest.java src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java
git commit -m "chore: remove legacy weather paths"
```

---

### Task 8: Update current documentation and run final verification

**Files:**

- Modify: `docs/CURRENT_STATE.md`
- Modify: `docs/FEATURE_MATRIX.md`
- Modify: `docs/MANUAL_TEST_CHECKLIST.md`

**Interfaces:**

- Consumes: completed implementation and test evidence.
- Produces: accurate operator/developer guidance and final verification record.

- [ ] **Step 1: Update current-state documentation**

Record:

- one data flow: WeChat/ASR → ReactAgent → WeatherTool → WeatherService → OpenMeteoWeatherProvider;
- current + three-day forecast behavior;
- location-required and three-day-range behavior;
- cache TTL defaults and batch limit;
- Tool/REST shared service;
- default tests are deterministic and offline;
- real Open-Meteo validation remains a manual check.

Do not rewrite historical changelogs or prior specs.

- [ ] **Step 2: Update the manual checklist**

Include exact cases:

```text
杭州天气
杭州明天会下雨吗
杭州后天天气
今天热不热
明天会下雨吗
北京天气 → 那后天呢？
2026-08-01 杭州天气（超出三天时应拒绝）
不存在的城市天气
```

For “今天热不热” and “明天会下雨吗”, expected behavior without a known location is a location clarification, not a guessed city.

- [ ] **Step 3: Run the complete test suite**

```powershell
.\mvnw.cmd test
```

Expected: Maven exits 0 with zero failures and zero errors. Pre-existing intentionally disabled Spring-context tests may remain skipped; report their exact count.

- [ ] **Step 4: Build the JAR**

```powershell
.\mvnw.cmd clean package
```

Expected: `BUILD SUCCESS`, zero test failures, and a JAR under `target/`.

- [ ] **Step 5: Run static architecture checks**

```powershell
rg -n "wttr\.in|WeatherUtil|chatWithTools|class ToolRegistry|implements Tool" src/main src/test
```

Expected: no matches.

```powershell
rg -n "queryWeather|WeatherService|OpenMeteoWeatherProvider" src/main/java
```

Expected: WeatherTool delegates to WeatherService; WeatherController delegates to WeatherService; only OpenMeteoWeatherProvider performs weather HTTP calls.

- [ ] **Step 6: Perform deferred manual checks**

With real LLM and network configuration, execute the updated `docs/MANUAL_TEST_CHECKLIST.md`. Record manual WeChat and Open-Meteo checks as deferred if the current machine lacks credentials or iLink login. Do not weaken automated tests to accommodate missing external services.

- [ ] **Step 7: Run the final spec review in a fresh Claude Code context using DeepSeek**

```text
Review the complete weather implementation against every acceptance criterion in docs/superpowers/specs/2026-07-23-weather-module-target-architecture-design.md. Check git diff from commit 41aba34 to HEAD, test evidence, architecture boundaries, privacy, and scope. Categorize findings P0-P3 with file/line. Do not suggest unrelated refactors.
```

- [ ] **Step 8: Re-run affected tests after review fixes**

Run the focused tests for every changed component, then rerun:

```powershell
.\mvnw.cmd test
```

Expected: Maven exits 0.

- [ ] **Step 9: Commit documentation**

```powershell
git add docs/CURRENT_STATE.md docs/FEATURE_MATRIX.md docs/MANUAL_TEST_CHECKLIST.md
git commit -m "docs: document weather tool architecture"
```

## Completion Checklist

- [ ] WeChat text and ASR weather questions enter the same ReactAgent path.
- [ ] BotController contains no weather keywords, city extraction, or direct weather calls.
- [ ] WeatherTool and WeatherController depend on WeatherService only.
- [ ] WeatherService depends on WeatherProvider, not Open-Meteo JSON.
- [ ] OpenMeteoWeatherProvider is the only external weather adapter.
- [ ] Current weather and the next two calendar days use the target location's time zone.
- [ ] Tool results are structured and failures are machine-readable.
- [ ] Cache size and TTL are bounded and tested.
- [ ] Batch REST requests are limited to 10 cities.
- [ ] Default CI tests make no public weather calls.
- [ ] Legacy WeatherUtil and ToolRegistry sources are absent.
- [ ] Logs omit message bodies, locations, query URLs, and provider response bodies.
- [ ] `.\mvnw.cmd test` and `.\mvnw.cmd clean package` both exit 0.
