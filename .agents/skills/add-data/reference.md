# Canonical core implementations

Drop-in reference code for the shared types every skill/agent references. Source: Philipp Lackner's house-style skills pack (matches CryptoTracker/Runique). Create these ONCE in `core/` when the project doesn't have them; reuse verbatim otherwise.

## `core/domain/util` — Result wrapper + typed errors

```kotlin
interface Error

typealias RootError = Error // Result.Error shadows Error inside Result; the alias avoids a package-specific FQN

sealed interface Result<out D, out E : RootError> {
    data class Success<out D>(val data: D) : Result<D, Nothing>
    data class Error<out E : RootError>(val error: E) : Result<Nothing, E>
}

typealias EmptyResult<E> = Result<Unit, E>

inline fun <T, E : Error, R> Result<T, E>.map(map: (T) -> R): Result<R, E> = when (this) {
    is Result.Error -> Result.Error(error)
    is Result.Success -> Result.Success(map(data))
}

inline fun <T, E : Error> Result<T, E>.onSuccess(action: (T) -> Unit): Result<T, E> = also {
    if (this is Result.Success) action(data)
}

inline fun <T, E : Error> Result<T, E>.onFailure(action: (E) -> Unit): Result<T, E> = also {
    if (this is Result.Error) action(error)
}

fun <T, E : Error> Result<T, E>.asEmptyResult(): EmptyResult<E> = map { }

sealed interface DataError : Error {
    enum class Network : DataError {
        BAD_REQUEST, REQUEST_TIMEOUT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT,
        TOO_MANY_REQUESTS, NO_INTERNET, PAYLOAD_TOO_LARGE, SERVER_ERROR,
        SERVICE_UNAVAILABLE, SERIALIZATION, UNKNOWN
    }
    enum class Local : DataError { DISK_FULL, NOT_FOUND, UNKNOWN }
}
```

Feature-specific errors implement `Error` directly (`enum class PasswordValidationError : Error { TOO_SHORT, ... }`) and return `EmptyResult<PasswordValidationError>` — always ONE error per Result, never a list.

## `core/data/networking` — Ktor client + safeCall

Matches the real code in `core/data/src/main/java/com/penthouse/myapplication/core/data/networking/`.

```kotlin
// KMP-clean: engine AND baseUrl come from the platform side (Koin wiring / BuildConfig on
// Android, Darwin engine + its own config on iOS). Nothing in here is Android-specific.
object HttpClientFactory {
    fun create(engine: HttpClientEngine, baseUrl: String): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Auth) { // not yet in the repo's factory — wire this block in when the app gets auth
            bearer {
                loadTokens { /* from DataStore */ }
                refreshTokens { /* refresh endpoint, persist new tokens */ }
            }
        }
        install(Logging) { level = LogLevel.INFO }
        defaultRequest {
            url(baseUrl) // relative routes resolve against this; absolute URLs still win
            contentType(ContentType.Application.Json)
        }
    }
}

suspend inline fun <reified T> safeCall(execute: () -> HttpResponse): Result<T, DataError.Network> {
    val response = try {
        execute()
    } catch (e: CancellationException) {
        throw e // never swallow cancellation
    } catch (e: UnresolvedAddressException) {
        return Result.Error(DataError.Network.NO_INTERNET)
    } catch (e: IOException) {
        // Ktor 3.x surfaces connect/socket failures as IOException (kotlinx.io.IOException
        // is a typealias of java.io.IOException on JVM), not UnresolvedAddressException
        return Result.Error(DataError.Network.NO_INTERNET)
    } catch (e: SerializationException) {
        return Result.Error(DataError.Network.SERIALIZATION)
    } catch (e: Exception) {
        return Result.Error(DataError.Network.UNKNOWN)
    }
    return responseToResult(response)
}

suspend inline fun <reified T> responseToResult(response: HttpResponse): Result<T, DataError.Network> {
    return when (response.status.value) {
        in 200..299 -> try {
            Result.Success(response.body<T>())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.Error(DataError.Network.SERIALIZATION) // any unconvertible 2xx body is malformed data
        }
        400 -> Result.Error(DataError.Network.BAD_REQUEST)
        401 -> Result.Error(DataError.Network.UNAUTHORIZED)
        403 -> Result.Error(DataError.Network.FORBIDDEN)
        404 -> Result.Error(DataError.Network.NOT_FOUND)
        408 -> Result.Error(DataError.Network.REQUEST_TIMEOUT)
        409 -> Result.Error(DataError.Network.CONFLICT)
        413 -> Result.Error(DataError.Network.PAYLOAD_TOO_LARGE)
        429 -> Result.Error(DataError.Network.TOO_MANY_REQUESTS)
        503 -> Result.Error(DataError.Network.SERVICE_UNAVAILABLE)
        in 500..599 -> Result.Error(DataError.Network.SERVER_ERROR)
        else -> Result.Error(DataError.Network.UNKNOWN)
    }
}

// Typed wrappers so data sources read like: httpClient.get(route = "/notes").
// No constructRoute helper: routes are passed straight to url() and resolve
// against the factory's defaultRequest { url(baseUrl) }.
suspend inline fun <reified Response : Any> HttpClient.get(
    route: String,
    queryParameters: Map<String, Any?> = mapOf(),
): Result<Response, DataError.Network> = safeCall {
    get {
        url(route)
        queryParameters.forEach { (key, value) -> parameter(key, value) }
    }
}

suspend inline fun <reified Request, reified Response : Any> HttpClient.post(
    route: String,
    body: Request,
): Result<Response, DataError.Network> = safeCall {
    post {
        url(route)
        setBody(body)
    }
}

suspend inline fun <reified Response : Any> HttpClient.delete(
    route: String,
    queryParameters: Map<String, Any?> = mapOf(),
): Result<Response, DataError.Network> = safeCall {
    delete {
        url(route)
        queryParameters.forEach { (key, value) -> parameter(key, value) }
    }
}
```

## `core/presentation/util` — UiText + ObserveAsEvents

```kotlin
sealed interface UiText {
    data class DynamicString(val value: String) : UiText
    class StringResource(val id: Int, val args: Array<Any> = emptyArray()) : UiText

    @Composable
    fun asString(): String = when (this) {
        is DynamicString -> value
        is StringResource -> stringResource(id, *args)
    }
}
```

Rule: `UiText` for anything that is (or could be) a string resource — error messages, labels. Plain `String` for always-dynamic values (user name, formatted date). Every user-facing error type gets a `.toUiText()` extension — feature-specific ones in the feature's presentation, shared `DataError.toUiText()` in `core/presentation` (exhaustive `when` over user-facing cases, fallback `R.string.error_unknown`).

```kotlin
@Composable
fun <T> ObserveAsEvents(
    flow: Flow<T>,
    key1: Any? = null,
    key2: Any? = null,
    onEvent: (T) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current // androidx.lifecycle.compose — the compose.ui.platform one is deprecated
    LaunchedEffect(lifecycleOwner.lifecycle, key1, key2) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                flow.collect(onEvent)
            }
        }
    }
}
```

`ObserveAsEvents(viewModel.events) { event -> ... }` in Root composables replaces hand-rolled `LaunchedEffect` event collection — `Main.immediate` ensures navigation events aren't dropped mid-transition.
