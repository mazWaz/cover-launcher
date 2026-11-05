---
name: add-data
description: Add Room caching and/or remote networking (Ktor/Retrofit) behind an offline-first repository for a feature - DTOs, entities, DAOs, mappers, safeCall error handling, and DI wiring. Use when the user asks to connect an API, add a database/cache, or implement a repository.
metadata:
  argument-hint: "<feature> [api|cache|both]"
---

Build the data layer for the feature named in the arguments. Delegate to the `data-layer-engineer` agent for implementation, or follow these steps directly for small additions.

## Steps

1. **Detect the stack**: Ktor + kotlinx.serialization vs Retrofit + Moshi, the existing `@Database` class, existing `safeCall`/`Result<D, DataError>` helpers, injected-dispatcher pattern. Reuse `core/` infrastructure — if it doesn't exist yet, create it there (not per-feature) from the canonical code in `reference.md` next to this skill (Result wrapper + extensions, DataError, safeCall/responseToResult, HttpClientFactory, UiText, ObserveAsEvents).
2. **Domain first**: plain model data classes (no annotations) and the repository interface in `domain/` — if `/new-feature` or the architect already created the interface, IMPLEMENT that exact signature, don't redefine it.
3. **Remote** (if needed): `@Serializable` DTOs matching the API, an api-service class on the shared client, every call wrapped in `safeCall` returning `Result<T, DataError.Network>` (map HTTP codes, `SerializationException`, `IOException` to typed errors; rethrow `CancellationException`).
4. **Local** (if caching): `@Entity` + `@Dao` with `Flow` queries and suspend `@Upsert` writes, registered in the `@Database` class (bump version / add migration), KSP processor confirmed in the build file.
5. **Mappers**: `Dto.toDomain()`, `Dto.toEntity()`, `Entity.toDomain()` extension functions in `data/mappers/`.
6. **Repository impl as SSOT**: expose a `Flow` reading the DAO; `refresh()` fetches remote and upserts into Room; network errors surface as typed `Result` while cache keeps flowing; all suspend functions main-safe (`withContext` at the blocking call; inject the dispatcher only if the class is directly unit-tested).
7. **Wire DI**: Koin `singleOf(::OfflineFirstXRepository) { bind<XRepository>() }` (classic lambda `single { }` only for the Room/Ktor builders), keep `KoinModulesTest.verify()` passing, and run `.\gradlew.bat assembleDebug`.
8. **Leave a fake**: a hand-written fake repository implementing the interface in the test source set, then suggest `/write-tests`.
