package com.locusable.ginkgo.shelf.cover

import android.graphics.Bitmap
import android.net.Uri
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

private const val MAX_XML = 512 * 1024
private const val MAX_IMAGE = 12 * 1024 * 1024
private val rasterExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

/** Reads EPUB metadata and one embedded image, without opening the book or extracting it to disk. */
internal fun epubCover(file: File, decode: (ByteArray) -> Bitmap?): Bitmap? = withEpubMembers(file) { read ->
    readEpubCover(read, decode)
}

internal fun epubAuthor(file: File): String? = withEpubMembers(file) { read ->
    val (_, metadata) = readEpubPackage(read) ?: return@withEpubMembers null
    val authors = mutableListOf<String>()
    var inMetadata = false
    elements(metadata, namespaces = true) { xml ->
        if (xml.depth == 2) inMetadata = xml.name == "metadata"
        if (inMetadata && xml.depth == 3 && xml.name == "creator" && xml.namespace == "http://purl.org/dc/elements/1.1/") {
            val role = xml.attr("role")
            xml.nextText().trim().takeIf { it.length in 1..256 && (role == null || role == "aut") }?.let { name ->
                if (name !in authors && authors.size < 4) authors.add(name)
            }
        }
    }
    authors.joinToString("、").take(256).ifEmpty { null }
}

private typealias ReadMember = (String, Int) -> ByteArray?

private inline fun <T> withEpubMembers(file: File, block: (ReadMember) -> T): T = ZipFile(file).use { zip ->
    block { path, limit ->
        zip.getEntry(path)?.takeUnless { it.isDirectory || it.size > limit }
            ?.let { entry -> zip.getInputStream(entry).use { it.readBounded(limit) } }
    }
}

private data class EpubItem(val id: String, val path: String, val type: String, val isCover: Boolean)

private fun readEpubPackage(read: ReadMember): Pair<String, ByteArray>? {
    var opf: String? = null
    elements(read("META-INF/container.xml", MAX_XML) ?: return null) { xml ->
        if (xml.name == "rootfile" && opf == null) opf = archivePath("", xml.attr("full-path"))
    }
    val packagePath = opf ?: return null
    return packagePath to (read(packagePath, MAX_XML) ?: return null)
}

private fun readEpubCover(read: ReadMember, decode: (ByteArray) -> Bitmap?): Bitmap? {
    val (packagePath, metadata) = readEpubPackage(read) ?: return null
    val manifest = mutableListOf<EpubItem>()
    var coverId: String? = null
    var reference: String? = null
    elements(metadata) { xml ->
        when (xml.name) {
            "item" -> archivePath(packagePath, xml.attr("href"))?.let { path ->
                val properties = xml.attr("properties").orEmpty().split(Regex("\\s+"))
                manifest += EpubItem(xml.attr("id").orEmpty(), path, xml.attr("media-type").orEmpty(), "cover-image" in properties)
            }
            "meta" -> if (xml.attr("name").equals("cover", ignoreCase = true)) coverId = xml.attr("content")
            "reference" -> if (xml.attr("type").equals("cover", ignoreCase = true)) reference = archivePath(packagePath, xml.attr("href"))
        }
    }
    val namedCover = manifest.firstOrNull {
        it.type.startsWith("image/") && it.path.substringAfterLast('/').lowercase(Locale.ROOT).let { name ->
            name.startsWith("cover") || name.startsWith("frontcover")
        }
    }
    val candidates = listOfNotNull(
        manifest.firstOrNull { it.isCover }?.path,
        manifest.firstOrNull { coverId != null && it.id == coverId }?.path,
        reference,
        namedCover?.path,
    ).distinct()
    for (path in candidates) {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in setOf("html", "htm", "xhtml", "svg")) {
            read(path, MAX_IMAGE)?.let(decode)?.let { return it }
            continue
        }
        val bytes = read(path, MAX_XML) ?: continue
        val linked = linkedImage(path, bytes) ?: continue
        read(linked, MAX_IMAGE)?.let(decode)?.let { return it }
    }
    return null
}

private fun linkedImage(path: String, bytes: ByteArray): String? {
    var result: String? = null
    try {
        elements(bytes) { xml ->
            if (result == null && (xml.name == "img" || xml.name == "image")) {
                val asset = archivePath(path, xml.attr("src") ?: xml.attr("href"))
                if (asset?.substringAfterLast('.')?.lowercase(Locale.ROOT) in rasterExtensions) result = asset
            }
        }
    } catch (_: XmlPullParserException) {
        // Some EPUB 2 cover pages are malformed HTML. Keep any safe image reference already read.
    }
    return result
}

private fun archivePath(base: String, href: String?): String? {
    val decoded = Uri.decode(href?.trim()?.substringBefore('#')?.substringBefore('?') ?: return null)
    if (decoded.isEmpty() || decoded.startsWith("//") || ':' in decoded.substringBefore('/') || '\\' in decoded) return null
    val parts = ArrayDeque<String>()
    if (!decoded.startsWith('/')) base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.forEach(parts::addLast)
    for (part in decoded.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeLast()
            else -> parts.addLast(part)
        }
    }
    return parts.joinToString("/").takeIf(String::isNotEmpty)
}

private inline fun elements(bytes: ByteArray, namespaces: Boolean = false, visit: (XmlPullParser) -> Unit) {
    val xml = Xml.newPullParser().apply {
        if (namespaces) setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(bytes.inputStream(), null)
    }
    while (true) {
        when (xml.next()) {
            XmlPullParser.START_TAG -> visit(xml)
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':') == name) return getAttributeValue(i)
    return null
}

private fun InputStream.readBounded(limit: Int): ByteArray? {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!Thread.currentThread().isInterrupted) {
        val read = read(buffer, 0, minOf(buffer.size, limit - bytes.size() + 1))
        if (read < 0) return bytes.toByteArray()
        if (read > limit - bytes.size()) return null
        bytes.write(buffer, 0, read)
    }
    return null
}
