# Contract Visualizer

MVI Contract 파일을 파싱하여 State / Event / Effect 구조를 다이어그램으로 시각화하는 IntelliJ / Android Studio 플러그인.

`*Contract.kt` 파일을 열면 우측 Tool Window에 다이어그램이 자동 표시된다.

## 기능

### 다이어그램 렌더링
- **State 박스** (파란색) — 프로퍼티, derived 프로퍼티 (`derived` 뱃지), companion 상수 (`const` 뱃지)
- **Event 박스** (주황색) — 각 이벤트 variant + 파라미터
- **Effect 박스** (초록색) — 각 사이드이펙트 variant + 파라미터
- Sealed interface state의 경우 Loading / Success / Error 등 subtype을 variant로 표시

### 연결선
- `@EmitsEffect` 어노테이션 기반: Event → Effect 개별 행으로 정확히 연결
- `@MutatesState` 어노테이션 기반: Event → State 필드 행으로 정확히 연결
- 어노테이션 없는 이벤트: 이름 매칭 휴리스틱으로 fallback
- 기본 상태에서 연한 실선, 호버 시 강조

### 호버 인터랙션
- Event 행 위에 마우스를 올리면:
  - 연결된 State 필드 행에 파란색 하이라이트 + 좌측 액센트 바
  - 연결된 Effect 행에 주황색 하이라이트 + 좌측 액센트 바
  - 해당 연결선만 굵은 선으로 강조

### 레이아웃 (툴바 버튼으로 순환)
- **Horizontal** — `[State] [Event] [Effect]` 가로 배치
- **Vertical** — 세로 배치
- **Flow** — 패널 너비에 맞춰 자동 줄바꿈 (FlowRow 방식)

### 기타
- Cmd + 스크롤: 확대/축소 (0.3x ~ 3.0x, 마우스 위치 기준)
- 드래그: 패닝
- 스크롤: 시점 이동
- Copy Mermaid: Mermaid 코드를 클립보드에 복사
- IDE 다크/라이트 테마 자동 대응

## 구현 원리

### 1. 파싱 — Kotlin PSI

IntelliJ의 Kotlin PSI(Program Structure Interface)를 사용하여 `*Contract.kt` 파일의 AST를 탐색한다. regex가 아닌 의미론적 파싱이므로 포매팅 변화에 강건하다.

```
KtFile
  └─ declarations (KtClass 목록)
      ├─ 이름이 *State / *UiState → StateDefinition으로 분류
      │   ├─ data class → DataClassState (constructor params, derived props, companion consts)
      │   └─ sealed interface → SealedState (variants)
      ├─ 이름이 *Event → Event variants 추출
      ├─ 이름이 *Effect / *SideEffect → Effect variants 추출
      └─ 나머지 → SupportingType (enum, sealed class 등)
```

각 variant의 `annotationEntries`에서 `@EmitsEffect`, `@MutatesState`를 추출하여 매핑 정보를 수집한다.

**핵심 파일:** `PsiContractParser.kt`

### 2. 모델

```kotlin
ContractInfo
  ├─ featureName: String          // "SignIn", "Home" 등
  ├─ state: StateDefinition?      // DataClassState | SealedState
  ├─ events: List<VariantInfo>    // 각 variant에 emitsEffects, mutatesFields 포함
  ├─ effects: List<VariantInfo>
  └─ supportingTypes: List<SupportingType>
```

**핵심 파일:** `ContractModel.kt`

### 3. 렌더링 — Swing Graphics2D

JCEF(내장 브라우저) 대신 Swing `JPanel`의 `paintComponent`를 오버라이드하여 직접 렌더링한다. JCEF 가용성 문제를 우회하고, 호버/줌/패닝 등 인터랙션을 네이티브로 처리한다.

```
DiagramCanvas (JPanel)
  ├─ paintComponent: 박스 3개 + 연결선 렌더링
  ├─ drawGroup: 헤더 + 아이템 행 + 뱃지 + 하이라이트
  ├─ drawSmartConnection: 박스 간 상대 위치에 따라 좌우/상하 베지어 곡선 자동 선택
  └─ MouseAdapter: 드래그(패닝), 호버(하이라이트), Cmd+스크롤(줌)
```

**핵심 파일:** `MermaidRenderPanel.kt`

### 4. 어노테이션

Contract 파일의 Event variant에 어노테이션을 붙여 정확한 매핑을 선언한다. 어노테이션이 없으면 이름 매칭 fallback이 동작한다.

```kotlin
@EmitsEffect("NavigateToHome", "ShowDialog")
data object OnSignIn : SignInEvent

@MutatesState("phoneNumber")
data class OnPhoneNumberChange(val phoneNumber: String) : SignInEvent
```

어노테이션 정의는 `contract-parser` 모듈(ContractTest 프로젝트)에 있다.

### 5. Mermaid 코드 생성

Copy Mermaid 버튼으로 `flowchart LR` 형식의 Mermaid 코드를 복사할 수 있다. `@MutatesState`가 있으면 라벨 포함 실선, 없으면 점선.

**핵심 파일:** `MermaidGenerator.kt`

### 6. 파일 전환 감지

`FileEditorManagerListener`를 구현하여 에디터에서 `*Contract.kt` 파일이 활성화되면 자동으로 파싱 → 렌더링한다. Tool Window가 닫혀있으면 자동으로 열어준다.

**핵심 파일:** `ContractFileEditorListener.kt`

## 프로젝트 구조

```
src/main/
  kotlin/org/bmsk/contractvisualizer/
    model/
      ContractModel.kt           # ContractInfo, StateDefinition, VariantInfo 등 데이터 모델
    parser/
      PsiContractParser.kt       # Kotlin PSI 기반 Contract 파서
    mermaid/
      MermaidGenerator.kt        # ContractInfo → Mermaid flowchart 문자열 생성
    toolwindow/
      ContractVisualizerToolWindowFactory.kt  # Tool Window 생성 및 등록
      ContractVisualizerPanel.kt              # 툴바(레이아웃 토글, Copy Mermaid) + 렌더 패널
      MermaidRenderPanel.kt                   # Swing 기반 다이어그램 렌더러 (핵심)
    listener/
      ContractFileEditorListener.kt           # *Contract.kt 파일 활성화 감지
    util/
      MermaidHtmlTemplate.kt                  # (미사용, JCEF 시도 흔적)
  resources/
    META-INF/plugin.xml          # 플러그인 메타데이터, Tool Window/Listener 등록
    mermaid.min.js               # (미사용, JCEF 시도 흔적)
```

## 빌드

```bash
./gradlew buildPlugin
```

결과물: `build/distributions/ContractVisualizer-<version>.zip`

## 설치

1. [GitHub Releases](https://github.com/YiBeomSeok/ContractVisualizer/releases)에서 최신 ZIP 다운로드 (또는 위 명령으로 로컬 빌드)
2. Android Studio → Settings → Plugins → 톱니바퀴 → Install Plugin from Disk
3. ZIP 선택 후 IDE 재시작
4. `*Contract.kt` 파일 열기 → 우측 "Contract Visualizer" Tool Window 확인

## 릴리스 프로세스

버전 관리는 [SemVer](https://semver.org/)를 따르고, 변경 이력은 [Keep a Changelog](https://keepachangelog.com/) 형식의 `CHANGELOG.md`에 기록한다.

1. `CHANGELOG.md`의 `[Unreleased]` 섹션에 변경사항 추가
2. `gradle.properties`의 `pluginVersion` 업데이트 (예: `1.0.0` → `1.0.1`)
3. `./gradlew patchChangelog` 실행 — `[Unreleased]` 항목이 새 버전 섹션으로 이동
4. 커밋 후 태그 푸쉬:
   ```bash
   git commit -am "chore: release v1.0.1"
   git tag v1.0.1
   git push origin main --tags
   ```
5. GitHub Actions가 자동으로 빌드 후 ZIP을 첨부한 GitHub Release를 생성

## 개발 환경에서 테스트

```bash
./gradlew runIde
```

샌드박스 IDE가 열리면 Contract 파일을 열어 동작을 확인할 수 있다.

## 빌드 설정

- IntelliJ Platform Gradle Plugin 2.5.0
- Kotlin 2.0.21
- Target: IntelliJ IDEA Community 2024.3
- Kotlin K2 모드 호환 (`supportsK2="true"`)
- `sinceBuild`: 243, `untilBuild`: 261.*
