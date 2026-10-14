import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import patch_smarttube_compat as patcher


class SmartTubeDeprecationPatchesTest(unittest.TestCase):
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
