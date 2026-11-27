import sys
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import patch_smarttube_compat as patcher


class SmartTubeDeprecationPatchesTest(unittest.TestCase):
    def test_dns_record_is_qualified_and_patch_is_idempotent(self) -> None:
        for newline in ("\n", "\r\n"):
            with self.subTest(newline=repr(newline)):
                path = Path(self.temporary.name) / "PublicDnsResolver.java"
                path.write_bytes(newline.join([
                    "import org.xbill.DNS.Record;",
                    "Record[] records = lookup.run();",
                    "for (Record record : records) {",
                    "}", "",
                ]).encode())
                with self.assertRaises(SystemExit):
                    patcher.patch_public_dns_record(path, check=True)
                self.assertTrue(patcher.patch_public_dns_record(path, check=False))
                patched = path.read_bytes()
                self.assertIn(b"org.xbill.DNS.Record[] records", patched)
                self.assertIn(b"for (org.xbill.DNS.Record record", patched)
                self.assertNotIn(b"import org.xbill.DNS.Record;", patched)
                self.assertEqual(patched, path.read_text().replace("\n", newline).encode())
                self.assertFalse(patcher.patch_public_dns_record(path, check=True))
                self.assertFalse(patcher.patch_public_dns_record(path, check=False))
                self.assertEqual(patched, path.read_bytes())

    def subtitle_source(self, client: str) -> str:
        return """        // TV and others has a limited number of auto generated subtitles
        if (needMoreSubtitles(result)) {
            Log.d(TAG, "Enable full list of auto generated subtitles...");

            if (mCachedTranslationLanguages == null || mCachedTranslationLanguages.size() < 100) {
                mUseAuth = false;
                VideoInfo webInfo = null;
                try {
                    webInfo = getVideoInfo(AppClient.CLIENT, videoId, clickTrackingParams);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (webInfo != null) {
                    mCachedTranslationLanguages = webInfo.getTranslationLanguages();
                }
            }
            result.setTranslationLanguages(mCachedTranslationLanguages);
        }
    private static boolean needMoreSubtitles(VideoInfo videoInfo) {
""".replace("AppClient.CLIENT", f"AppClient.{client}")

    def test_subtitle_refetch_preserves_client_and_line_endings(self) -> None:
        for client in ("WEB", "WEB_DUMMY"):
            for newline in ("\n", "\r\n"):
                with self.subTest(client=client, newline=repr(newline)):
                    path = Path(self.temporary.name) / "VideoInfoService.java"
                    path.write_bytes(self.subtitle_source(client).replace("\n", newline).encode())
                    with self.assertRaises(SystemExit):
                        patcher.patch_skip_playable_subtitle_refetch(path, check=True)
                    self.assertTrue(patcher.patch_skip_playable_subtitle_refetch(path, check=False))
                    patched = path.read_bytes()
                    text = path.read_text()
                    self.assertIn(f"getVideoInfo(AppClient.{client},", text)
                    self.assertIn("if (!hasPlayableFormats(result) &&", text)
                    self.assertIn("result.setTranslationLanguages(mCachedTranslationLanguages);", text)
                    self.assertEqual(patched, text.replace("\n", newline).encode())
                    patcher.patch_skip_playable_subtitle_refetch(path, check=True)
                    self.assertFalse(patcher.patch_skip_playable_subtitle_refetch(path, check=False))
                    self.assertEqual(patched, path.read_bytes())

    def test_subtitle_refetch_rejects_unknown_upstream_client(self) -> None:
        path = self.write("VideoInfoService.java", self.subtitle_source("UNKNOWN"))
        original = path.read_bytes()
        with self.assertRaises(SystemExit):
            patcher.patch_skip_playable_subtitle_refetch(path, check=False)
        self.assertEqual(original, path.read_bytes())

    def test_auth_cache_recovery_supports_renamed_class(self) -> None:
        for name in ("AppServiceIntCached.java", "AppServiceCoreCached.java"):
            with self.subTest(name=name):
                msc = Path(self.temporary.name) / name
                base = msc / "youtubeapi/src/main/java/com/liskovsoft/youtubeapi"
                auth = base / "auth/V2/AuthService.java"
                auth.parent.mkdir(parents=True)
                auth.write_text("// AMBIENT_TV_DEVICE_CODE_RECOVERY\n")
                cache = base / "app" / name
                cache.parent.mkdir(parents=True)
                cache.write_text(
                    "    public void invalidateCache() {\n"
                    "        mAppInfo = null;\n"
                    "        // Don\'t reset Player\'s cache. It\'s too heavy to recreate often.\n"
                    "        // Better do it inside MediaServiceData after the update\n"
                    "    }\n"
                )
                self.assertEqual([cache], patcher.patch_youtube_auth_device_code_recovery(msc, check=False))
                self.assertIn("getData().setClientData(null);", cache.read_text())
                self.assertEqual([], patcher.patch_youtube_auth_device_code_recovery(msc, check=True))
                self.assertEqual([], patcher.patch_youtube_auth_device_code_recovery(msc, check=False))

    def write(self, name: str, text: str) -> Path:
        path = Path(self.temporary.name) / name
        path.write_text(text)
        return path

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="smarttube-patch-")
        self.addCleanup(self.temporary.cleanup)

    def test_drive_request_body_uses_okhttp_extensions(self) -> None:
        path = self.write(
            "DriveServiceInt.kt",
            "import okhttp3.RequestBody\n"
            "        uploadFile(RequestBody.create(FILE_MIME_TYPE.toMediaTypeOrNull(), file), path)\n"
            "        uploadFile(RequestBody.create(FILE_MIME_TYPE.toMediaTypeOrNull(), content), path)\n",
        )
        self.assertTrue(patcher.patch_drive_request_body(path, check=False))
        text = path.read_text()
        self.assertIn("file.asRequestBody(FILE_MIME_TYPE.toMediaTypeOrNull())", text)
        self.assertIn("content.toRequestBody(FILE_MIME_TYPE.toMediaTypeOrNull())", text)
        self.assertIn("import okhttp3.RequestBody.Companion.asRequestBody", text)
        self.assertIn("import okhttp3.RequestBody.Companion.toRequestBody", text)
        self.assertNotIn("RequestBody.create(", text)
        patcher.patch_drive_request_body(path, check=True)

    def test_ytcfg_uses_parse_string(self) -> None:
        path = self.write(
            "YtCfgService.kt",
            "        val parser = JsonParser()\n"
            "        return parser.parse(ytCfgStr?.ytCfg).asJsonObject\n",
        )
        self.assertTrue(patcher.patch_ytcfg_json_parser(path, check=False))
        text = path.read_text()
        self.assertIn("JsonParser.parseString(ytCfg)", text)
        self.assertNotIn("JsonParser()", text)
        patcher.patch_ytcfg_json_parser(path, check=True)

    def test_unused_cronet_manager_and_adapter_are_removed(self) -> None:
        root = Path(self.temporary.name)
        msc = root / "MediaServiceCore"
        shared = root / "SharedModules"
        helper = msc / "youtubeapi/src/main/java/com/liskovsoft/googlecommon/common/helpers/RetrofitOkHttpHelper.kt"
        helper.parent.mkdir(parents=True)
        helper.write_text(
            "import com.google.net.cronet.okhttptransport.CronetInterceptor\n"
            "import com.liskovsoft.sharedutils.cronet.CronetManager\n"
            "    private fun createClient(): OkHttpClient {\n"
            "        val builder = OkHttpManager.instance().client.newBuilder()\n"
            "        //addCronetInterceptor(builder)\n"
            "        return builder.build()\n"
            "    }\n"
            "    private fun addCronetInterceptor(builder: OkHttpClient.Builder) {\n"
            "        val engine = CronetManager.getEngine(AppService.instance().context)\n"
            "        if (engine != null) builder.addInterceptor(CronetInterceptor.newBuilder(engine).build())\n"
            "    }\n"
            "}\n"
        )
        manager = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/cronet/CronetManager.kt"
        manager.parent.mkdir(parents=True)
        manager.write_text("object CronetManager")
        wrapper = manager.with_name("CronetEngineWrapper.java")
        wrapper.write_text("class CronetEngineWrapper {}")
        with self.assertRaises(SystemExit):
            patcher.patch_disable_unused_cronet(msc, shared, check=True)
        self.assertEqual([manager, wrapper, helper], patcher.patch_disable_unused_cronet(msc, shared, check=False))
        self.assertFalse(wrapper.exists())
        self.assertFalse(manager.exists())
        patched = helper.read_text()
        self.assertNotIn("Cronet", patched)
        self.assertNotIn("addCronetInterceptor", patched)
        self.assertEqual([], patcher.patch_disable_unused_cronet(msc, shared, check=True))
        helper.write_text(patched + "\nimport com.liskovsoft.sharedutils.cronet.CronetManager\n")
        with self.assertRaises(SystemExit):
            patcher.patch_disable_unused_cronet(msc, shared, check=True)
        self.assertIn("CronetManager", helper.read_text())

    def test_active_cronet_adapter_is_not_modified(self) -> None:
        root = Path(self.temporary.name)
        msc = root / "MediaServiceCore"
        shared = root / "SharedModules"
        helper = msc / "youtubeapi/src/main/java/com/liskovsoft/googlecommon/common/helpers/RetrofitOkHttpHelper.kt"
        manager = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/cronet/CronetManager.kt"
        for path in (helper, manager):
            path.parent.mkdir(parents=True, exist_ok=True)
        helper.write_text("        addCronetInterceptor(builder)\n")
        manager.write_text("object CronetManager")
        before = (helper.read_bytes(), manager.read_bytes())
        with self.assertRaises(SystemExit):
            patcher.patch_disable_unused_cronet(msc, shared, check=False)
        self.assertEqual(before, (helper.read_bytes(), manager.read_bytes()))

    def test_cronet_manager_delete_failure_rolls_back_helper(self) -> None:
        root = Path(self.temporary.name)
        msc = root / "MediaServiceCore"
        shared = root / "SharedModules"
        helper = msc / "youtubeapi/src/main/java/com/liskovsoft/googlecommon/common/helpers/RetrofitOkHttpHelper.kt"
        manager = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/cronet/CronetManager.kt"
        for path in (helper, manager):
            path.parent.mkdir(parents=True, exist_ok=True)
        helper.write_bytes(b"import com.liskovsoft.sharedutils.cronet.CronetManager\r\n")
        manager.write_text("object CronetManager")
        before = (helper.read_bytes(), manager.read_bytes())
        original_unlink = Path.unlink

        def fail_manager(path, *args, **kwargs):
            if path == manager:
                raise PermissionError("injected manager deletion failure")
            return original_unlink(path, *args, **kwargs)

        with mock.patch.object(Path, "unlink", fail_manager):
            with self.assertRaises(PermissionError):
                patcher.patch_disable_unused_cronet(msc, shared, check=False)
        self.assertEqual(before, (helper.read_bytes(), manager.read_bytes()))
        self.assertFalse(list(helper.parent.glob(".ambient-cronet-*")))

    def test_cronet_wrapper_delete_failure_rolls_back_all_sources(self) -> None:
        root = Path(self.temporary.name)
        msc = root / "MediaServiceCore"
        shared = root / "SharedModules"
        helper = msc / "youtubeapi/src/main/java/com/liskovsoft/googlecommon/common/helpers/RetrofitOkHttpHelper.kt"
        manager = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/cronet/CronetManager.kt"
        for path in (helper, manager):
            path.parent.mkdir(parents=True, exist_ok=True)
        helper.write_bytes(b"import com.liskovsoft.sharedutils.cronet.CronetManager\r\n")
        manager.write_text("object CronetManager")
        wrapper = manager.with_name("CronetEngineWrapper.java")
        wrapper.write_text("class CronetEngineWrapper {}")
        before = (helper.read_bytes(), manager.read_bytes(), wrapper.read_bytes())
        original_unlink = Path.unlink

        def fail_manager(path, *args, **kwargs):
            if path == wrapper:
                raise PermissionError("injected manager deletion failure")
            return original_unlink(path, *args, **kwargs)

        with mock.patch.object(Path, "unlink", fail_manager):
            with self.assertRaises(PermissionError):
                patcher.patch_disable_unused_cronet(msc, shared, check=False)
        self.assertEqual(before, (helper.read_bytes(), manager.read_bytes(), wrapper.read_bytes()))
        self.assertFalse(list(helper.parent.glob(".ambient-cronet-*")))

    def test_auth_headers_publish_complete_snapshots(self) -> None:
        root = Path(self.temporary.name)
        source = root / "youtubeapi/src/main/java"
        fixtures = {
            "com/liskovsoft/googlecommon/common/helpers/RetrofitOkHttpHelper.kt": """    @JvmStatic
    val authHeaders = mutableMapOf<String, String>()
                if (authHeaders.isEmpty() || doSkipAuth) {
                    applyHeaders(authHeaders, headers, requestBuilder)
""",
            "com/liskovsoft/youtubeapi/service/YouTubeSignInService.java": """    private String mCachedAuthorizationHeader;
    private long mCacheUpdateTime;

    private YouTubeSignInService() {
        mAccountManager = YouTubeAccountManager.instance(this);

        GlobalPreferences.setOnInit(() -> {
            mAccountManager.init();
            try {
                updateAuthHeadersIfNeeded();
            } catch (Exception e) {
                // Host not found
                e.printStackTrace();
            }
        });
    }

    public static YouTubeSignInService instance() {
        if (sInstance == null) {
            sInstance = new YouTubeSignInService();
        }

        return sInstance;
    }

    @Override
    public Observable<String> signInObserve() {
        return mAccountManager.signInObserve();
    }

    public void checkAuth() {
        updateAuthHeadersIfNeeded();
    }

    private synchronized void updateAuthHeadersIfNeeded() {
        if (mCachedAuthorizationHeader != null && Helpers.equals(mCachedAuthorizationHeader, RetrofitOkHttpHelper.getAuthHeaders().get("Authorization"))
                && System.currentTimeMillis() - mCacheUpdateTime < TOKEN_REFRESH_PERIOD_MS) {
            return;
        }

        updateAuthHeaders();

        mCacheUpdateTime = System.currentTimeMillis();
    }

    private void updateAuthHeaders() {
        Account account = mAccountManager.getSelectedAccount();
        String refreshToken = account != null ? ((YouTubeAccount) account).getRefreshToken() : null;
        // get or create authorization on fly
        mCachedAuthorizationHeader = createAuthorizationHeader(refreshToken);
        syncWithRetrofit();
        mAccountManager.syncStorage();
    }

    @Override
    public boolean isSigned() {
        // Condition created for the case when a device in offline mode.
        return mAccountManager.getSelectedAccount() != null;
    }

    @Override
    public List<Account> getAccounts() {
        return mAccountManager.getAccounts();
    }

    @Nullable
    @Override
    public Account getSelectedAccount() {
        return mAccountManager.getSelectedAccount();
    }

    public void invalidateCache() {
        mCachedAuthorizationHeader = null;
        mCacheUpdateTime = 0;
    }

    // Fix empty content when quickly switch accounts???
    @Override
    public synchronized void selectAccount(Account account) {
        mAccountManager.selectAccount(account);
    }

    @Override
    public synchronized void removeAccount(Account account) {
        mAccountManager.removeAccount(account);
    }

    @Override
    public String printDebugInfo() {
        String name = "none";
        String header = "none";
        String token = "none";

        if (mCachedAuthorizationHeader != null) {
            header = "ok";
        }

        Account account = getSelectedAccount();

        if (account instanceof YouTubeAccount) {
            if (account.getName() != null) {
                name = "ok";
            }
            if (((YouTubeAccount) account).getRefreshToken() != null) {
                token = "ok";
            }
        }

        return String.format("name=%s;header=%s;token=%s", name, header, token);
    }


    private void syncWithRetrofit() {
        if (Helpers.isJUnitTest()) {
            return;
        }

        Map<String, String> headers = RetrofitOkHttpHelper.getAuthHeaders();
        headers.clear();

        Account selectedAccount = getSelectedAccount();

        if (mCachedAuthorizationHeader != null && selectedAccount != null) {
            headers.put("Authorization", mCachedAuthorizationHeader);
            String pageIdToken = ((YouTubeAccount) selectedAccount).getPageIdToken();
            if (pageIdToken != null) {
                // Apply branded account rights (restricted videos). Branded refresh token with current account page id.
                headers.put("X-Goog-Pageid", pageIdToken);
            }
        }
    }

    @Override
    public void addOnAccountChange(OnAccountChange listener) {
        mAccountManager.addOnAccountChange(listener);
    }

""",
            "com/liskovsoft/googleapi/oauth2/manager/OAuth2AccountManagerBase.java": """    private String mCachedAuthorizationHeader;
    private long mCacheUpdateTime;

    protected abstract Account getSelectedAccount();
    protected abstract AccessToken obtainAccessToken(String refreshToken);

    public void checkAuth() {
        updateAuthHeadersIfNeeded();
    }

    protected void updateAuthHeadersIfNeeded() {
        if (mCachedAuthorizationHeader != null && Helpers.equals(mCachedAuthorizationHeader, RetrofitOkHttpHelper.getAuthHeaders().get("Authorization"))
                && System.currentTimeMillis() - mCacheUpdateTime < TOKEN_REFRESH_PERIOD_MS) {
            return;
        }

        updateAuthHeaders();

        mCacheUpdateTime = System.currentTimeMillis();
    }

    private void updateAuthHeaders() {
        Account account = getSelectedAccount();
        String refreshToken = account != null ? ((YouTubeAccount) account).getRefreshToken() : null;
        // get or create authorization on fly
        mCachedAuthorizationHeader = createAuthorizationHeader(refreshToken);
        syncWithRetrofit();
    }

    /**
     * For testing purposes
     */
    public void setAuthorizationHeader(String authorizationHeader) {
        mCachedAuthorizationHeader = authorizationHeader;
        mCacheUpdateTime = System.currentTimeMillis();

        syncWithRetrofit();
    }

    public void invalidateCache() {
        mCachedAuthorizationHeader = null;
    }


    private void syncWithRetrofit() {
        if (Helpers.isJUnitTest()) {
            return;
        }

        Map<String, String> headers = RetrofitOkHttpHelper.getAuthHeaders();
        headers.clear();

        if (mCachedAuthorizationHeader != null && getSelectedAccount() != null) {
            headers.put("Authorization", mCachedAuthorizationHeader);
            String pageIdToken = ((YouTubeAccount) getSelectedAccount()).getPageIdToken();
            if (pageIdToken != null) {
                // Apply branded account rights (restricted videos). Branded refresh token with current account page id.
                headers.put("X-Goog-Pageid", pageIdToken);
            }
        }
    }
}
""",
        }
        paths = []
        for name, content in fixtures.items():
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content.replace("\n", "\r\n").encode())
            paths.append(path)
        before = [p.read_bytes() for p in paths]
        with self.assertRaises(SystemExit):
            patcher.patch_auth_header_snapshots(root, check=True)
        self.assertEqual(before, [p.read_bytes() for p in paths])
        self.assertEqual(paths, patcher.patch_auth_header_snapshots(root, check=False))
        helper = paths[0].read_text()
        self.assertIn("@Volatile", helper)
        self.assertIn("Collections.unmodifiableMap(HashMap(headers))", helper)
        self.assertIn("applyHeaders(requestAuthHeaders", helper)
        for path in paths[1:]:
            self.assertNotIn("headers.clear()", path.read_text())
            self.assertIn("publishAuthHeaders(generation, headers);", path.read_text())
            self.assertIn("syncWithRetrofit(account, generation);", path.read_text())
            self.assertIn("if (account != getSelectedAccount())", path.read_text())
            self.assertNotIn("Account selectedAccount = getSelectedAccount();", path.read_text())
        for path in paths:
            raw = path.read_bytes()
            self.assertNotIn(b"\n", raw.replace(b"\r\n", b""))
        self.assertEqual([], patcher.patch_auth_header_snapshots(root, check=True))
        self.assertEqual([], patcher.patch_auth_header_snapshots(root, check=False))

    def test_http_debug_patch_fails_closed(self) -> None:
        path = self.write("OkHttpCommons.java", "    private static void debugSetup(OkHttpClient.Builder okBuilder) {\n        if (BuildConfig.DEBUG) {\n")
        with self.assertRaises(SystemExit):
            patcher.patch_okhttp_debug_dump(path, check=True)
        self.assertTrue(patcher.patch_okhttp_debug_dump(path, check=False))
        self.assertFalse(patcher.patch_okhttp_debug_dump(path, check=True))
        self.assertFalse(patcher.patch_okhttp_debug_dump(path, check=False))
        path.write_text("    private static void debugSetup(OkHttpClient.Builder okBuilder) {\n        if (true) {\n")
        for check in (False, True):
            with self.assertRaises(SystemExit):
                patcher.patch_okhttp_debug_dump(path, check=check)

    def test_legacy_tls_bypass_is_disabled(self) -> None:
        path = self.write("OkHttpCommons.java", "        configureToIgnoreCertificate(okBuilder);\n")
        with self.assertRaises(SystemExit):
            patcher.patch_disable_legacy_tls_bypass(path, check=True)
        self.assertTrue(patcher.patch_disable_legacy_tls_bypass(path, check=False))
        self.assertNotIn("configureToIgnoreCertificate(okBuilder);", path.read_text())
        self.assertFalse(patcher.patch_disable_legacy_tls_bypass(path, check=True))
        self.assertFalse(patcher.patch_disable_legacy_tls_bypass(path, check=False))

    def test_okhttp_manager_initialization_is_thread_safe(self) -> None:
        path = self.write(
            "OkHttpManager.java",
            """private static OkHttpManager sInstance;
private OkHttpClient mClient;
public static void unhold() { sInstance = null; }
public static OkHttpManager instance(boolean enableProfiler) {
        if (sInstance == null) {
            sInstance = new OkHttpManager(enableProfiler);
        }

        return sInstance;
    }
public OkHttpClient getClient() {
        if (mClient == null) {
            OkHttpCommons.enableProfiler = mEnableProfiler;
            mClient = OkHttpCommons.setupBuilder(new OkHttpClient.Builder()).build();
        }

        return mClient;
    }
""",
        )
        with self.assertRaises(SystemExit):
            patcher.patch_okhttp_manager_thread_safety(path, check=True)
        self.assertTrue(patcher.patch_okhttp_manager_thread_safety(path, check=False))
        patched = path.read_text()
        for expected in (
            "private static volatile OkHttpManager sInstance;",
            "private volatile OkHttpClient mClient;",
            "public static synchronized void unhold()",
            "OkHttpManager current = sInstance;",
            "OkHttpClient current = mClient;",
            "if (current != null) return current;",
        ):
            self.assertIn(expected, patched)
        self.assertEqual(2, patched.count("synchronized (OkHttpManager.class)"))
        self.assertFalse(patcher.patch_okhttp_manager_thread_safety(path, check=True))
        self.assertFalse(patcher.patch_okhttp_manager_thread_safety(path, check=False))

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_patched_okhttp_manager_concurrent_lifecycle(self) -> None:
        path = self.write("OkHttpManager.java", """public class OkHttpManager {
    private static OkHttpManager sInstance;
    private OkHttpClient mClient;
    private final boolean mEnableProfiler;
    private OkHttpManager(boolean profiler) { mEnableProfiler = profiler; }
    public static void unhold() { sInstance = null; }
    public static OkHttpManager instance(boolean enableProfiler) {
        if (sInstance == null) {
            sInstance = new OkHttpManager(enableProfiler);
        }

        return sInstance;
    }
    public OkHttpClient getClient() {
        if (mClient == null) {
            OkHttpCommons.enableProfiler = mEnableProfiler;
            mClient = OkHttpCommons.setupBuilder(new OkHttpClient.Builder()).build();
        }

        return mClient;
    }
}
class OkHttpClient {
    static final java.util.concurrent.atomic.AtomicInteger builds =
        new java.util.concurrent.atomic.AtomicInteger();
    static class Builder {
        OkHttpClient build() {
            builds.incrementAndGet();
            return new OkHttpClient();
        }
    }
}
class OkHttpCommons {
    static boolean enableProfiler = true;
    static OkHttpClient.Builder setupBuilder(OkHttpClient.Builder builder) { return builder; }
}
""")
        patcher.patch_okhttp_manager_thread_safety(path, check=False)
        runner = self.write("ManagerHarness.java", """import java.util.concurrent.*;
public class ManagerHarness {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int generation = 0; generation < 20; generation++) {
                OkHttpManager.unhold();
                CountDownLatch start = new CountDownLatch(1);
                java.util.List<Future<OkHttpManager>> tasks = new java.util.ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    final boolean profiler = i % 2 == 0;
                    tasks.add(pool.submit(() -> {
                        start.await();
                        OkHttpManager manager = OkHttpManager.instance(profiler);
                        manager.getClient();
                        return manager;
                    }));
                }
                start.countDown();
                OkHttpManager first = tasks.get(0).get(5, TimeUnit.SECONDS);
                for (Future<OkHttpManager> future : tasks) {
                    if (first != future.get(5, TimeUnit.SECONDS))
                        throw new AssertionError("multiple managers");
                }
                if (OkHttpClient.builds.get() != generation + 1)
                    throw new AssertionError("multiple clients");
                if (!OkHttpCommons.enableProfiler)
                    throw new AssertionError("global profiler changed");
                OkHttpManager.unhold();
                if (first == OkHttpManager.instance(false))
                    throw new AssertionError("reset did not replace manager");
                if (first.getClient() == null)
                    throw new AssertionError("stale manager lost its client");
            }
            // Retain a manager across reset before its client is initialized.
            OkHttpManager.unhold();
            OkHttpManager stale = OkHttpManager.instance(false);
            OkHttpManager.unhold();
            OkHttpManager fresh = OkHttpManager.instance(true);
            Future<OkHttpClient> oldClient = pool.submit(stale::getClient);
            Future<OkHttpClient> newClient = pool.submit(fresh::getClient);
            if (oldClient.get(5, TimeUnit.SECONDS) == newClient.get(5, TimeUnit.SECONDS))
                throw new AssertionError("generations shared a client");
            if (OkHttpManager.instance(false) != fresh || !OkHttpCommons.enableProfiler)
                throw new AssertionError("stale initialization changed global state");

            // Race resets against first access; each retained manager must still
            // return a stable, non-null client even if no longer the singleton.
            java.util.List<Future<?>> races = new java.util.ArrayList<>();
            for (int i = 0; i < 128; i++) {
                final int task = i;
                races.add(pool.submit(() -> {
                    if (task % 4 == 0) OkHttpManager.unhold();
                    OkHttpManager manager = OkHttpManager.instance(task % 2 == 0);
                    OkHttpClient client = manager.getClient();
                    if (client == null || client != manager.getClient())
                        throw new AssertionError("unstable client during reset");
                }));
            }
            for (Future<?> race : races) race.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
""")
        subprocess.run(["javac", str(path), str(runner)], cwd=path.parent, check=True, timeout=30)
        subprocess.run(["java", "-cp", str(path.parent), "ManagerHarness"], check=True, timeout=30)

    def test_okhttp_manager_swaps_create_args(self) -> None:
        path = self.write(
            "OkHttpManager.java",
            "                .post(RequestBody.create(contentType != null ? MediaType.parse(contentType) : null, body))\n",
        )
        self.assertTrue(patcher.patch_okhttp_manager_request_body(path, check=False))
        self.assertIn(
            ".post(RequestBody.create(body, contentType != null ? MediaType.parse(contentType) : null))",
            path.read_text(),
        )
        patcher.patch_okhttp_manager_request_body(path, check=True)

    def test_v8_object_utils_drops_double_constructor(self) -> None:
        path = self.write(
            "V8ObjectUtils.java",
            "            result.push(new Double((Long) value));\n",
        )
        self.assertTrue(patcher.patch_v8_object_utils_double(path, check=False))
        self.assertIn("result.push(((Long) value).doubleValue());", path.read_text())
        patcher.patch_v8_object_utils_double(path, check=True)

    def test_retrofit_drops_unnecessary_safe_call(self) -> None:
        legacy = "newUrlBuilder?.addQueryParameter(entry.key, entry.value)"
        replacement = "newUrlBuilder.addQueryParameter(entry.key, entry.value)"
        path = self.write("RetrofitOkHttpHelper.kt", "")
        path.write_bytes(f"                {legacy}\r\n".encode())
        replacements = [(legacy, replacement)]

        self.assertTrue(patcher.patch_source_text(path, replacements, check=False))
        self.assertEqual(f"                {replacement}\r\n".encode(), path.read_bytes())
        self.assertFalse(patcher.patch_source_text(path, replacements, check=False))
        patcher.patch_source_text(path, replacements, check=True)

    def test_v8_wrapper_registers_java_callback(self) -> None:
        path = self.write(
            "V8Wrapper.kt",
            "import com.eclipsesource.v8.JavaVoidCallback\n"
            "    fun registerJavaMethod(callback: JavaVoidCallback, jsFunctionName: String) {\n"
            "        synchronized(v8Lock) {\n"
            "            initRuntime()\n"
            "            v8Runtime?.registerJavaMethod(callback, jsFunctionName)\n"
            "        }\n"
            "    }\n",
        )
        self.assertTrue(patcher.patch_v8_wrapper_java_callback(path, check=False))
        text = path.read_text()
        self.assertIn("import com.eclipsesource.v8.JavaCallback", text)
        self.assertIn("fun registerJavaMethod(callback: JavaCallback, jsFunctionName: String)", text)
        patcher.patch_v8_wrapper_java_callback(path, check=True)

    def test_potoken_v8_returns_interval_id(self) -> None:
        path = self.write(
            "PoTokenV8.kt",
            "import android.content.Context\n"
            "import com.liskovsoft.youtubeapi.app.potokennp2.misc.V8Wrapper\n"
            "        v8Wrapper.registerJavaMethod({ _, args ->\n"
            "            val delay = args.getInteger(0)\n"
            "            id\n"
            "        }, \"__nativeSetInterval\")\n",
        )
        self.assertTrue(patcher.patch_potoken_v8_set_interval_return(path, check=False))
        text = path.read_text()
        self.assertIn("import com.eclipsesource.v8.JavaCallback", text)
        self.assertIn("v8Wrapper.registerJavaMethod(JavaCallback { _, args ->", text)
        patcher.patch_potoken_v8_set_interval_return(path, check=True)

    def test_request_body_create_swaps_media_type_first(self) -> None:
        path = self.write(
            "GsonRequestBodyConverter.java",
            "        return RequestBody.create(MEDIA_TYPE, value.toString());\n",
        )
        self.assertTrue(patcher.patch_request_body_create_media_first(path, check=False))
        self.assertIn("RequestBody.create(value.toString(), MEDIA_TYPE)", path.read_text())
        patcher.patch_request_body_create_media_first(path, check=True)

    def test_auth_v1_request_body_swaps_null_type(self) -> None:
        path = self.write(
            "AuthService.java",
            "                RequestBody.create(null, rawAuthData.getBytes()));\n",
        )
        self.assertTrue(patcher.patch_auth_v1_request_body(path, check=False))
        self.assertIn("RequestBody.create(rawAuthData.getBytes(), null)", path.read_text())
        patcher.patch_auth_v1_request_body(path, check=True)


if __name__ == "__main__":
    unittest.main()
