---
name: write-tests
description: Generate and run unit/UI tests for a ViewModel, repository, use case, DAO, or composable - Turbine for Flows, fakes over mocks, MainDispatcherRule, Compose test rule. Use when the user asks to write, add, or fix tests.
metadata:
  argument-hint: "<class-or-file-to-test>"
---

Write tests for the target in the arguments. Delegate to the `test-engineer` agent for full coverage work, or follow these steps directly for a single class.

## Steps

1. **Find the seams**: injected repository interfaces (reuse existing fakes in the test source set, or create one), injected dispatchers, exposed `StateFlow`/`Channel`.
2. **Confirm test deps** in `gradle/libs.versions.toml`: kotlinx-coroutines-test, Turbine, Truth (AssertK in KMP shared modules — never two assertion libraries in one module), MockK only if faking is impractical, compose-ui-test-junit4 for UI tests. Add what's missing.
3. **ViewModel tests** (JVM): `MainDispatcherRule` (`Dispatchers.setMain(StandardTestDispatcher())` in a TestWatcher), `runTest`, drive with `onAction(...)`, assert state via `viewModel.state.test { awaitItem() }` (Turbine — it also provides the collector that `stateIn(WhileSubscribed)` pipelines need), events via `viewModel.events.test { }`.
4. **Repository tests**: fake remote source + fake DAO (JVM) or `Room.inMemoryDatabaseBuilder` (androidTest, close in `@After`); assert offline-first behavior — cache served on network failure, refresh updates the DB.
5. **Compose tests**: `createComposeRule()`, `setContent { AppTheme { Screen(fakeState, onAction = ...) } }` — always the stateless screen with fake state, never the Root. Find via `onNodeWithText`/`onNodeWithTag`, assert `assertIsDisplayed`/`assertCountEquals`, act via `performClick`/`performTextInput`, verify the captured `Action`. Assert through the rule, never through captured variables alone.
6. **Names**: behavior sentences in backticks — `` `given X, when Y, then Z` ``.
7. **Run until green**: `.\gradlew.bat testDebugUnitTest` (and `connectedDebugAndroidTest` only if a device is attached). A red suite is not a deliverable — fix or report the diagnosis.
