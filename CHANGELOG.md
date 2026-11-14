# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/BangShou1st/ai-model-bridge/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/BangShou1st/ai-model-bridge/releases/tag/v0.1.0
