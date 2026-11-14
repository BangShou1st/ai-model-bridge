# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- README quick start now uses `provider/model` references for routable targets and keeps
  embedding, rerank and local-provider examples separate.
- SSE cancellation now interrupts the wait for response headers as well as closing an
  established response body.
- Model catalogs expose `provider/model` IDs for duplicate or alias-shadowed model IDs,
  while ambiguous bare IDs return explicit routing guidance.
- Qualified model references cannot be shadowed by conflicting global or model aliases;
  startup reports the alias, its targets and the affected model target.

## [0.2.0] - 2026-11-14

### Added

- Open `ProtocolId` SPI with `AdapterRegistry` support for chat, embedding, rerank and
  model discovery; custom `BridgePlugin` protocols no longer require a `ProviderType` change.
- Split OpenAI, Gemini and Ollama embedding adapters plus OpenAI, Anthropic, Gemini and
  Ollama model-discovery adapters.
- `ChatModel` / `EmbeddingModel` / `RerankModel`, `DefaultChatModel`, `DefaultEmbeddingModel`
  and `DefaultRerankModel`, with bound provider/model identity and per-call option merging.
- `ChatInterceptor` pipeline with `LoggingInterceptor`, `RetryInterceptor`,
  `StructuredOutputInterceptor` and `TruncationPolicyInterceptor`.
- `RetryPolicy` with `Retry-After` support, bounded exponential backoff and streaming retry
  only before the first content/reasoning/tool delta.
- `ContentPart` text/image/thinking blocks, multimodal message mapping and thinking-signature
  replay for Anthropic and Gemini.
- `ChatOptions` with sampling controls, `failOnLength`, `includeStreamUsage`,
  `strictJsonSchema`, safe `extraBody` and global/provider/model/request inheritance.
- `EndpointConfig` and `ProxyConfig`; typed path overrides, per-provider proxy support and
  HTTP basic proxy authentication.
- `StreamHandle` / `DefaultStreamHandle` and cancellable SSE transport.
- `NetworkPolicyMode` with `PUBLIC_HTTPS_ONLY`, `ALLOW_LOOPBACK` and
  `ALLOW_PRIVATE_NETWORK`; cloud metadata addresses remain blocked in every mode.
- Core `BridgeConfig`, provider/model definitions, `ModelRouter`, `DefaultModelRouter`,
  `ModelCatalogSnapshot`, alias fallback and optional discovery merging.
- Spring Boot YAML binding for providers, models, defaults, aliases, proxy, retry and security;
  auto-configured `ModelRouter` and conditional default model beans.
- Server now routes through `ModelRouter`, merges legacy `ai.bridge.gateway.upstreams.*`,
  binds client cancellation to upstream streams and exposes accurate
  `supports_embeddings` / `supports_rerank` / `native_endpoints`.

### Changed

- `TurnRequest` is deprecated and now bridges internally to `ChatRequest`; adapters no longer
  duplicate turn request/response parsing.
- `LENGTH` is a normal terminal finish reason at the protocol layer. Opt-in truncation failure
  is provided by `TruncationPolicyInterceptor` and carries the complete streamed usage.
- Structured-output validation and one `PROMPT_JSON` fallback are unified for OpenAI,
  Anthropic and Gemini instead of being hard-coded only in the OpenAI adapter.
- The gateway's embedding and rerank capability checks use the adapter registry rather than
  hard-coded provider enums.
- Legacy public APIs remain available through overloads or `@Deprecated` bridges, including
  `ProviderType`, `TurnRequest`, `ModelBridgeClient.turn` and
  `completeStream(..., Consumer)`.

### Fixed

- Version suffix detection for `/api/v3`, `/api/paas/v4` and similar base URLs.
- Gemini tool responses now backfill the function name from assistant `tool_call_id`.
- Gemini `thoughtSignature` is preserved across gateway tool-call round trips.
- Anthropic `reasoning` / `thinking` and `PROMPT_JSON` no longer send the wrong structured
  output field.
- OpenAI wire mapping preserves assistant text alongside tool calls and parses sampling
  controls such as `top_p`, `stop`, `seed` and penalties.
- Gemini embedding is no longer blocked by the gateway.
- SSE cancellation now closes the upstream response instead of waiting for another frame.

### Security

- Cloud metadata endpoints remain hard-blocked in all network policy modes.
- Production loopback still requires explicit host/port allowlists and
  `allow-loopback-in-production=true`.
- Logs and exceptions continue to exclude API keys and prompt text.

## [0.1.2] - 2026-11-14

### Added

- Native Gemini embedding support through `batchEmbedContents`, with 3072-dimension
  `gemini-embedding-001` verification.

## [0.1.1] - 2026-11-14

### Fixed

- Capability probes now use token budgets suitable for reasoning models, avoiding false
  `LENGTH` results when providers spend output tokens on hidden reasoning.
- Capability report JSON now exposes `duration_ms`, `error_category`, `http_status`,
  `started_at` and `supports_all`.

## [0.1.0] - 2026-11-14

### Added

- Spring-free `ai-model-bridge-core` with unified chat, native-tool, streaming,
  embedding, rerank and model-discovery contracts.
- OpenAI-compatible, Anthropic, Gemini, Ollama embedding and llama.cpp rerank adapters.
- Structured-output fallback, controlled reasoning parameters and LENGTH usage propagation.
- Four-stage capability prober: connectivity, streaming, structured output and native tools.
- SSRF boundary with HTTPS enforcement, private/metadata blocking and explicit loopback allowlists.
- Unified error taxonomy and prompt/credential-safe logging helpers.
- Spring Boot 3.4+/4.x-compatible starter with conditional bean auto-configuration.
- Optional OpenAI-compatible gateway with alias routing, bearer authentication,
  SSE/tools, embeddings, rerank and Micrometer metrics.
- GitHub Actions CI, Maven Wrapper and JitPack multi-module publishing configuration.
- Real local-HTTP test suites for core, starter and server modules.

### Security

- Public upstreams must use HTTPS and redirects are disabled.
- All DNS answers are validated as one set; mixed public/private answers are rejected.
- Loopback access requires both host and port allowlists and a `local` or `test` profile.
- Upstream response bodies, API keys and prompt text are never written to logs.

[Unreleased]: https://github.com/BangShou1st/ai-model-bridge/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/BangShou1st/ai-model-bridge/releases/tag/v0.2.0
[0.1.2]: https://github.com/BangShou1st/ai-model-bridge/releases/tag/v0.1.2
[0.1.1]: https://github.com/BangShou1st/ai-model-bridge/releases/tag/v0.1.1
[0.1.0]: https://github.com/BangShou1st/ai-model-bridge/releases/tag/v0.1.0
