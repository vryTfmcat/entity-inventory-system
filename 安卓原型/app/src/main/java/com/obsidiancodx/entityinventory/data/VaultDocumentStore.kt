package com.obsidiancodx.entityinventory.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

data class VaultFile(
    val document: DocumentFile,
    val relativePath: String,
    val raw: String,
    val hash: String,
    val modifiedAt: Long
)

class VaultDocumentStore(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun persistPermission(treeUri: Uri) {
        resolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    fun entityRoot(treeUri: Uri): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("无法打开所选目录")
        return if (root.name == "50_实体") root else {
            root.findFile("50_实体")?.takeIf { it.isDirectory }
                ?: error("所选目录中没有 50_实体，请选择 Obsidian 库根目录或 50_实体")
        }
    }

    fun scanMarkdown(treeUri: Uri): List<VaultFile> {
        val root = entityRoot(treeUri)
        val output = mutableListOf<VaultFile>()
        walk(root, "", output)
        return output
    }

    fun findMarkdown(treeUri: Uri, relativePath: String): VaultFile {
        val segments = relativePath.replace('\\', '/').split('/').filter(String::isNotBlank)
        require(segments.isNotEmpty()) { "空的实体路径" }
        var current = entityRoot(treeUri)
        segments.dropLast(1).forEach { segment ->
            current = current.findFile(segment)?.takeIf { it.isDirectory }
                ?: error("实体目录不存在：$relativePath")
        }
        val document = current.findFile(segments.last())?.takeIf { it.isFile }
            ?: error("实体文件不存在：$relativePath")
        return read(document, relativePath)
    }

    fun read(document: DocumentFile, relativePath: String): VaultFile {
        val raw = resolver.openInputStream(document.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("无法读取 $relativePath")
        return VaultFile(document, relativePath, raw, sha256(raw), document.lastModified())
    }

    fun createMarkdown(
        treeUri: Uri,
        directorySegments: List<String>,
        fileName: String,
        content: String
    ): VaultFile {
        var directory = entityRoot(treeUri)
        for (segment in directorySegments) {
            directory = directory.findFile(segment)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(segment)
                ?: error("无法创建目录 $segment")
        }
        require(directory.findFile(fileName) == null) { "$fileName 已存在" }
        val document = directory.createFile("text/markdown", fileName.removeSuffix(".md"))
            ?: error("无法创建 $fileName")
        writeRaw(document, content)
        val relative = (directorySegments + fileName).joinToString("/")
        return read(document, relative)
    }

    fun writeAttachment(
        treeUri: Uri,
        directorySegments: List<String>,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri {
        var directory = entityRoot(treeUri)
        for (segment in directorySegments) {
            directory = directory.findFile(segment)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(segment)
                ?: error("无法创建目录 $segment")
        }
        val document = directory.findFile(fileName)
            ?: directory.createFile(mimeType, fileName.substringBeforeLast('.', fileName))
            ?: error("无法创建附件 $fileName")
        resolver.openOutputStream(document.uri, "wt")?.use { it.write(bytes) }
            ?: error("无法写入附件 $fileName")
        return document.uri
    }

    fun updateMarkdown(expected: VaultFile, newContent: String): VaultFile {
        val current = read(expected.document, expected.relativePath)
        check(current.hash == expected.hash) {
            "${expected.relativePath} 已在 Obsidian 中被修改，已停止覆盖"
        }
        writeRaw(expected.document, newContent)
        return read(expected.document, expected.relativePath)
    }

    private fun writeRaw(document: DocumentFile, content: String) {
        resolver.openOutputStream(document.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(content.replace("\r\n", "\n"))
        } ?: error("无法写入 ${document.name}")
    }

    private fun walk(directory: DocumentFile, prefix: String, output: MutableList<VaultFile>) {
        directory.listFiles().sortedBy { it.name }.forEach { child ->
            val name = child.name ?: return@forEach
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            when {
                child.isDirectory -> walk(child, relative, output)
                child.isFile && name.endsWith(".md", ignoreCase = true) -> output += read(child, relative)
            }
        }
    }

    companion object {
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun safeFileName(value: String): String = value
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .trim().ifBlank { "未命名物品" }
    }
}
