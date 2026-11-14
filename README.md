# ai-model-bridge

一个面向 Java 21 的通用模型接入库，以及一个可选的 OpenAI 兼容网关。

- 核心库不依赖 Spring，只依赖 Jackson 与 JDK `HttpClient`。
- 统一 OpenAI-compatible、Anthropic、Gemini、Ollama 和 llama.cpp 的协议差异。
- 提供 Chat、SSE 流式、原生 Tool Calling、Embedding、Rerank、模型发现与能力探针。
- 内置 SSRF 防护、统一错误分类、密钥/提示词日志脱敏和请求超时。
- 可选 Spring Boot Starter 仅做自动装配，不包含数据库、JPA、Flyway 或任何业务表。
- 可选 Server 暴露 `/v1/models`、`/v1/chat/completions`、`/v1/embeddings`、`/v1/rerank`。

模型目录的线格式参考 Magpie 的 `/v1/models` 实践，包括
`context_window`、`max_output_tokens`、`reasoning_levels`、
`supported_parameters`、OpenRouter 风格 `pricing` 与 `native_endpoints`。
本项目不包含 Magpie、OpenCode Zen、Kilo Gateway 或任何业务项目代码。

## 模块

| 模块 | 用途 |
| --- | --- |
| `ai-model-bridge-core` | 契约、协议适配器、模型发现、能力探针、安全与可观测性；零 Spring 依赖 |
| `ai-model-bridge-spring-boot-starter` | `@ConfigurationProperties("ai.bridge")`、环回白名单守卫和 `@ConditionalOnMissingBean` 自动装配 |
| `ai-model-bridge-server` | 可选 OpenAI 兼容网关，多上游/别名路由、虚拟网关鉴权、Micrometer 指标 |

要求：

- Java 21+
- Maven 3.9+
- 消费端 Spring Boot 3.4+ 或 4.x（Starter 只使用稳定自动装配 API）

## JitPack

先在 Maven 或 Gradle 中加入 JitPack：

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>
```

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

多模块 JitPack 坐标：

```xml
<dependency>
    <groupId>com.github.BangShou1st.ai-model-bridge</groupId>
    <artifactId>ai-model-bridge-core</artifactId>
    <version>v0.1.0</version>
</dependency>
```

```groovy
implementation 'com.github.BangShou1st.ai-model-bridge:ai-model-bridge-core:v0.1.0'
implementation 'com.github.BangShou1st.ai-model-bridge:ai-model-bridge-spring-boot-starter:v0.1.0'
```

JitPack 构建日志：
`https://jitpack.io/com/github/BangShou1st/ai-model-bridge/v0.1.0/build.log`

## 直接使用 Core

```java
ObjectMapper mapper = new ObjectMapper();
OutboundEndpointPolicy policy = OutboundEndpointPolicy.httpsOnly();
ModelBridgeClient client = ModelBridgeClient.create(mapper, policy);

ProviderConfig provider = ProviderConfig.openAiCompatible(
        "upstream", "https://api.example.com/v1", System.getenv("MODEL_API_KEY"));

ChatResponse response = client.complete(provider, ChatRequest.builder("model-id")
        .user("用一句话解释模型网关。")
        .temperature(0.2)
        .maxOutputTokens(512)
        .build());
```

流式：

```java
client.completeStream(provider, request, chunk -> {
    if (chunk.type() == StreamChunk.Type.CONTENT) {
        System.out.print(chunk.delta());
    }
});
```

原生工具调用：

```java
ToolDefinition tool = new ToolDefinition(
        "lookup_order",
        "查询订单状态",
        mapper.readTree("""
                {"type":"object","properties":{"orderId":{"type":"string"}},
                 "required":["orderId"]}
                """));

TurnRequest turn = new TurnRequest(
        "model-id",
        List.of(Message.user("查询订单 A-1001")),
        List.of(tool),
        ToolChoice.REQUIRED,
        0.0,
        512,
        ReasoningOptions.none(),
        Map.of(),
        "request-1");

TurnResult result = client.turn(provider, turn);
```

结构化输出会在适配器支持的条件下使用原生 `response_format` / `responseMimeType`；
OpenAI-compatible 适配器在 `400`、`404`、`422` 或 JSON/schema 校验失败时，
可对 `AUTO` 请求最多降级一次 `PROMPT_JSON`。

## Spring Boot Starter

```xml
<dependency>
    <groupId>com.github.BangShou1st.ai-model-bridge</groupId>
    <artifactId>ai-model-bridge-spring-boot-starter</artifactId>
    <version>v0.1.0</version>
</dependency>
```

自动装配的 Bean：

- `OutboundEndpointPolicy`
- `HttpTransport` 与 `AdapterRegistry`
- `ModelBridgeClient`
- `ModelDiscoveryClient`
- `CapabilityProber`

全部使用 `@ConditionalOnMissingBean`，应用可以替换任意一个 Bean。

```yaml
ai:
  bridge:
    enabled: true
    connect-timeout: 10s
    request-timeout: 2m
    security:
      allow-loopback: true   # 仅 local/test profile 允许
      loopback-hosts:
        - 127.0.0.1
        - ::1
        - localhost
      loopback-ports:
        - 3425
```

安全守卫：

- 默认拒绝所有明文 loopback 地址。
- `allow-loopback=true` 时必须有非空 host 和 port 白名单。
- 只有激活 `local` 或 `test` profile 时才能开启；其他环境启动直接失败。
- 使用自定义 `OutboundEndpointPolicy` 也仍然执行启动守卫。

## 可选网关

Server 读取 `ai.bridge.gateway.*`：

```yaml
ai:
  bridge:
    security:
      allow-loopback: true
      loopback-hosts: [127.0.0.1, localhost]
      loopback-ports: [3425, 11434]
    gateway:
      auth-enabled: true
      api-keys:
        - ${AI_BRIDGE_GATEWAY_API_KEY}
      aliases:
        fast: magpie/workbuddy/hy4free
      upstreams:
        magpie:
          type: openai-compatible
          base-url: http://127.0.0.1:3425/v1
          api-key: ${MAGPIE_API_KEY:}
          models:
            - id: workbuddy/hy4free
              alias: hy4
              display-name: Hy4
              context-window: 960000
              max-input-tokens: 960000
              max-output-tokens: 64000
              supports-tools: true
              supports-reasoning: true
              reasoning-levels: [high]
              supported-parameters: [tools, tool_choice, reasoning]
              pricing:
                prompt: 0
                completion: 0
              native-endpoints:
                - /v1/chat/completions
        ollama:
          type: ollama
          base-url: http://127.0.0.1:11434
          models:
            - id: nomic-embed-text
              alias: embeddings
        local-rerank:
          type: llama-cpp
          base-url: http://127.0.0.1:8080
          models:
            - id: bge-reranker-v2-m3
              alias: rerank
```

启动：

```powershell
$env:AI_BRIDGE_GATEWAY_API_KEY = "dev-secret"
java -jar ai-model-bridge-server/target/ai-model-bridge-server-0.1.0.jar --spring.profiles.active=local
```

端点：

```text
GET  /v1/models
POST /v1/chat/completions     # stream=true 时返回 SSE
POST /v1/embeddings
POST /v1/rerank
GET  /actuator/health
GET  /actuator/metrics
```

调用示例：

```bash
curl http://127.0.0.1:8080/v1/models \
  -H 'Authorization: Bearer dev-secret'

curl http://127.0.0.1:8080/v1/chat/completions \
  -H 'Authorization: Bearer dev-secret' \
  -H 'Content-Type: application/json' \
  -d '{"model":"fast","stream":true,"messages":[{"role":"user","content":"hello"}]}'
```

网关特性：

- 请求模型名可以是显式别名、全局别名或唯一模型 ID；发往上游时替换为真实模型 ID。
- 流式响应输出 OpenAI `chat.completion.chunk`，支持 content、reasoning_content、tool_calls 分片和最终 usage。
- `top_n` 只影响最终返回条数；发送给 llama.cpp 的上游 `top_n` 始终等于候选总数，并校验所有索引完整且唯一。
- 虚拟网关使用 Bearer token 鉴权，认证失败不会访问上游。
- Micrometer 指标：`ai.bridge.gateway.latency`（endpoint/provider/outcome）与
  `ai.bridge.gateway.errors`（endpoint/provider/category）。

## 安全与错误模型

`OutboundEndpointPolicy`：

- 公网只允许 HTTPS；禁止 URL userinfo、fragment 和重定向。
- DNS 解析结果整体校验，任何私网、链路本地、CGNAT、云元数据或 mixed public/private
  结果都会被拒绝，以阻断 DNS rebinding 形态。
- 环回 HTTP 仅允许显式 host/port 白名单，且 Starter 额外限制为 `local` / `test`。
- 返回 `PinnedEndpoint`，包含本次解析后的完整地址集合与选定连接地址。

`ModelBridgeException` 统一分类：

- `AUTHENTICATION` / `AUTHORIZATION`
- `RATE_LIMIT` / `UNAVAILABLE` / `TIMEOUT`
- `LENGTH` / `SCHEMA` / `INVALID_RESPONSE`
- `NETWORK_POLICY` / `UNSUPPORTED`

`retryable` 随分类提供；`LENGTH` 异常携带已产生的 `TokenUsage`。
日志只记录模型名、消息数量、字符数、错误分类和 HTTP 状态，不打印 API Key 或 Prompt 正文。

## 构建与测试

```bash
./mvnw -B -ntp verify
```

测试使用真实本地 JDK `HttpServer`，覆盖：

- 三个协议的请求转换、原生工具调用和 SSE 分片
- structured output 降级、参数白名单、LENGTH usage
- 429 / 504 分类
- Embedding 完整性、Rerank 全量候选校验
- 标准模型列表、Magpie 丰富元数据、Anthropic/Gemini 分页
- SSRF / DNS rebinding、日志脱敏
- Starter 自动装配与环回守卫
- Server 鉴权、别名路由、SSE、tools、embeddings、rerank、指标和端到端错误分类

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
