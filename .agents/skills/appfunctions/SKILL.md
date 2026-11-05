---
name: appfunctions
description: Expose app workflows as AppFunctions (Android 16+ on-device agent actions) - discovery of candidate functions, AppFunctionServiceEntryPoint implementation, KDoc optimization for agent discoverability, and ADB invocation testing. Use when the user wants their app's actions callable by AI agents/assistant/voice, or mentions AppFunctions.
---

Adapted from Google's official appfunctions skill. AppFunctions let AI agents execute app workflows (create a note, send a message) on-device WITHOUT opening the UI. **Requirements**: `targetSdk 36+`, `compileSdk 37+`, KSP, and `androidx.appfunctions` **1.0.0-alpha10+** (1.0.0-beta01 as of 2026-10 — still pre-stable; flag that to the user, the API surface can shift). Always the Jetpack library, never raw platform APIs.

```kotlin
implementation(libs.androidx.appfunctions)      // single artifact since alpha10 (appfunctions-service is dead, ends at alpha09)
ksp(libs.androidx.appfunctions.compiler)
```

## The four steps (do all four — a function without refined KDoc is undiscoverable)

### 1. Discovery
Scan manifest/screens for atomic user outcomes. Prioritize **mutations** (create/send/update) and **rich queries** (resolve an entity to an ID that other functions need — e.g. `searchContacts(query)` returning `endpointValue` as the prerequisite for `send(endpointValue, body)`). Skip purely informational or system-redundant functions. NEVER expose credentials/financial secrets or irreversible destructive actions without an explicit confirmation step.

### 2. Implementation (`@AppFunctionServiceEntryPoint`, alpha10+ pattern)

```kotlin
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "TaskAppFunctionService",
    appFunctionXmlFileName = "task_app_function_service",   // NO .xml — KSP appends it ("foo.xml" → foo.xml.xml)
)
abstract class BaseTaskAppFunctionService : AppFunctionService(), KoinComponent {
    private val taskRepository: TaskRepository by inject()   // Koin; services can't constructor-inject

    /**
     * Creates a task based on [createTaskParams].
     * @param createTaskParams The parameter to describe how to create the task.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun createTask(createTaskParams: CreateTaskParams): Task = withContext(Dispatchers.IO) {
        if (createTaskParams.title == null && createTaskParams.content == null)
            throw AppFunctionInvalidArgumentException("Title or content should be non-null")
        taskRepository.createTask(...) // call EXISTING repositories/use cases — no extra abstraction layer
    }
}
```

Rules: `suspend` + `withContext(Dispatchers.IO)`; specific parameters with defaults for non-essentials; supported types only (primitives + arrays, `String`/`Uri`/`PendingIntent`/`LocalDateTime`/`Instant`, `@AppFunctionSerializable` classes, `List` of non-primitives); errors as `AppFunctionException` subclasses (`AppFunctionInvalidArgumentException`, `AppFunctionElementNotFoundException`) so agents learn WHY. The service IS a `Context` — no `AppFunctionContext` param (that's the dead ≤alpha09 pattern; migrating off it: drop `appfunctions-service` dep, delete `AppFunctionConfiguration.Provider` from the Application, move `@AppFunction`s into the service class).

Manifest: `<service>` pointing at the KSP-**generated** concrete class, `android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"`, `exported="true"`, the `android.app.appfunctions.AppFunctionService` intent-filter, the schema + `v2` property tags (v2 value must match the generated asset filename EXACTLY), plus app-level `<property android:name="android.app.appfunctions.app_metadata" android:resource="@xml/app_metadata" />` — the `app_metadata.xml` `appfn:description` documents cross-function patterns and constraints for the LLM (no marketing, no per-function repetition).

### 3. KDoc refinement (run IMMEDIATELY after implementing)
This is the function's UI for agents:
- Start with an imperative verb + user outcome; descriptive not imperative ("This function provides...", never "You must...").
- Prerequisites via the standard phrase: `Required workflow: Call "searchContacts" first to obtain valid recipient IDs.`
- `@param` tags carry validation rules and defaults; rewrite `@throws` as recovery steps ("If X, suggest the user check their connection").
- **`@AppFunctionSerializable` classes: KSP only reads INLINE KDoc per property** — class-level `@param`/`@property` tags are silently ignored (the #1 cause of "metadata missing").

### 4. ADB testing
```
adb shell cmd app_function help                      # "Can't find service" = device doesn't support it
adb shell cmd app_function list-app-functions        # registered functions + descriptions (JSON)
adb shell cmd app_function execute-app-function --package com.pkg \
    --function 'com.pkg.TaskAppFunctionService#createTask' --parameters '{"title":"x"}'   # single-quote the JSON
```
Read the function's `description` from list output and follow it before invoking (prerequisite calls, disambiguation). "AppFunction unavailable"/metadata missing → check inline KDoc, the generated `assets/<name>.xml` inside the APK, the KSP compiler dep, and KSP arg `appfunctions:aggregateAppFunctions=true`.
