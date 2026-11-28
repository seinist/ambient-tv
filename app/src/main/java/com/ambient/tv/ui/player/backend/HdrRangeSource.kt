package com.ambient.tv.ui.player.backend

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seekable, read-only virtual media file. Every missing block is fetched
 * conditionally against the same strong entity validator. Never retry a
 * different representation, redirect credentials, or silently accept 200.
 * Servers without a strong validator/range support use the snapshot route.
 */
internal class HdrRangeSource private constructor(
    private val url: URL, private val headers: Map<String,String>,
    val length: Long, private val etag: String, val key: String,
) : AutoCloseable {
    private val failed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val worker = HandlerThread("hdr-range").apply { start() }
    private var descriptor: ParcelFileDescriptor? = null
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long,ByteArray>?): Boolean = size > 8
    }
    val fd get() = checkNotNull(descriptor).fileDescriptor
    val file get() = File("/proc/self/fd/${checkNotNull(descriptor).fd}")
    fun valid() = !failed.get() && !closed.get()
    private fun readBlock(index: Long): ByteArray {
        blocks[index]?.let { return it }
        val start=index*BLOCK
        val end=minOf(length-1,start+BLOCK-1)
        val conn=connection(url,headers,start,end,etag)
        try {
            if (conn.responseCode!=206 || conn.getHeaderField("ETag")!=etag ||
                !identity(conn) || !matchesRange(conn.getHeaderField("Content-Range"),start,end,length) ||
                conn.contentLengthLong!=end-start+1) throw java.io.IOException("HDR range identity changed")
            val bytes=ByteArray((end-start+1).toInt())
            conn.inputStream.use { input ->
                var pos=0
                while(pos<bytes.size){
                    if(closed.get()) throw java.io.IOException("HDR range closed")
                    val n=input.read(bytes,pos,bytes.size-pos)
                    if(n<=0) throw java.io.EOFException("HDR range truncated")
                    pos+=n
                }
                if(input.read()!=-1) throw java.io.IOException("HDR range oversized")
            }
            blocks[index]=bytes
            return bytes
        } finally { conn.disconnect() }
    }
    private fun attach(context: Context) {
        val storage=context.getSystemService(StorageManager::class.java) ?: error("No StorageManager")
        descriptor=storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,object:ProxyFileDescriptorCallback(){
            override fun onGetSize(): Long = length
            override fun onRead(offset: Long,size: Int,data: ByteArray): Int {
                if(!valid()||offset<0||size<0||size>data.size) throw ErrnoException("hdr-read",OsConstants.EIO)
                if(offset>=length||size==0)return 0
                val count=minOf(size.toLong(),length-offset).toInt()
                try {
                    var written=0
                    while(written<count){
                        val at=offset+written
                        val block=readBlock(at/BLOCK)
                        val within=(at%BLOCK).toInt()
                        val n=minOf(count-written,block.size-within)
                        if(n<=0) throw java.io.EOFException("HDR block bounds")
                        block.copyInto(data,written,within,within+n);written+=n
                    }
                    return written
                } catch (_:Exception){
                    failed.set(true)
                    throw ErrnoException("hdr-read",OsConstants.EIO)
                }
            }
            override fun onRelease(){ failed.set(true);blocks.clear() }
        },Handler(worker.looper))
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        runCatching { descriptor?.close() }
        worker.quitSafely()
    }
    companion object {
        private const val BLOCK=1024L*1024
        private val rangePattern=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        internal fun strongTag(tag:String?):Boolean = tag!=null&&tag.length in 2..1024&&
            tag.first()=='"'&&tag.last()=='"'&&tag.substring(1,tag.length-1).all { it.code==0x21||it.code in 0x23..0x7e||it.code in 0x80..0xff }
        internal fun matchesRange(value:String?,start:Long,end:Long,total:Long):Boolean{
            val m=rangePattern.matchEntire(value.orEmpty())?:return false
            return start>=0&&end>=start&&total>end&&m.groupValues[1].toLongOrNull()==start&&
                m.groupValues[2].toLongOrNull()==end&&m.groupValues[3].toLongOrNull()==total
        }
        private fun identity(c:HttpURLConnection)=c.contentEncoding?.equals("identity",true)!=false
        private fun connection(url:URL,headers:Map<String,String>,start:Long,end:Long,etag:String?):HttpURLConnection{
            val c=url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects=false;c.connectTimeout=10_000;c.readTimeout=10_000
            c.requestMethod="GET";c.useCaches=false
            for((name,value) in headers){
                if(name.lowercase() in setOf("range","accept-encoding","host","connection","if-match","if-none-match","if-range","if-modified-since","if-unmodified-since"))continue
                require(name.none { it=='\r'||it=='\n' }&&value.none { it=='\r'||it=='\n' })
                c.setRequestProperty(name,value)
            }
            c.setRequestProperty("Accept-Encoding","identity")
            c.setRequestProperty("Range","bytes=$start-$end")
            if(etag!=null)c.setRequestProperty("If-Match",etag)
            return c
        }
        fun open(context:Context,source:String,headers:Map<String,String>):HdrRangeSource?{
            var result:HdrRangeSource?=null
            try {
                val url=URL(source)
                if(url.protocol !in setOf("http","https")||url.userInfo!=null)return null
                val c=connection(url,headers,0,0,null)
                val tag:String
                val length:Long
                try {
                    if(c.responseCode!=206||!identity(c)||c.contentLengthLong!=1L)return null
                    tag=c.getHeaderField("ETag")?.takeIf(::strongTag)?:return null
                    val range=c.getHeaderField("Content-Range")
                    length=rangePattern.matchEntire(range.orEmpty())?.groupValues?.get(3)?.toLongOrNull()?:return null
                    if(!matchesRange(range,0,0,length)||length>64L*1024*1024*1024)return null
                    val type=c.contentType.orEmpty().lowercase()
                    if("mpegurl" in type||"dash+xml" in type||"text/html" in type)return null
                    c.inputStream.use { if(it.read()<0||it.read()!=-1)return null }
                } finally { c.disconnect() }
                // Bind credentials/representation selectors as well as URI,
                // length and strong validator. Only the digest is persisted.
                val identity=buildString{
                    fun part(v:String){append(v.length);append(':');append(v)}
                    part("range-v1");part(source);part(tag);part(length.toString())
                    headers.toSortedMap().forEach { (k,v)->part(k);part(v) }
                }
                val opened=HdrRangeSource(url,headers.toMap(),length,tag,HdrSceneReuse.key(identity))
                result=opened
                opened.attach(context)
                // Verify that native same-process file opening works on this
                // device before advertising the descriptor to either player.
                java.io.RandomAccessFile(opened.file,"r").use { f ->
                    if(f.length()!=length||f.read()<0)return null
                    f.seek(length-1)
                    if(f.read()<0)return null
                }
                result=null
                return opened
            } catch (_:Exception){return null}
            finally { result?.close() }
        }
    }
}
