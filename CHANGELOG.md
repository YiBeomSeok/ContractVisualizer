# Contract Visualizer Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-04-17

### Added

- Initial release.
- Kotlin PSI 기반 `*Contract.kt` 파서 (`State` / `Event` / `Effect` 추출).
- Swing Graphics2D 기반 다이어그램 렌더러 (Horizontal / Vertical / Flow 레이아웃).
- `@EmitsEffect`, `@MutatesState` 어노테이션 기반 정확한 매핑 + 이름 매칭 fallback.
- 호버 인터랙션 (Event ↔ State 필드 / Effect 행 하이라이트).
- Cmd + 스크롤 줌, 드래그 패닝.
- Mermaid `flowchart LR` 코드 클립보드 복사.
- 파일 전환 자동 감지 및 Tool Window 자동 표시.

[Unreleased]: https://github.com/YiBeomSeok/ContractVisualizer/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/YiBeomSeok/ContractVisualizer/releases/tag/v1.0.0
