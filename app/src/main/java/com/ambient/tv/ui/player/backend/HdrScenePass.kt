package com.ambient.tv.ui.player.backend

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

/** Small, app-private persistent analysis artifacts. Never retain video
 * snapshots here. Exact source SHA, clock, APK/device identity and capture
 * profile separate incompatible plans and native caches. */
internal object HdrSceneReuse {
    private val lock = Any()
    private const val MAX_BYTES = 128L*1024*1024
    private const val MAX_ENTRIES = 12

    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun copyIn(directory: File, name: String, destination: File, limit: Long): Boolean = synchronized(lock) {
        val file = File(directory,name)
        runCatching {
            if (!file.isFile || file.length() !in 1L..limit || destination.exists()) return@synchronized false
            file.copyTo(destination,overwrite=false)
            file.setLastModified(System.currentTimeMillis())
            true
        }.getOrElse { destination.delete(); false }
    }

    fun store(directory: File, name: String, source: File, limit: Long) = synchronized(lock) {
        runCatching {
            if (!source.isFile || source.length() !in 1L..limit || !directory.exists()&&!directory.mkdirs()) return@synchronized
            val tmp = File.createTempFile("analysis-", ".tmp", directory)
            try {
                source.copyTo(tmp,overwrite=true)
                java.nio.file.Files.move(tmp.toPath(),File(directory,name).toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { tmp.delete() }
            val entries = directory.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".plan")||it.name.endsWith(".stats")) }
                .sortedByDescending { it.lastModified() }
            var bytes=0L
            for ((i,entry) in entries.withIndex()) {
                bytes+=entry.length()
                if (i>=MAX_ENTRIES||bytes>MAX_BYTES) entry.delete()
            }
        }
        Unit
    }

    fun invalidate(directory: File, name: String) = synchronized(lock) { File(directory,name).delete() }
}

/**
 * Automatic plan for finite HEVC VOD. Remote media is frozen in a private
 * snapshot for both passes; decoded luma changes complement stable metadata runs.
 * Scene detection is an estimate, not proof of editorial cuts.
 */
internal object HdrScenePlanner {
    private const val MAX_SAMPLE = 16 * 1024 * 1024
    private const val MAX_FRAMES = 1_000_000
    private const val MAX_SCENES = 100_000

    private const val MAX_SNAPSHOT = 64L * 1024 * 1024 * 1024
    private const val FREE_RESERVE = 512L * 1024 * 1024

    fun supportedSource(url: String): Boolean = localFile(url) != null ||
        Uri.parse(url).scheme?.lowercase() in setOf("http","https","content")

    // Existing NAS transports publish seekable HTTP URLs through the file
    // bridge; use the same authenticated resource rather than creating a
    // second SMB/NFS client. Freeze bytes for both passes to prevent a
    // changed network resource being replayed against stale scene metadata.
    private suspend fun snapshot(context: Context, url: String, headers: Map<String,String>, run: File, digest: MessageDigest): File? {
        val uri = Uri.parse(url)
        val file = File(run,"source.media")
        suspend fun copy(input: java.io.InputStream, length: Long): Boolean {
            if (length <= 0 || length > MAX_SNAPSHOT || length > run.usableSpace-FREE_RESERVE) return false
            input.use { stream ->
                file.outputStream().buffered().use { out ->
                    val buffer = ByteArray(1024*1024)
                    var written = 0L
                    while (written < length) {
                        currentCoroutineContext().ensureActive()
                        val n = stream.read(buffer,0,minOf(buffer.size.toLong(),length-written).toInt())
                        if (n <= 0) return false
                        out.write(buffer,0,n);digest.update(buffer,0,n);written += n
                        if (run.usableSpace < FREE_RESERVE) return false
                    }
                    if (stream.read() != -1) return false
                }
            }
            return file.length() == length
        }
        if (uri.scheme == "content") {
            val afd = context.contentResolver.openAssetFileDescriptor(uri,"r") ?: return null
            afd.use {
                val length = it.length
                if (length <= 0) return null
                if (!copy(it.createInputStream(),length)) return null
            }
        } else {
            if (uri.scheme !in setOf("http","https") || !uri.userInfo.isNullOrEmpty()) return null
            val conn = java.net.URL(url).openConnection() as? java.net.HttpURLConnection ?: return null
            try {
                conn.instanceFollowRedirects = false // never forward credentials to a different origin
                conn.connectTimeout = 15_000;conn.readTimeout = 15_000
                conn.requestMethod = "GET"
                for ((name,value) in headers) {
                    if (name.equals("Range",true) || name.equals("Accept-Encoding",true) ||
                        name.equals("Host",true) || name.equals("Connection",true)) continue
                    if (name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) return null
                    conn.setRequestProperty(name,value)
                }
                conn.setRequestProperty("Accept-Encoding","identity")
                if (conn.responseCode != 200 || conn.contentEncoding?.equals("identity",true) == false) return null
                val type = conn.contentType.orEmpty().lowercase()
                if ("mpegurl" in type || "dash+xml" in type || "text/html" in type) return null
                val length = conn.contentLengthLong
                if (length <= 0 || length > MAX_SNAPSHOT || length > run.usableSpace-FREE_RESERVE) return null
                if (!copy(conn.inputStream,length)) return null
            } finally { conn.disconnect() }
        }
        return file
    }

    internal data class Sample(val ptsUs: Long, val metadata: String)
    internal data class Plan(val ends: List<Long>, val source: Double, val target: Double)

    fun localFile(url: String): File? = runCatching {
        val uri = Uri.parse(url)
        val path = when {
            url.startsWith("/") -> url
            uri.scheme == "file" && uri.authority.isNullOrEmpty() -> uri.path
            else -> null
        } ?: return null
        File(path).canonicalFile.takeIf { it.isFile && it.canRead() }
    }.getOrNull()

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return CharArray(bytes.size*2).also { out ->
            bytes.forEachIndexed { i,b ->
                val v = b.toInt() and 255
                out[i*2] = digits[v ushr 4];out[i*2+1] = digits[v and 15]
            }
        }.concatToString()
    }

    // Extract registered HDR10+ SEI from single-layer HEVC. A four-byte
    // length of 256..511 can look like a three-byte Annex B start code.
    // Validate the entire framing, and reject conflicting interpretations.
    internal fun metadata(sample: ByteArray): String? {
        if (sample.size !in 3..MAX_SAMPLE) return null
        data class Parsed(val metadata: String?)
        fun start(i: Int): Int = when {
            i + 3 < sample.size && sample[i] == 0.toByte() && sample[i+1] == 0.toByte() &&
                sample[i+2] == 0.toByte() && sample[i+3] == 1.toByte() -> 4
            i + 2 < sample.size && sample[i] == 0.toByte() && sample[i+1] == 0.toByte() && sample[i+2] == 1.toByte() -> 3
            else -> 0
        }
        fun parse(annex: Boolean): Parsed? {
            var result: String? = null
            val digest = MessageDigest.getInstance("SHA-256")
            fun nal(lo: Int, hi: Int): Boolean {
                if (hi-lo < 2) return false
                val first = sample[lo].toInt() and 255
                val second = sample[lo+1].toInt() and 255
                // Forbidden bit, unsupported nonzero layer ID, invalid temporal ID.
                if (first and 129 != 0 || second and 248 != 0 || second and 7 == 0) return false
                val type = (first ushr 1) and 63
                if (type != 39 && type != 40) return true
                val rbsp = ByteArray(hi-lo-2)
                var n = 0
                var zeros = 0
                for (i in lo+2 until hi) {
                    val v = sample[i].toInt() and 255
                    if (zeros >= 2) {
                        if (v == 3) {
                            if (i+1 >= hi || (sample[i+1].toInt() and 255) > 3) return false
                            zeros = 0
                            continue
                        }
                        if (v < 3) return false // unescaped start-code-like bytes
                    }
                    rbsp[n++] = sample[i]
                    zeros = if (v == 0) zeros+1 else 0
                }
                var p = 0
                fun field(): Int? {
                    var value = 0
                    while (p < n) {
                        val v = rbsp[p++].toInt() and 255
                        if (value > MAX_SAMPLE-v) return null
                        value += v
                        if (v != 255) return value
                    }
                    return null
                }
                while (p < n) {
                    if (rbsp[p] == 0x80.toByte() && p == n-1) return true
                    val kind = field() ?: return false
                    val size = field() ?: return false
                    if (size > n-p) return false
                    if (kind == 4 && size >= 6 &&
                        (rbsp[p].toInt() and 255) == 0xb5 && rbsp[p+1] == 0.toByte() &&
                        rbsp[p+2] == 0x3c.toByte() && rbsp[p+3] == 0.toByte() &&
                        rbsp[p+4] == 1.toByte() && rbsp[p+5] == 4.toByte()) {
                        // Do not silently ignore unsupported versions alongside valid data.
                        if (size !in 7..1024 || rbsp[p+6] !in 0.toByte()..1.toByte()) return false
                        digest.update(rbsp,p,size)
                        val hash = "${rbsp[p+6]}:" + hex(digest.digest())
                        if (result != null && result != hash) return false
                        result = hash
                    }
                    p += size
                }
                return false // Missing rbsp_trailing_bits.
            }
            var i = 0
            while (i < sample.size) {
                if (annex) {
                    val prefix = start(i)
                    if (prefix == 0) return null
                    val lo = i+prefix
                    var end = lo+2
                    while (end < sample.size && start(end) == 0) end++
                    // Annex B permits trailing_zero_8bits between units and at EOF.
                    var hi = end.coerceAtMost(sample.size)
                    while (hi > lo+2 && sample[hi-1] == 0.toByte()) hi--
                    if (!nal(lo,hi)) return null
                    i = end
                } else {
                    if (sample.size-i < 4) return null
                    var len = 0L
                    repeat(4) { len = (len shl 8) or (sample[i++].toLong() and 255L) }
                    if (len < 2 || len > sample.size-i) return null
                    val end = i+len.toInt()
                    if (!nal(i,end)) return null
                    i = end
                }
            }
            return Parsed(result)
        }
        val annex = if (start(0) != 0) parse(true) else null
        val length = parse(false)
        if (annex != null && length != null && annex != length) return null
        return (annex ?: length)?.metadata
    }

    /** Same constant output grid as both adapters. Use the actual PTS of every
     * source sample, not the container duration or rounded movie FPS. */
    internal fun plan(samples: List<Sample>, source: Double, target: Double, vlc: Boolean, contentCuts: Set<Long>? = null): Plan? {
        if (samples.size < 2 || samples.size > MAX_FRAMES || !source.isFinite() || !target.isFinite() ||
            source !in 5.0..120.0 || target <= source || target > 120 || target/source > 5) return null
        val sorted = samples.sortedBy { it.ptsUs }
        val origin = sorted.first().ptsUs
        if (origin < 0 || sorted.last().ptsUs-origin > 86_400_000_000L) return null
        if (contentCuts != null && !sorted.map { it.ptsUs }.toHashSet().containsAll(contentCuts)) return null
        // VLC's var_InheritFloat returns binary32 even though the native
        // scheduler stores the promoted value in a double. Use that exact
        // clock for tick enumeration, retaining the requested target for
        // UI policy and configuration comparisons.
        val clockTarget = if (vlc) target.toFloat().toDouble() else target
        if (clockTarget <= source || clockTarget/source > 5.000001) return null
        val expected = 1_000_000.0/source
        val ends = ArrayList<Long>()
        var tick = 0L
        var previous = 0L
        for (i in sorted.indices) {
            val current = sorted[i].ptsUs-origin
            if (i > 0 && (current <= previous || abs(current-previous-expected) > maxOf(3000.0,expected*.25))) return null
            previous = current
            val end = if (i+1 < sorted.size) sorted[i+1].ptsUs-origin else current+expected.roundToLong()
            val first = tick
            // The native scene schedulers reserve a 1 us boundary tolerance.
            // In particular, rounding the final source duration and then
            // adding it to a rounded PTS can move the endpoint by one tick.
            while (if (vlc) (tick*1_000_000.0/clockTarget).roundToLong() < end-1L
                else tick*1_000_000.0/clockTarget < end-1.0) {
                if (++tick-first > 8 || tick >= 1_000_000_000L) return null
            }
            if (tick == first) return null
            val metadataCut = i+1 < sorted.size && sorted[i].metadata != sorted[i+1].metadata &&
                (contentCuts == null || i>0 && i+2<sorted.size &&
                    sorted[i-1].metadata==sorted[i].metadata && sorted[i+1].metadata==sorted[i+2].metadata)
            if (i+1 == sorted.size || metadataCut || contentCuts?.contains(sorted[i+1].ptsUs) == true) {
                if (ends.size >= MAX_SCENES) return null
                ends += tick
            }
        }
        // Per-frame dynamic metadata cannot identify a stable scene plan.
        if (contentCuts == null && ends.size > maxOf(1, sorted.size/4)) return null
        return Plan(ends,source,target)
    }

    suspend fun scan(context: Context, url: String, headers: Map<String,String>, source: Double, target: Double, vlc: Boolean, directory: File): HdrSceneSession? {
        var created: File? = null
        var delivered = false
        var range: HdrRangeSource? = null
        try {
            val result = withContext(Dispatchers.IO) {
                range = HdrRangeSource.open(context,url,headers)
                val direct = try { range?.let { scanLocal(context,url,headers,source,target,vlc,directory,it) { created = it } } }
                    catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(_:Exception){null}
                if (direct != null) direct else {
                    range?.close();range=null;created?.deleteRecursively();created=null
                    currentCoroutineContext().ensureActive()
                    scanLocal(context,url,headers,source,target,vlc,directory,null) { created = it }
                }
            }
            delivered = result != null
            return result
        } finally {
            // withContext may discard a result if cancellation races dispatch
            // back to the player thread. Never strand the private run directory.
            if (!delivered) { range?.close();created?.deleteRecursively() }
        }
    }

    private suspend fun scanLocal(context: Context, url: String, headers: Map<String,String>, source: Double, target: Double, vlc: Boolean,
        directory: File, range: HdrRangeSource?, created: (File) -> Unit): HdrSceneSession? {
        if (!source.isFinite() || !target.isFinite() || source !in 5.0..120.0 || target <= source || target > 120 || target/source > 5) return null
        if (!directory.exists() && !directory.mkdirs()) return null
        val run = java.nio.file.Files.createTempDirectory(directory.toPath(),"hdr-").toFile()
        created(run)
        if (run.path.any { it in ":,{}%\"\\\n\r" }) return null
        val local = localFile(url)
        val digest = MessageDigest.getInstance("SHA-256")
        val file = local ?: range?.file ?: snapshot(context,url,headers,run,digest) ?: return null
        val length = range?.length ?: file.length()
        val modified = if(range!=null) 0L else file.lastModified()
        fun unchanged() = range?.valid() ?: (file.length()==length && file.lastModified()==modified)
        // Network/content snapshots are hashed during the copy. Local media
        // still needs a whole-file digest to bind both native passes.
        if (local != null) file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024*1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer,0,count)
            }
        }
        val key = range?.key ?: hex(digest.digest())
        val reuse = File(context.cacheDir,"hdr-scene-analysis-v2")
        val apk = File(context.applicationInfo.sourceDir)
        val dynamicAvailable = SvpNative.dynamicSceneAvailable()
        val reuseKey = HdrSceneReuse.key("plan-v3|$dynamicAvailable|$key|${source.toBits()}|${target.toBits()}|$vlc|${android.os.Build.FINGERPRINT}|${apk.length()}|${apk.lastModified()}")
        val planCopy = File(run,"cached-plan")
        val reused = if (HdrSceneReuse.copyIn(reuse,"$reuseKey.plan",planCopy,2_000_000L)) {
            try {
                runCatching {
                    val lines = planCopy.readLines()
                    if (lines.size !in 5..MAX_SCENES+4 || lines[0]!="HDR_PLAN_CACHE_3" || lines[1]!=key) return@runCatching null
                    val version = lines[2].toIntOrNull()?.takeIf { it in 0..1 } ?: return@runCatching null
                    val dynamic = when(lines[3]){"gpu"->true;"decoded"->false;else->return@runCatching null}
                    if(dynamic&&(!dynamicAvailable||version!=1))return@runCatching null
                    val ends = lines.drop(4).map { it.toLongOrNull() ?: return@runCatching null }
                    if (ends.first()<=0||ends.last()>=1_000_000_000L||ends.zipWithNext().any { it.first>=it.second }||
                        version==0&&ends.last()>1_000_000L) return@runCatching null
                    Triple(version,Plan(ends,source,target),dynamic)
                }.getOrNull()
            } finally { planCopy.delete() }
        } else null
        if (reused != null) {
            currentCoroutineContext().ensureActive()
            if (!unchanged()) return null
            val manifest=File(run,"plan")
            manifest.writeText(buildString {
                appendLine(if(reused.third)"AMBIENTSVP_SCENE_PLAN_2" else "AMBIENTSVP_SCENE_PLAN_1");appendLine(key);reused.second.ends.forEach { appendLine(it) }
            })
            return HdrSceneSession(file,length,modified,key,reused.second,manifest,File(run,"cache"),local==null,reused.first,reuse,reuseKey,range,reused.third)
        }
        val samples = ArrayList<Sample>()
        val known = HashMap<String,String>()
        fun accept(pts:Long,bytes:ByteArray,v1Only:Boolean):Boolean{
            if(samples.size>=MAX_FRAMES||bytes.size !in 3 until MAX_SAMPLE)return false
            val hdr=metadata(bytes)?:return false
            if(v1Only&&!hdr.startsWith("1:"))return false
            if(hdr !in known&&known.size>=MAX_SCENES)return false
            samples+=Sample(pts,known.getOrPut(hdr){hdr})
            return true
        }
        // V1's single global window can discover scene cuts in the native
        // statistics pass. Retain the decoded planner for legacy or older
        // native libraries; abort the compressed probe on the first non-V1 sample.
        val dynamic=dynamicAvailable&&HdrContentSceneDetector.scanCompressed(file,range){pts,bytes->accept(pts,bytes,true)}
        val cuts=if(dynamic)emptySet() else {
            samples.clear();known.clear()
            HdrContentSceneDetector.scan(file,range){pts,bytes->accept(pts,bytes,false)}?:return null
        }
        val version = samples.firstOrNull()?.metadata?.substringBefore(':') ?: return null
        if (samples.any { it.metadata.substringBefore(':') != version }) return null
        val plan = plan(samples,source,target,vlc,cuts) ?: return null
        if (version == "0" && plan.ends.last() > 1_000_000L) return null
        currentCoroutineContext().ensureActive()
        if (!unchanged()) return null
        return try {
            val manifest = File(run,"plan")
            manifest.writeText(buildString {
                appendLine(if(dynamic)"AMBIENTSVP_SCENE_PLAN_2" else "AMBIENTSVP_SCENE_PLAN_1");appendLine(key)
                plan.ends.forEach { appendLine(it) }
            })
            planCopy.writeText(buildString {
                appendLine("HDR_PLAN_CACHE_3");appendLine(key);appendLine(version);appendLine(if(dynamic)"gpu" else "decoded");plan.ends.forEach { appendLine(it) }
            })
            HdrSceneReuse.store(reuse,"$reuseKey.plan",planCopy,2_000_000L)
            planCopy.delete()
            HdrSceneSession(file,length,modified,key,plan,manifest,File(run,"cache"),local==null,version.toInt(),reuse,reuseKey,range,dynamic)
        } catch (e: Exception) { run.deleteRecursively();throw e }
    }
}

internal class HdrSceneSession(
    private val sourceFile: File,
    private val sourceLength: Long,
    private val sourceModified: Long,
    val key: String,
    val plan: HdrScenePlanner.Plan,
    private val manifest: File,
    private val cache: File,
    private val staged: Boolean = false,
    private val version: Int = 1,
    private val reuseDirectory: File? = null,
    private val reuseKey: String? = null,
    private val range: HdrRangeSource? = null,
    private val dynamic: Boolean = false,
) {
    // Native retirement uncertainty retains the file/range and scene plan until process exit.
    private companion object {
        val retained = mutableListOf<HdrSceneSession>()
    }
    @Volatile private var retainedByNative = false
    @Synchronized fun quarantine() {
        mode = 0
        if (!retainedByNative) {
            retainedByNative = true
            synchronized(retained) { retained.add(this) }
        }
    }
    @Volatile var mode: Int = 1
        private set
    private fun captureName(profile: String): String? = reuseKey?.let { HdrSceneReuse.key("capture-v2|$it|$profile")+".stats" }
    suspend fun restoreCapture(profile: String): Boolean = withContext(Dispatchers.IO) {
        val dir=reuseDirectory ?: return@withContext false
        val name=captureName(profile) ?: return@withContext false
        if (mode!=1||!valid()||!HdrSceneReuse.copyIn(dir,name,cache,32_000_000L)) return@withContext false
        if (advance()) true else { cache.delete();HdrSceneReuse.invalidate(dir,name);false }
    }
    suspend fun persistCapture(profile: String) = withContext(Dispatchers.IO) {
        val dir=reuseDirectory ?: return@withContext
        val name=captureName(profile) ?: return@withContext
        if (mode==2&&valid()) HdrSceneReuse.store(dir,name,cache,32_000_000L)
    }
    fun analysisPath(): String = sourceFile.path
    fun discardIncompleteCapture() { if (mode == 1) cache.delete() }
    fun playbackPath(): String? = sourceFile.path.takeIf { staged && valid() }
    fun valid() = mode != 0 && (range?.valid() ?: (sourceFile.length() == sourceLength && sourceFile.lastModified() == sourceModified))
    fun advance(): Boolean {
        if (mode != 1 || !valid() || !cache.isFile || cache.length() !in 1L..32_000_000L) return false
        val matching = runCatching {
            // The native writer publishes atomically. Quickly reject an
            // empty/truncated/wrong-source cache before requesting replay;
            // the native second pass validates every interval and profile.
            val header = cache.inputStream().bufferedReader().use {
                it.readLine() == "AMBIENTSVP_SCENE_CACHE_$version" && it.readLine() == key &&
                    !it.readLine().isNullOrBlank() && !it.readLine().isNullOrBlank() &&
                    (version == 1 || plan.ends.last() <= 1_000_000L &&
                        cache.length() > plan.ends.last() * 20 &&
                        it.readLine()?.matches(Regex("G2 [0-9a-f]{16}")) == true)
            }
            if (!header) false else java.io.RandomAccessFile(cache,"r").use { f ->
                val size = minOf(f.length(),4096L).toInt()
                val tail = ByteArray(size)
                f.seek(f.length()-size);f.readFully(tail)
                val line = tail.toString(Charsets.UTF_8).trimEnd().substringAfterLast('\n')
                val fields = line.trim().split(Regex("\\s+"))
                val first = if (plan.ends.size == 1) 0L else plan.ends[plan.ends.lastIndex-1]
                val actualFirst=fields.firstOrNull()?.toLongOrNull()
                if(fields.size<3||actualFirst==null||
                    (if(dynamic)actualFirst !in first until plan.ends.last() else actualFirst!=first)||
                    fields[1].toLongOrNull()!=plan.ends.last())false
                else if (version == 1) (fields.size == 17 || fields.size == 26) && fields[16].toIntOrNull()?.let { it in 1..1000 } == true
                else {
                    val count = fields[2].toIntOrNull() ?: return@use false
                    if (count !in 1..3) return@use false
                    var cursor = 3
                    repeat(count) {
                        val n = fields.getOrNull(cursor)?.toIntOrNull() ?: return@use false
                        if (n !in 0..15 || cursor+6+2*n>fields.size ||
                            fields[cursor+5].toIntOrNull()!=0) return@use false
                        cursor+=6+2*n
                    }
                    cursor==fields.size
                }
            }
        }.getOrDefault(false)
        if (!matching) return false
        mode = 2
        return true
    }
    fun mpvOptions(): String = ":hdr-pass=$mode:hdr-plan=${manifest.path}:hdr-cache=${cache.path}:hdr-key=$key"
    fun vlcOptions(): List<String> = listOf("hdr-pass=$mode","hdr-plan=${manifest.path}","hdr-cache=${cache.path}","hdr-key=$key")
    fun abort() { mode = 0 }
    @Synchronized fun cleanup() { abort(); if (!retainedByNative) { range?.close(); manifest.parentFile?.deleteRecursively() } }
}
