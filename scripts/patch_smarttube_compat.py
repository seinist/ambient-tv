#!/usr/bin/env python3
"""Apply/check Ambient TV compatibility patches against live SmartTube sources.

The SmartTube repositories remain upstream checkouts. This script applies the
small source transformations required by the host-owned Gradle wrappers after
whatever upstream commit is currently checked out.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


def die(msg: str) -> None:
    raise SystemExit(f"ERROR: {msg}")


def manifest_files(msc: Path, shared: Path) -> list[Path]:
    return [
        msc / "youtubeapi/src/main/AndroidManifest.xml",
        msc / "mediaserviceinterfaces/src/main/AndroidManifest.xml",
        shared / "sharedutils/src/main/AndroidManifest.xml",
        shared / "commons-io-2.8.0/src/main/AndroidManifest.xml",
        shared / "j2v8/src/main/AndroidManifest.xml",
    ]


def patch_manifest(path: Path, check: bool) -> bool:
    if not path.is_file():
        die(f"SmartTube manifest moved or disappeared: {path}")
    text = path.read_text()
    patched, count = re.subn(r'(<manifest\b[^>]*?)\s+package="[^"]+"', r"\1", text, count=1)
    if check:
        if count:
            die(f"legacy manifest package attribute still present: {path}")
        return False
    if count:
        path.write_text(patched)
        return True
    return False


def patch_doh(path: Path, check: bool) -> bool:
    if not path.is_file():
        die(f"SmartTube OkHttp source moved or disappeared: {path}")
    text = path.read_text()
    required_import = "import okhttp3.HttpUrl.Companion.toHttpUrlOrNull"
    required_call = "s.toHttpUrlOrNull()"
    legacy_call = 'HttpUrl.parse(s) ?: throw NullPointerException("unable to parse url")'

    if check:
        if required_import not in text or required_call not in text:
            die(f"SmartTube OkHttp compatibility patch is not applied: {path}")
        if legacy_call in text:
            die(f"legacy HttpUrl.parse call remains after patching: {path}")
        return False

    changed = False
    if required_import not in text:
        anchor = "import okhttp3.HttpUrl\n"
        if anchor not in text:
            # Upstream may already have migrated away from HttpUrl.parse. If so,
            # only accept it when the replacement call is already present.
            if required_call not in text:
                die(f"cannot locate OkHttp import anchor in updated SmartTube source: {path}")
        else:
            text = text.replace(anchor, anchor + required_import + "\n", 1)
            changed = True

    if legacy_call in text:
        text = text.replace(
            legacy_call,
            's.toHttpUrlOrNull() ?: throw NullPointerException("unable to parse url")',
            1,
        )
        changed = True
    elif required_call not in text:
        die(f"cannot locate legacy or patched URL parser in updated SmartTube source: {path}")

    if changed:
        path.write_text(text)
    return changed



def patch_kotlin_text(path: Path, replacements: list[tuple[str, str]], check: bool) -> bool:
    """Apply narrowly-scoped Kotlin compatibility replacements if the file exists.

    Missing files are accepted because SmartTube upstream may have moved or removed
    the code in a newer revision. In check mode we only reject legacy spellings that
    are still present in files that exist.
    """
    if not path.is_file():
        return False
    text = path.read_text()
    if check:
        legacy = [old for old, _new in replacements if old in text]
        if legacy:
            die(f"legacy OkHttp Kotlin accessor remains in {path}: {legacy[0]}")
        return False

    changed = False
    for old, new in replacements:
        if old in text:
            text = text.replace(old, new)
            changed = True
    if changed:
        path.write_text(text)
    return changed


def _ensure_import(text: str, import_line: str, path: Path) -> tuple[str, bool]:
    if import_line in text:
        return text, False
    m = re.search(r"^import okhttp3\.[^\n]+$", text, re.MULTILINE)
    if not m:
        die(f"cannot locate OkHttp import anchor in updated SmartTube source: {path}")
    return text[: m.end()] + "\n" + import_line + text[m.end() :], True


def patch_drive_media_type(path: Path, check: bool) -> bool:
    """Migrate deprecated MediaType.parse(...) Kotlin calls to companion extensions."""
    if not path.is_file():
        return False
    text = path.read_text()
    legacy = "MediaType.parse(FILE_MIME_TYPE)"
    replacement = "FILE_MIME_TYPE.toMediaTypeOrNull()"
    required_import = "import okhttp3.MediaType.Companion.toMediaTypeOrNull"

    if check:
        if legacy in text:
            die(f"legacy MediaType.parse call remains in {path}")
        # If the replacement is present, its extension import must be present too.
        if replacement in text and required_import not in text:
            die(f"MediaType compatibility import is missing in {path}")
        return False

    changed = False
    if legacy in text:
        text = text.replace(legacy, replacement)
        changed = True
    if replacement in text and required_import not in text:
        anchor = "import okhttp3.MediaType\n"
        if anchor in text:
            text = text.replace(anchor, anchor + required_import + "\n", 1)
            changed = True
        else:
            text, _inserted = _ensure_import(text, required_import, path)
            changed = True
    if changed:
        path.write_text(text)
    return changed


def patch_drive_request_body(path: Path, check: bool) -> bool:
    """Migrate deprecated RequestBody.create(type, payload) to OkHttp 4 extensions."""
    if not path.is_file():
        return False
    text = path.read_text()
    replacements = [
        (
            "uploadFile(RequestBody.create(FILE_MIME_TYPE.toMediaTypeOrNull(), file), path)",
            "uploadFile(file.asRequestBody(FILE_MIME_TYPE.toMediaTypeOrNull()), path)",
        ),
        (
            "uploadFile(RequestBody.create(FILE_MIME_TYPE.toMediaTypeOrNull(), content), path)",
            "uploadFile(content.toRequestBody(FILE_MIME_TYPE.toMediaTypeOrNull()), path)",
        ),
    ]
    required_imports = [
        "import okhttp3.RequestBody.Companion.asRequestBody",
        "import okhttp3.RequestBody.Companion.toRequestBody",
    ]

    if check:
        if "RequestBody.create(" in text:
            die(f"legacy RequestBody.create call remains in {path}")
        if any(old in text for old, _new in replacements):
            die(f"legacy RequestBody.create call remains in {path}")
        if "asRequestBody(" in text and required_imports[0] not in text:
            die(f"asRequestBody import is missing in {path}")
        if "toRequestBody(" in text and required_imports[1] not in text:
            die(f"toRequestBody import is missing in {path}")
        return False

    changed = False
    for old, new in replacements:
        if old in text:
            text = text.replace(old, new)
            changed = True
    for import_line, marker in (
        (required_imports[0], "asRequestBody("),
        (required_imports[1], "toRequestBody("),
    ):
        if marker in text and import_line not in text:
            anchor = "import okhttp3.RequestBody\n"
            if anchor in text:
                text = text.replace(anchor, anchor + import_line + "\n", 1)
                changed = True
            else:
                text, _inserted = _ensure_import(text, import_line, path)
                changed = True
    if changed:
        path.write_text(text)
    return changed


def patch_ytcfg_json_parser(path: Path, check: bool) -> bool:
    """Replace the deprecated Gson JsonParser() instance with parseString()."""
    if not path.is_file():
        return False
    text = path.read_text()
    legacy = "        val parser = JsonParser()\n        return parser.parse(ytCfgStr?.ytCfg).asJsonObject\n"
    replacement = "        val ytCfg = ytCfgStr?.ytCfg ?: return null\n        return JsonParser.parseString(ytCfg).asJsonObject\n"
    if check:
        if "JsonParser()" in text or "parser.parse(" in text:
            die(f"legacy Gson JsonParser instance remains in {path}")
        return False
    if legacy not in text:
        return False
    path.write_text(text.replace(legacy, replacement, 1))
    return True


def patch_cronet_experimental_import(path: Path, check: bool) -> bool:
    """Drop the unused ExperimentalCronetEngine import (the call sites are commented out)."""
    if not path.is_file():
        return False
    text = path.read_text()
    legacy = "import org.chromium.net.ExperimentalCronetEngine\n"
    if check:
        if legacy in text:
            die(f"unused ExperimentalCronetEngine import remains in {path}")
        return False
    if legacy not in text:
        return False
    path.write_text(text.replace(legacy, "", 1))
    return True


def patch_okhttp_manager_request_body(path: Path, check: bool) -> bool:
    """Swap RequestBody.create(mediaType, body) to the non-deprecated (body, mediaType) overload."""
    if not path.is_file():
        return False
    text = path.read_text()
    legacy = ".post(RequestBody.create(contentType != null ? MediaType.parse(contentType) : null, body))"
    replacement = ".post(RequestBody.create(body, contentType != null ? MediaType.parse(contentType) : null))"
    if check:
        if legacy in text:
            die(f"legacy RequestBody.create argument order remains in {path}")
        return False
    if legacy not in text:
        return False
    path.write_text(text.replace(legacy, replacement, 1))
    return True


def patch_v8_object_utils_double(path: Path, check: bool) -> bool:
    """Replace the deprecated Double(long) constructor in j2v8's V8ObjectUtils."""
    if not path.is_file():
        return False
    text = path.read_text()
    legacy = "result.push(new Double((Long) value));"
    replacement = "result.push(((Long) value).doubleValue());"
    if check:
        if legacy in text:
            die(f"deprecated Double constructor remains in {path}")
        return False
    if legacy not in text:
        return False
    path.write_text(text.replace(legacy, replacement, 1))
    return True


def patch_format_info_null_fallback(path: Path, check: bool) -> bool:
    """Innertube must run when the legacy player lookup returns null, not only when it is unplayable."""
    if not path.is_file():
        return False
    text = path.read_text()
    legacy = "if (formatInfo != null && formatInfo.isUnplayable())"
    replacement = "if (formatInfo == null || formatInfo.isUnplayable())"
    if check:
        if legacy in text:
            die(f"legacy format-info fallback remains in {path}")
        return False
    if legacy not in text:
        return False
    path.write_text(text.replace(legacy, replacement, 1))
    return True


def patch_video_url_holder_invalid_percent(path: Path, check: bool) -> bool:
    """Keep one malformed YouTube format URL from aborting all playback extraction.

    YouTube occasionally returns a literal percent sign in a format URL query. The
    shared URL parser delegates to java.net.URLDecoder, which rejects a percent not
    followed by two hex digits. Preserve valid escapes and encode only malformed
    percent signs before SmartTube parses or rewrites the query.
    """
    if not path.is_file():
        die(f"SmartTube VideoUrlHolder moved or disappeared: {path}")
    text = path.read_text()
    marker = "AMBIENT_TV_LENIENT_PERCENT_ESCAPE"

    if check:
        if marker not in text:
            die(f"SmartTube malformed-percent recovery patch is not applied: {path}")
        if "UrlQueryStringFactory.parse(cipherUri)" in text or "UrlQueryStringFactory.parse(url)" in text:
            die(f"unsafe SmartTube URL query parsing remains in: {path}")
        return False

    if marker in text:
        return False

    import_anchor = "import com.liskovsoft.sharedutils.querystringparser.UrlQueryStringFactory\n"
    helper = """
// AMBIENT_TV_LENIENT_PERCENT_ESCAPE: URLDecoder throws on a lone/truncated '%'.
// Keep valid percent escapes byte-for-byte and encode only malformed percent signs.
private val INVALID_PERCENT_ESCAPE = Regex("%(?![0-9A-Fa-f]{2})")
private fun String.withValidPercentEscapes(): String = replace(INVALID_PERCENT_ESCAPE, "%25")
"""
    if import_anchor not in text:
        die(f"cannot locate VideoUrlHolder import anchor in updated SmartTube source: {path}")
    text = text.replace(import_anchor, import_anchor + helper, 1)

    replacements = {
        "UrlQueryStringFactory.parse(cipherUri)": "UrlQueryStringFactory.parse(cipherUri.withValidPercentEscapes())",
        "UrlQueryStringFactory.parse(url)": "UrlQueryStringFactory.parse(url!!.withValidPercentEscapes())",
    }
    for legacy, replacement in replacements.items():
        if legacy not in text:
            die(f"cannot locate SmartTube URL query parser in updated source: {path}")
        text = text.replace(legacy, replacement, 1)

    path.write_text(text)
    return True


def patch_okhttp_debug_dump(path: Path, check: bool) -> bool:
    """Debug builds must not dump YouTube HTTP traffic (OAuth tokens, cookies) to logcat."""
    if not path.is_file():
        return False
    text = path.read_text()
    legacy = "    private static void debugSetup(OkHttpClient.Builder okBuilder) {\n        if (BuildConfig.DEBUG) {"
    replacement = "    private static void debugSetup(OkHttpClient.Builder okBuilder) {\n        if (false) { // Ambient TV: no HTTP bodies/headers in logcat"
    if check:
        if legacy in text:
            die(f"HTTP debug dump to logcat is still enabled in {path}")
        return False
    if legacy not in text:
        return False
    path.write_text(text.replace(legacy, replacement, 1))
    return True


def patch_logger_quiet(path: Path, check: bool) -> bool:
    """Upstream debug/info logs print PoTokens, botguard responses, signed stream URLs and
    visitor data. Ambient TV keeps upstream warnings and errors only, in every build type."""
    if not path.is_file():
        die(f"SmartTube logger moved or disappeared: {path}")
    text = path.read_text()
    legacy = [
        "        sLogger.d(tag, formatMsg(msg, formatArgs));\n",
        "        sLogger.i(tag, formatMsg(msg, formatArgs));\n",
    ]
    marker = "        // Ambient TV: upstream debug/info output carries tokens and signed URLs.\n"
    if check:
        if any(line in text for line in legacy):
            die(f"SmartTube debug/info logging is still enabled in {path}")
        if text.count(marker) != 2:
            die(f"SmartTube logger patch marker missing in {path}")
        return False
    if not any(line in text for line in legacy):
        return False
    for line in legacy:
        text = text.replace(line, marker, 1)
    path.write_text(text)
    return True


def patch_youtubeapi_okhttp_kotlin(msc: Path, check: bool) -> list[Path]:
    """Patch Kotlin API migrations for the host OkHttp version."""
    base = msc / "youtubeapi/src/main/java"
    changed: list[Path] = []

    drive = base / "com/liskovsoft/googleapi/drive3/DriveServiceInt.kt"
    if patch_drive_media_type(drive, check):
        changed.append(drive)
    if patch_drive_request_body(drive, check):
        changed.append(drive)

    ytcfg = base / "com/liskovsoft/youtubeapi/innertube/ytcfg/YtCfgService.kt"
    if patch_ytcfg_json_parser(ytcfg, check):
        changed.append(ytcfg)

    patches: dict[str, list[tuple[str, str]]] = {
        "com/liskovsoft/googlecommon/common/helpers/RetrofitOkHttpHelper.kt": [
            (".headers()", ".headers"),
            (".url()", ".url"),
            (
                "newUrlBuilder?.addQueryParameter(entry.key, entry.value)",
                "newUrlBuilder.addQueryParameter(entry.key, entry.value)",
            ),
        ],
        "com/liskovsoft/youtubeapi/app/nsigsolver/common/InfoExtractor.kt": [
            (".body()", ".body"),
            ('return content ?: throw InfoExtractorError(formatError(errorMsg, "Empty content received for the $url"))',
             "return content"),
        ],
        "com/liskovsoft/youtubeapi/app/playerdata/TclPlayerDataExtractor.kt": [
            ("nResults?.let { results -> nParams?.map { results[it] } }",
             "nResults?.let { results -> nParams.map { results[it] } }"),
        ],
        "com/liskovsoft/youtubeapi/app/potokennp2/generators/PoTokenV8.kt": [
            (".code()", ".code"),
            (".body()", ".body"),
        ],
        "com/liskovsoft/youtubeapi/app/potokennp2/generators/PoTokenWebView.kt": [
            (".code()", ".code"),
            (".body()", ".body"),
        ],
        "com/liskovsoft/youtubeapi/app/potokennp2/generators/PoTokenWebView2.kt": [
            (".code()", ".code"),
            (".body()", ".body"),
        ],
        "com/liskovsoft/youtubeapi/app/potokennp2/generators/PoTokenWebView3.kt": [
            (".code()", ".code"),
            (".body()", ".body"),
        ],
        "com/liskovsoft/youtubeapi/app/potokennp2/generators/PoTokenWebView4.kt": [
            (".code()", ".code"),
            (".body()", ".body"),
        ],
        "com/liskovsoft/youtubeapi/app/potokennp2/generators/TvPoTokenWebView4.kt": [
            (".code()", ".code"),
            (".body()", ".body"),
        ],
        "com/liskovsoft/youtubeapi/app/potokennp2/misc/JavaScriptUtil.kt": [
            (".body()", ".body"),
            ('\n            ?: throw PoTokenException("Empty response body")', ""),
        ],
        "com/liskovsoft/youtubeapi/innertube/initialresponse/InitialResponseService.kt": [
            (".body()", ".body"),
        ],
    }
    for rel, replacements in patches.items():
        if (".body()", ".body") in replacements:
            # OkHttp Response.body is non-null in the host dependency.
            replacements.append((".body?.string()", ".body.string()"))
        if "/potokennp2/generators/" in rel:
            replacements.append((
                '        if (response.body == null) {\n'
                '            onInitializationErrorCloseAndCancel(PoTokenException("Response body is empty. Response code: $httpCode"))\n'
                '        }\n\n',
                "",
            ))
        path = base / rel
        if patch_kotlin_text(path, replacements, check):
            changed.append(path)
    return changed


def patch_youtube_auth_device_code_recovery(msc: Path, check: bool) -> list[Path]:
    """Make SmartTube device-code auth recover from stale/empty scraped TV OAuth data.

    MediaServiceCore normally scrapes the public YouTube TV OAuth client from the
    current TV web bundle. Fresh installs can temporarily fail when that bundle
    changes, causing signInObserve() to surface "User code result is empty".
    Keep the scraped credentials as the primary path, refresh their cache once,
    then fall back to the long-lived public TV device-flow client only if both
    dynamic attempts fail.
    """
    changed: list[Path] = []

    auth = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/auth/V2/AuthService.java"
    if not auth.is_file():
        die(f"SmartTube AuthService moved or disappeared: {auth}")
    text = auth.read_text()

    marker = "AMBIENT_TV_DEVICE_CODE_RECOVERY"
    if check:
        if marker not in text:
            die(f"SmartTube device-code recovery patch is not applied: {auth}")
    elif marker not in text:
        old_constants = """    private static final int REFRESH_TOKEN_ATTEMPTS = 200;\n    private static final long REFRESH_TOKEN_ATTEMPT_INTERVAL_MS = 3_000;\n    private final AppService mAppService;\n"""
        new_constants = """    private static final int REFRESH_TOKEN_ATTEMPTS = 200;\n    private static final long REFRESH_TOKEN_ATTEMPT_INTERVAL_MS = 3_000;\n    // AMBIENT_TV_DEVICE_CODE_RECOVERY: public YouTube TV device-flow fallback.\n    // The dynamic TV bundle scrape remains primary so upstream credential rotation still wins.\n    private static final String FALLBACK_TV_CLIENT_ID = \"861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com\";\n    private static final String FALLBACK_TV_CLIENT_SECRET = \"SboVhoG9s0rNafixCSGGKXAT\";\n    private final AppService mAppService;\n    private volatile boolean mUseFallbackTvCredentials;\n"""
        if old_constants not in text:
            die(f"cannot locate AuthService constant anchor in updated SmartTube source: {auth}")
        text = text.replace(old_constants, new_constants, 1)

        old_user_code = """    public UserCode getUserCode() {\n        Call<UserCode> wrapper = mAuthApi.getUserCode(\n                AuthApiHelper.getUserCodeQuery(mAppService.getClientId())\n        );\n        return RetrofitHelper.get(wrapper);\n    }\n"""
        new_user_code = """    public UserCode getUserCode() {\n        mUseFallbackTvCredentials = false;\n\n        UserCode result = requestUserCode(mAppService.getClientId());\n        if (result == null) {\n            // A persisted TV bundle/client tuple can become stale between releases. Refresh it\n            // once before falling back, so normal upstream credential discovery stays primary.\n            mAppService.invalidateCache();\n            result = requestUserCode(mAppService.getClientId());\n        }\n        if (result == null) {\n            result = requestUserCode(FALLBACK_TV_CLIENT_ID);\n            mUseFallbackTvCredentials = result != null;\n        }\n        return result;\n    }\n\n    private UserCode requestUserCode(String clientId) {\n        if (clientId == null || clientId.isEmpty()) {\n            return null;\n        }\n        Call<UserCode> wrapper = mAuthApi.getUserCode(\n                AuthApiHelper.getUserCodeQuery(clientId)\n        );\n        return RetrofitHelper.get(wrapper);\n    }\n\n    private String getAuthClientId() {\n        return mUseFallbackTvCredentials ? FALLBACK_TV_CLIENT_ID : mAppService.getClientId();\n    }\n\n    private String getAuthClientSecret() {\n        return mUseFallbackTvCredentials ? FALLBACK_TV_CLIENT_SECRET : mAppService.getClientSecret();\n    }\n"""
        if old_user_code not in text:
            die(f"cannot locate AuthService getUserCode implementation in updated SmartTube source: {auth}")
        text = text.replace(old_user_code, new_user_code, 1)

        text = text.replace(
            """                        mAppService.getClientId(),\n                        mAppService.getClientSecret())\n""",
            """                        getAuthClientId(),\n                        getAuthClientSecret())\n""",
            1,
        )

        old_update = """    public AccessToken updateAccessToken(String refreshToken) {\n        Call<AccessToken> wrapper = mAuthApi.updateAccessToken(\n                AuthApiHelper.getAccessTokenQuery(refreshToken,\n                        mAppService.getClientId(),\n                        mAppService.getClientSecret())\n        );\n        return RetrofitHelper.getWithErrors(wrapper);\n    }\n"""
        new_update = """    public AccessToken updateAccessToken(String refreshToken) {\n        AccessToken result = requestAccessTokenUpdate(\n                refreshToken, mAppService.getClientId(), mAppService.getClientSecret());\n        if (result == null || result.getError() != null) {\n            AccessToken fallback = requestAccessTokenUpdate(\n                    refreshToken, FALLBACK_TV_CLIENT_ID, FALLBACK_TV_CLIENT_SECRET);\n            if (fallback != null && fallback.getError() == null) {\n                mUseFallbackTvCredentials = true;\n                result = fallback;\n            }\n        }\n        return result;\n    }\n\n    private AccessToken requestAccessTokenUpdate(String refreshToken, String clientId, String clientSecret) {\n        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()) {\n            return null;\n        }\n        Call<AccessToken> wrapper = mAuthApi.updateAccessToken(\n                AuthApiHelper.getAccessTokenQuery(refreshToken, clientId, clientSecret)\n        );\n        return RetrofitHelper.getWithErrors(wrapper);\n    }\n"""
        if old_update not in text:
            die(f"cannot locate AuthService updateAccessToken implementation in updated SmartTube source: {auth}")
        text = text.replace(old_update, new_update, 1)
        auth.write_text(text)
        changed.append(auth)

    cache = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/app/AppServiceIntCached.java"
    if not cache.is_file():
        cache = cache.with_name("AppServiceCoreCached.java")
    if not cache.is_file():
        die(f"SmartTube cached AppService moved or disappeared: {cache}")
    cache_text = cache.read_text()
    cache_marker = "AMBIENT_TV_AUTH_CACHE_RECOVERY"
    if check:
        if cache_marker not in cache_text:
            die(f"SmartTube auth cache recovery patch is not applied: {cache}")
    elif cache_marker not in cache_text:
        old_invalidate = """    public void invalidateCache() {\n        mAppInfo = null;\n        // Don't reset Player's cache. It's too heavy to recreate often.\n        // Better do it inside MediaServiceData after the update\n    }\n"""
        new_invalidate = """    public void invalidateCache() {\n        mAppInfo = null;\n        // AMBIENT_TV_AUTH_CACHE_RECOVERY: client id/secret belong to the TV bundle too.\n        // Clearing only AppInfo can keep a stale OAuth tuple alive after a failed device-code request.\n        mClientData = null;\n        getData().setClientData(null);\n        // Don't reset Player's cache. It's too heavy to recreate often.\n        // Better do it inside MediaServiceData after the update\n    }\n"""
        if old_invalidate not in cache_text:
            die(f"cannot locate cached AppService.invalidateCache in updated SmartTube source: {cache}")
        cache.write_text(cache_text.replace(old_invalidate, new_invalidate, 1))
        changed.append(cache)

    return changed


def patch_youtube_auth_device_code_diagnostics(msc: Path, check: bool) -> list[Path]:
    """Report why each device-code attempt failed instead of only "User code result is empty".

    Runs after patch_youtube_auth_device_code_recovery(). Every attempt (scraped TV client,
    refreshed TV client, public fallback) records its HTTP status/OAuth error or network
    exception; YouTubeAccountManager appends that summary to the sign-in error.
    """
    changed: list[Path] = []
    marker = "AMBIENT_TV_DEVICE_CODE_DIAGNOSTICS"

    auth = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/auth/V2/AuthService.java"
    text = auth.read_text()
    if check:
        if marker not in text:
            die(f"SmartTube device-code diagnostics patch is not applied: {auth}")
    elif marker not in text:
        old_start = """        mUseFallbackTvCredentials = false;\n\n        UserCode result = requestUserCode(mAppService.getClientId());\n"""
        new_start = """        mUseFallbackTvCredentials = false;\n        clearUserCodeErrors();\n\n        UserCode result = requestUserCode(mAppService.getClientId());\n"""
        old_request = """    private UserCode requestUserCode(String clientId) {\n        if (clientId == null || clientId.isEmpty()) {\n            return null;\n        }\n        Call<UserCode> wrapper = mAuthApi.getUserCode(\n                AuthApiHelper.getUserCodeQuery(clientId)\n        );\n        return RetrofitHelper.get(wrapper);\n    }\n"""
        new_request = """    // AMBIENT_TV_DEVICE_CODE_DIAGNOSTICS: why each device-code attempt failed, shown with the
    // sign-in error instead of only "User code result is empty".
    private static final StringBuilder sUserCodeErrors = new StringBuilder();

    public static synchronized String getLastUserCodeError() {
        return sUserCodeErrors.length() == 0 ? "no attempt recorded" : sUserCodeErrors.toString();
    }

    private static synchronized void clearUserCodeErrors() {
        sUserCodeErrors.setLength(0);
    }

    private static synchronized void noteUserCodeError(String attempt, String message) {
        if (sUserCodeErrors.length() > 0) {
            sUserCodeErrors.append("; ");
        }
        sUserCodeErrors.append(attempt).append(": ").append(message);
    }

    private UserCode requestUserCode(String clientId) {
        String attempt = FALLBACK_TV_CLIENT_ID.equals(clientId) ? "fallback client" : "TV client";
        if (clientId == null || clientId.isEmpty()) {
            noteUserCodeError(attempt, "no client id (YouTube TV bundle could not be loaded)");
            return null;
        }
        Call<UserCode> wrapper = mAuthApi.getUserCode(
                AuthApiHelper.getUserCodeQuery(clientId)
        );
        try {
            retrofit2.Response<UserCode> response = wrapper.execute();
            UserCode body = response.body();
            if (response.isSuccessful() && body != null && body.getUserCode() != null) {
                return body;
            }
            String detail = "";
            if (response.errorBody() != null) {
                detail = response.errorBody().string().replaceAll("\\\\s+", " ").trim();
                if (detail.length() > 200) {
                    detail = detail.substring(0, 200) + "...";
                }
            } else if (response.isSuccessful()) {
                detail = "response without user code";
            }
            noteUserCodeError(attempt, ("HTTP " + response.code() + " " + detail).trim());
        } catch (Exception e) {
            noteUserCodeError(attempt, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }
"""
        for old, new, label in [(old_start, new_start, "getUserCode start"), (old_request, new_request, "requestUserCode")]:
            if old not in text:
                die(f"cannot locate patched AuthService {label} in SmartTube source: {auth}")
            text = text.replace(old, new, 1)
        auth.write_text(text)
        changed.append(auth)

    manager = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/service/internal/YouTubeAccountManager.java"
    if not manager.is_file():
        die(f"SmartTube YouTubeAccountManager moved or disappeared: {manager}")
    text = manager.read_text()
    if check:
        if marker not in text:
            die(f"SmartTube device-code diagnostics patch is not applied: {manager}")
    elif marker not in text:
        old_error = """                RxHelper.onError(emitter, "User code result is empty");\n"""
        new_error = """                // AMBIENT_TV_DEVICE_CODE_DIAGNOSTICS\n                RxHelper.onError(emitter, "User code result is empty (" + AuthService.getLastUserCodeError() + ")");\n"""
        if old_error not in text:
            die(f"cannot locate the user-code error in SmartTube source: {manager}")
        manager.write_text(text.replace(old_error, new_error, 1))
        changed.append(manager)

    return changed

def patch_url_encoded_query_invalid_percent(path: Path, check: bool) -> bool:
    """Keep a malformed percent in a query name or value from aborting login and feeds.

    java.net.URLDecoder.decode throws IllegalArgumentException when a component contains
    '%' without two hex digits. appendOrSet only catches UnsupportedEncodingException, so
    that exception escapes and YouTube login/feeds fail. Same rule as VideoUrlHolder:
    leave valid %XX unchanged and encode only a malformed percent as %25 before decode.
    This does not change decoding of well-formed tokens, client ids, or client secrets.
    """
    if not path.is_file():
        die(f"SmartTube UrlEncodedQueryStringBase moved or disappeared: {path}")
    text = path.read_text()
    marker = "AMBIENT_TV_LENIENT_PERCENT_ESCAPE"
    legacy_name = '\t\t\t\tname = URLDecoder.decode( name, "UTF-8" );\n'
    legacy_value = '\t\t\t\t\tvalue = URLDecoder.decode( value, "UTF-8" );\n'
    patched_name = '\t\t\t\tname = decodeQueryComponent( name );\n'
    patched_value = '\t\t\t\t\tvalue = decodeQueryComponent( value );\n'

    def still_raw() -> bool:
        return legacy_name in text or legacy_value in text

    if check:
        if marker not in text or "decodeQueryComponent" not in text or "IllegalArgumentException" not in text:
            die(f"SmartTube query percent-escape patch is not applied: {path}")
        if still_raw():
            die(f"unsafe URLDecoder.decode remains in query parser: {path}")
        return False

    if marker in text:
        if still_raw():
            die(f"SmartTube query percent-escape patch is only partially applied: {path}")
        return False

    import_anchor = "import java.util.StringTokenizer;\n"
    if import_anchor not in text:
        die(f"cannot locate UrlEncodedQueryStringBase import anchor in updated SmartTube source: {path}")
    text = text.replace(import_anchor, import_anchor + "import java.util.regex.Pattern;\n", 1)

    field_anchor = (
        "\tprivate static final String\t\t\t\tPARSE_PARAMETER_SEPARATORS\t= "
        "String.valueOf( Separator.AMPERSAND ) + Separator.SEMICOLON;\n"
    )
    helper = """
	// AMBIENT_TV_LENIENT_PERCENT_ESCAPE: URLDecoder throws IllegalArgumentException
	// on a lone or truncated '%'. Keep valid percent escapes and encode only malformed ones.
	private static final Pattern INVALID_PERCENT_ESCAPE = Pattern.compile( "%(?![0-9A-Fa-f]{2})" );

	private static String decodeQueryComponent( final String raw ) throws UnsupportedEncodingException {
		String safe = INVALID_PERCENT_ESCAPE.matcher( raw ).replaceAll( "%25" );
		try {
			return URLDecoder.decode( safe, "UTF-8" );
		} catch ( IllegalArgumentException ignored ) {
			// Sanitization above should already prevent this. Never abort login or feeds.
			return safe;
		}
	}

"""
    if field_anchor not in text:
        die(f"cannot locate UrlEncodedQueryStringBase separator anchor in updated SmartTube source: {path}")
    text = text.replace(field_anchor, field_anchor + helper, 1)

    if legacy_name not in text or legacy_value not in text:
        die(f"cannot locate URLDecoder.decode call sites in updated SmartTube source: {path}")
    text = text.replace(legacy_name, patched_name, 1)
    text = text.replace(legacy_value, patched_value, 1)
    path.write_text(text)
    return True


def patch_v8_wrapper_java_callback(path: Path, check: bool) -> bool:
    """Expose j2v8 JavaCallback so JS can receive values such as setInterval ids."""
    if not path.is_file():
        die(f"SmartTube V8Wrapper moved or disappeared: {path}")
    text = path.read_text()
    required_import = "import com.eclipsesource.v8.JavaCallback\n"
    required_method = "fun registerJavaMethod(callback: JavaCallback, jsFunctionName: String)"
    if check:
        if required_import not in text or required_method not in text:
            die(f"V8Wrapper JavaCallback overload is not applied: {path}")
        return False
    changed = False
    if required_import not in text:
        anchor = "import com.eclipsesource.v8.JavaVoidCallback\n"
        if anchor not in text:
            die(f"cannot locate JavaVoidCallback import in updated SmartTube source: {path}")
        text = text.replace(anchor, required_import + anchor, 1)
        changed = True
    if required_method not in text:
        anchor = """    fun registerJavaMethod(callback: JavaVoidCallback, jsFunctionName: String) {
        synchronized(v8Lock) {
            initRuntime()
            v8Runtime?.registerJavaMethod(callback, jsFunctionName)
        }
    }
"""
        replacement = """    fun registerJavaMethod(callback: JavaVoidCallback, jsFunctionName: String) {
        synchronized(v8Lock) {
            initRuntime()
            v8Runtime?.registerJavaMethod(callback, jsFunctionName)
        }
    }

    fun registerJavaMethod(callback: JavaCallback, jsFunctionName: String) {
        synchronized(v8Lock) {
            initRuntime()
            v8Runtime?.registerJavaMethod(callback, jsFunctionName)
        }
    }
"""
        if anchor not in text:
            die(f"cannot locate registerJavaMethod in updated SmartTube source: {path}")
        text = text.replace(anchor, replacement, 1)
        changed = True
    if changed:
        path.write_text(text)
    return changed


def patch_potoken_v8_set_interval_return(path: Path, check: bool) -> bool:
    """Return the interval id to JS; JavaVoidCallback discarded the last expression."""
    if not path.is_file():
        return False
    text = path.read_text()
    legacy = "        v8Wrapper.registerJavaMethod({ _, args ->\n            val delay = args.getInteger(0)\n"
    replacement = "        v8Wrapper.registerJavaMethod(JavaCallback { _, args ->\n            val delay = args.getInteger(0)\n"
    required_import = "import com.eclipsesource.v8.JavaCallback\n"
    if check:
        if legacy in text:
            die(f"PoTokenV8 still registers __nativeSetInterval as a void callback: {path}")
        if "JavaCallback { _, args ->" not in text or required_import not in text:
            die(f"PoTokenV8 JavaCallback setInterval patch is not applied: {path}")
        return False
    changed = False
    if required_import not in text:
        anchor = "import com.liskovsoft.youtubeapi.app.potokennp2.misc.V8Wrapper\n"
        if anchor not in text:
            die(f"cannot locate PoTokenV8 import anchor in updated SmartTube source: {path}")
        text = text.replace(anchor, required_import + anchor, 1)
        changed = True
    if legacy in text:
        text = text.replace(legacy, replacement, 1)
        changed = True
    if changed:
        path.write_text(text)
    return changed


def patch_request_body_create_media_first(path: Path, check: bool) -> bool:
    """Swap RequestBody.create(mediaType, payload) to the non-deprecated (payload, mediaType) overload."""
    if not path.is_file():
        return False
    text = path.read_text()
    replacements = [
        (
            "RequestBody.create(MEDIA_TYPE, value.toString())",
            "RequestBody.create(value.toString(), MEDIA_TYPE)",
        ),
        (
            "RequestBody.create(MEDIA_TYPE, value instanceof String ? (String) value : gson.toJson(value))",
            "RequestBody.create(value instanceof String ? (String) value : gson.toJson(value), MEDIA_TYPE)",
        ),
        (
            "RequestBody.create(MEDIA_TYPE, buffer.readByteString())",
            "RequestBody.create(buffer.readByteString(), MEDIA_TYPE)",
        ),
    ]
    if check:
        if any(old in text for old, _new in replacements):
            die(f"legacy RequestBody.create(mediaType, payload) remains in {path}")
        return False
    changed = False
    for old, new in replacements:
        if old in text:
            text = text.replace(old, new)
            changed = True
    if changed:
        path.write_text(text)
    return True


def patch_auth_v1_request_body(path: Path, check: bool) -> bool:
    """Swap Auth V1 RequestBody.create(null, bytes) to (bytes, null)."""
    if not path.is_file():
        return False
    text = path.read_text()
    legacy = "RequestBody.create(null, rawAuthData.getBytes())"
    replacement = "RequestBody.create(rawAuthData.getBytes(), null)"
    if check:
        if legacy in text:
            die(f"legacy RequestBody.create(null, bytes) remains in {path}")
        return False
    if legacy not in text:
        return False
    path.write_text(text.replace(legacy, replacement, 1))
    return True


def patch_public_dns_record(path: Path, check: bool) -> bool:
    """Disambiguate dnsjava Record from the inherited OkHttp Dns.Record."""
    if not path.is_file():
        die(f"SmartTube PublicDnsResolver moved or disappeared: {path}")
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    replacements = [
        ("Record[] records = lookup.run();", "org.xbill.DNS.Record[] records = lookup.run();"),
        ("for (Record record : records)", "for (org.xbill.DNS.Record record : records)"),
    ]
    original = text
    for old, new in replacements:
        if new in text:
            continue
        if check or old not in text:
            die(f"SmartTube DNS Record patch missing or anchor changed: {path}")
        text = text.replace(old, new, 1)
    text = re.sub(r"^import org\.xbill\.DNS\.Record;\r?\n", "", text, flags=re.MULTILINE)
    if text == original or check:
        return False
    path.write_bytes(text.encode("utf-8"))
    return True


def patch_skip_playable_subtitle_refetch(path: Path, check: bool) -> bool:
    """Skip the second WEB player call when formats are already playable.

    applyFixesIfNeeded fetches getVideoInfo(WEB) solely to expand translation
    languages before returning. That round trip holds playback after a playable
    client response. Caption tracks already on the response stay, and a warm
    language cache is still applied. Subtitle loading after the player is
    published is untouched.
    """
    if not path.is_file():
        die(f"SmartTube VideoInfoService moved or disappeared: {path}")
    raw = path.read_bytes()
    newline = b"\r\n" if b"\r\n" in raw else b"\n"
    text = raw.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n")
    marker = "AMBIENT_TV_SKIP_PLAYABLE_SUBTITLE_REFETCH"
    legacy = """        // TV and others has a limited number of auto generated subtitles
        if (needMoreSubtitles(result)) {
            Log.d(TAG, "Enable full list of auto generated subtitles...");

            if (mCachedTranslationLanguages == null || mCachedTranslationLanguages.size() < 100) {
                mUseAuth = false;
                VideoInfo webInfo = null;
                try {
                    webInfo = getVideoInfo(AppClient.WEB, videoId, clickTrackingParams);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (webInfo != null) {
                    mCachedTranslationLanguages = webInfo.getTranslationLanguages();
                }
            }
"""
    replacement = """        // TV and others has a limited number of auto generated subtitles
        if (needMoreSubtitles(result)) {
            Log.d(TAG, "Enable full list of auto generated subtitles...");

            // AMBIENT_TV_SKIP_PLAYABLE_SUBTITLE_REFETCH: WEB player only expands
            // translation languages. Do not block playable formats on that call.
            if (!hasPlayableFormats(result) && (mCachedTranslationLanguages == null || mCachedTranslationLanguages.size() < 100)) {
                mUseAuth = false;
                VideoInfo webInfo = null;
                try {
                    webInfo = getVideoInfo(AppClient.WEB, videoId, clickTrackingParams);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (webInfo != null) {
                    mCachedTranslationLanguages = webInfo.getTranslationLanguages();
                }
            }
"""
    # Upstream now uses WEB_DUMMY for translation languages. Preserve the
    # selected client while accepting both known versions of this block.
    variants = [
        (legacy, replacement),
        (legacy.replace("AppClient.WEB,", "AppClient.WEB_DUMMY,"),
         replacement.replace("AppClient.WEB,", "AppClient.WEB_DUMMY,")),
    ]
    helper_anchor = "    private static boolean needMoreSubtitles(VideoInfo videoInfo) {\n"
    helper = """    private static boolean hasPlayableFormats(VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.isUnplayable()) {
            return false;
        }
        if (videoInfo.getDashManifestUrl() != null && !videoInfo.getDashManifestUrl().isEmpty()) {
            return true;
        }
        if (videoInfo.getHlsManifestUrl() != null && !videoInfo.getHlsManifestUrl().isEmpty()) {
            return true;
        }
        String sabr = videoInfo.getServerAbrStreamingUrl();
        if (sabr != null && !sabr.isEmpty()) {
            return true;
        }
        return videoInfo.containsAdaptiveVideoInfo() || videoInfo.containsRegularVideoInfo();
    }

    private static boolean needMoreSubtitles(VideoInfo videoInfo) {
"""
    if check:
        if marker not in text or "hasPlayableFormats(result)" not in text or "private static boolean hasPlayableFormats" not in text:
            die(f"SmartTube playable-format subtitle refetch skip is not applied: {path}")
        if any(old in text for old, _ in variants):
            die(f"second WEB player round trip still blocks playable formats: {path}")
        return False
    if marker in text:
        if "private static boolean hasPlayableFormats" not in text or any(old in text for old, _ in variants):
            die(f"SmartTube subtitle refetch skip is only partially applied: {path}")
        return False
    matches = [(old, new) for old, new in variants if old in text]
    if len(matches) != 1:
        die(f"cannot locate VideoInfoService subtitle refetch in updated SmartTube source: {path}")
    if helper_anchor not in text:
        die(f"cannot locate needMoreSubtitles in updated SmartTube source: {path}")
    legacy, replacement = matches[0]
    text = text.replace(legacy, replacement, 1)
    text = text.replace(helper_anchor, helper, 1)
    path.write_bytes(text.replace("\n", newline.decode("ascii")).encode("utf-8"))
    return True


def patch_upcoming_streams(msc: Path, check: bool) -> list[Path]:
    """Expose structured offline status and scheduled time through both player clients."""
    base = "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/"
    patches = {
        "mediaserviceinterfaces/src/main/java/com/liskovsoft/mediaserviceinterfaces/data/MediaItem.java": [
            ("    boolean isUpcoming();", "    boolean isUpcoming();\n    default String getUpcomingEventStartTime() { return null; }")],
        base + "common/models/impl/mediaitem/MediaItemImpl.kt": [
            ('    override val isUpcomingItem by lazy { itemWrapper.isUpcoming() }', '    override val isUpcomingItem by lazy { itemWrapper.isUpcoming() }\n    override fun getUpcomingEventStartTime() = itemWrapper.getUpcomingEventStartTime()')],
        base + "common/models/gen/CommonHelper.kt": [
            ('internal fun ItemWrapper.getUpcomingEventText()', 'internal fun ItemWrapper.getUpcomingEventStartTime() = getVideoItem()?.upcomingEventData?.startTime\n\ninternal fun ItemWrapper.getUpcomingEventText()')],
        base + "service/data/YouTubeMediaItem.java": [
            ('    private boolean mIsUpcoming;', '    private boolean mIsUpcoming;\n    private String mUpcomingEventStartTime;\n    @Override public String getUpcomingEventStartTime() { return mUpcomingEventStartTime; }'),
            ('    public static YouTubeMediaItem from(VideoItem item) {\n        YouTubeMediaItem video = new YouTubeMediaItem();', '    public static YouTubeMediaItem from(VideoItem item) {\n        YouTubeMediaItem video = new YouTubeMediaItem();\n        video.mUpcomingEventStartTime = item.getUpcomingEventStartTime();')],
        "mediaserviceinterfaces/src/main/java/com/liskovsoft/mediaserviceinterfaces/data/MediaItemFormatInfo.java": [
            ("    boolean isLiveContent();", "    boolean isLiveContent();\n    default boolean isLiveOffline() { return false; }\n    default String getScheduledStartTime() { return null; }")],
        base + "videoinfo/models/VideoInfo.java": [
            ('    private String mPlayabilityStatus;', '    private String mPlayabilityStatus;\n\n    @JsonPath("$.playabilityStatus.liveStreamability.liveStreamabilityRenderer.offlineSlate.liveStreamOfflineSlateRenderer.scheduledStartTime")\n    private String mScheduledStartTime;\n\n    public boolean isLiveOffline() { return STATUS_OFFLINE.equals(mPlayabilityStatus); }\n    public String getScheduledStartTime() { return mScheduledStartTime; }')],
        base + "service/data/YouTubeMediaItemFormatInfo.java": [
            ('    private String mPlayabilityStatus;', '    private String mPlayabilityStatus;\n    private boolean mLiveOffline;\n    private String mScheduledStartTime;\n    @Override public boolean isLiveOffline() { return mLiveOffline; }\n    @Override public String getScheduledStartTime() { return mScheduledStartTime; }'),
            ('        formatInfo.mPlayabilityStatus = videoInfo.getPlayabilityStatus();', '        formatInfo.mPlayabilityStatus = videoInfo.getPlayabilityStatus();\n        formatInfo.mLiveOffline = videoInfo.isLiveOffline();\n        formatInfo.mScheduledStartTime = videoInfo.getScheduledStartTime();')],
        base + "innertube/models/PlayerResult.kt": [
            ('        val errorScreen: ErrorScreen?', '        val liveStreamability: LiveStreamability? = null,\n        val errorScreen: ErrorScreen?'),
            ('        data class ErrorScreen(', '        data class LiveStreamability(val liveStreamabilityRenderer: Renderer?) {\n            data class Renderer(val offlineSlate: OfflineSlate?)\n            data class OfflineSlate(val liveStreamOfflineSlateRenderer: Slate?)\n            data class Slate(val scheduledStartTime: String?)\n        }\n        data class ErrorScreen(')],
        base + "innertube/impl/MediaItemFormatInfoImpl.kt": [
            ('    override fun isLiveContent() = _isLiveContent', '    override fun isLiveContent() = _isLiveContent\n    override fun isLiveOffline() = _playabilityStatus == STATUS_OFFLINE\n    override fun getScheduledStartTime() = playerResult.playabilityStatus?.liveStreamability?.liveStreamabilityRenderer?.offlineSlate?.liveStreamOfflineSlateRenderer?.scheduledStartTime')],
    }
    changed = []
    for relative, replacements in patches.items():
        path = msc / relative
        if not path.is_file():
            die(f"SmartTube upcoming-stream source missing: {path}")
        raw = path.read_bytes()
        text = raw.decode().replace("\r\n", "\n")
        original = text
        for old, new in replacements:
            if new in text:
                continue
            if check or old not in text:
                die(f"SmartTube upcoming-stream patch missing or anchor changed: {path}")
            text = text.replace(old, new, 1)
        if text != original:
            newline = "\r\n" if b"\r\n" in raw else "\n"
            path.write_bytes(text.replace("\n", newline).encode())
            changed.append(path)
    return changed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--root", type=Path, required=True)
    args = ap.parse_args()

    root = args.root.resolve()
    msc = root / "MediaServiceCore"
    shared = root / "SharedModules"
    if not (msc / ".git").exists() or not (shared / ".git").exists():
        die(f"SmartTube repositories are not present under {root}")

    changed: list[Path] = []
    for manifest in manifest_files(msc, shared):
        if patch_manifest(manifest, args.check):
            changed.append(manifest)

    public_dns = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/okhttp/PublicDnsResolver.java"
    if patch_public_dns_record(public_dns, args.check):
        changed.append(public_dns)

    doh = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/okhttp/DohProviders.kt"
    if patch_doh(doh, args.check):
        changed.append(doh)

    okhttp_commons = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/okhttp/OkHttpCommons.java"
    if patch_okhttp_debug_dump(okhttp_commons, args.check):
        changed.append(okhttp_commons)

    okhttp_manager = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/okhttp/OkHttpManager.java"
    if patch_okhttp_manager_request_body(okhttp_manager, args.check):
        changed.append(okhttp_manager)

    cronet = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/cronet/CronetManager.kt"
    if patch_cronet_experimental_import(cronet, args.check):
        changed.append(cronet)

    v8_utils = shared / "j2v8/src/main/java/com/eclipsesource/v8/utils/V8ObjectUtils.java"
    if patch_v8_object_utils_double(v8_utils, args.check):
        changed.append(v8_utils)

    logger = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/mylogger/Log.java"
    if patch_logger_quiet(logger, args.check):
        changed.append(logger)

    changed.extend(patch_youtubeapi_okhttp_kotlin(msc, args.check))

    v8_wrapper = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/app/potokennp2/misc/V8Wrapper.kt"
    if patch_v8_wrapper_java_callback(v8_wrapper, args.check):
        changed.append(v8_wrapper)

    potoken_v8 = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/app/potokennp2/generators/PoTokenV8.kt"
    if patch_potoken_v8_set_interval_return(potoken_v8, args.check):
        changed.append(potoken_v8)

    youtube_java = msc / "youtubeapi/src/main/java"
    for rel in (
        "com/liskovsoft/googlecommon/common/converters/gson/GsonRequestBodyConverter.java",
        "com/liskovsoft/googlecommon/common/converters/querystring/converter/QueryStringRequestBodyConverter.java",
        "com/liskovsoft/googlecommon/common/converters/jsonpath/converter/JsonPathRequestBodyConverter.java",
        "com/liskovsoft/googlecommon/common/converters/regexp/converter/RegExpRequestBodyConverter.java",
    ):
        converter = youtube_java / rel
        if patch_request_body_create_media_first(converter, args.check):
            changed.append(converter)

    auth_v1 = youtube_java / "com/liskovsoft/youtubeapi/auth/V1/AuthService.java"
    if patch_auth_v1_request_body(auth_v1, args.check):
        changed.append(auth_v1)

    format_info = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/service/YouTubeMediaItemService.java"
    if patch_format_info_null_fallback(format_info, args.check):
        changed.append(format_info)

    video_info = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/videoinfo/V2/VideoInfoService.java"
    if patch_skip_playable_subtitle_refetch(video_info, args.check):
        changed.append(video_info)

    video_url_holder = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi/videoinfo/models/VideoUrlHolder.kt"
    if patch_video_url_holder_invalid_percent(video_url_holder, args.check):
        changed.append(video_url_holder)

    query_base = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/querystringparser/UrlEncodedQueryStringBase.java"
    if patch_url_encoded_query_invalid_percent(query_base, args.check):
        changed.append(query_base)

    changed.extend(patch_upcoming_streams(msc, args.check))
    changed.extend(patch_youtube_auth_device_code_recovery(msc, args.check))
    changed.extend(patch_youtube_auth_device_code_diagnostics(msc, args.check))

    if not args.check:
        for path in changed:
            print(f"patched: {path.relative_to(root)}")
        if not changed:
            print("SmartTube compatibility patch already applied")
    return 0


if __name__ == "__main__":
    sys.exit(main())
