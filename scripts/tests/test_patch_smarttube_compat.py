import sys
import tempfile
import unittest
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

    def test_cronet_drops_experimental_import(self) -> None:
        path = self.write(
            "CronetManager.kt",
            "import org.chromium.net.CronetEngine\n"
            "import org.chromium.net.ExperimentalCronetEngine\n"
            "import org.chromium.net.impl.NativeCronetProvider\n",
        )
        self.assertTrue(patcher.patch_cronet_experimental_import(path, check=False))
        self.assertNotIn("ExperimentalCronetEngine", path.read_text())
        patcher.patch_cronet_experimental_import(path, check=True)

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
        path = self.write(
            "RetrofitOkHttpHelper.kt",
            "                newUrlBuilder?.addQueryParameter(entry.key, entry.value)\n",
        )
        self.assertTrue(
            patcher.patch_kotlin_text(
                path,
                [
                    (
                        "newUrlBuilder?.addQueryParameter(entry.key, entry.value)",
                        "newUrlBuilder.addQueryParameter(entry.key, entry.value)",
                    )
                ],
                check=False,
            )
        )
        self.assertIn("newUrlBuilder.addQueryParameter(entry.key, entry.value)", path.read_text())
        self.assertNotIn("newUrlBuilder?.addQueryParameter", path.read_text())
        patcher.patch_kotlin_text(
            path,
            [
                (
                    "newUrlBuilder?.addQueryParameter(entry.key, entry.value)",
                    "newUrlBuilder.addQueryParameter(entry.key, entry.value)",
                )
            ],
            check=True,
        )

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
