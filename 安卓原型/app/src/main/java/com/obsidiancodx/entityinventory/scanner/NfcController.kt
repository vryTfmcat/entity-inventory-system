package com.obsidiancodx.entityinventory.scanner

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.obsidiancodx.entityinventory.core.TagPayloadCodec
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class NfcController(
    private val activity: Activity,
    private val onScan: (tagId: String, uidHash: String) -> Unit,
    private val onWriteVerified: (tagId: String, uidHash: String) -> Unit,
    private val onMessage: (String) -> Unit
) : NfcAdapter.ReaderCallback {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val pendingWrite = AtomicReference<WriteRequest?>(null)

    data class WriteRequest(val tagId: String, val phone: String, val itemName: String)

    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun enable() {
        adapter?.enableReaderMode(
            activity,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null
        )
    }

    fun disable() {
        adapter?.disableReaderMode(activity)
    }

    fun armWrite(tagId: String, phone: String, itemName: String) {
        TagPayloadCodec.createNdef(tagId, phone, itemName, activity.packageName)
        pendingWrite.set(WriteRequest(tagId, phone, itemName))
        post("NFC 写入已就绪，请把手机贴近目标标签")
    }

    fun cancelWrite() {
        pendingWrite.set(null)
        post("已取消 NFC 写入")
    }

    @Suppress("DEPRECATION")
    fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action !in setOf(NfcAdapter.ACTION_NDEF_DISCOVERED, NfcAdapter.ACTION_TAG_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED)) return false
        val messages = intent?.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?.mapNotNull { it as? NdefMessage }
            .orEmpty()
        val tagId = messages.firstNotNullOfOrNull(TagPayloadCodec::decodeNdef) ?: return false
        val tag = intent?.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        val uidHash = tag?.id?.let(::hashUid).orEmpty()
        activity.runOnUiThread { onScan(tagId, uidHash) }
        return true
    }

    override fun onTagDiscovered(tag: Tag) {
        val uidHash = hashUid(tag.id)
        val write = pendingWrite.getAndSet(null)
        if (write != null) {
            runCatching { writeTag(tag, write) }
                .onSuccess { verified ->
                    if (verified) {
                        activity.runOnUiThread { onWriteVerified(write.tagId, uidHash) }
                        post("NFC 写入并读回成功，正在激活绑定")
                    } else {
                        post("NFC 已格式化写入；请移开标签后再扫一次完成读回验证")
                    }
                }
                .onFailure { post("NFC 写入失败：${it.message}") }
            return
        }
        runCatching {
            val ndef = Ndef.get(tag) ?: error("标签不是 NDEF 格式")
            ndef.connect()
            try {
                val message = ndef.ndefMessage ?: ndef.cachedNdefMessage ?: error("标签没有 NDEF 内容")
                val tagId = TagPayloadCodec.decodeNdef(message) ?: error("没有找到实体盘点 tagId")
                activity.runOnUiThread { onScan(tagId, uidHash) }
            } finally {
                ndef.close()
            }
        }.onFailure { post("NFC 读取失败：${it.message}") }
    }

    private fun writeTag(tag: Tag, request: WriteRequest): Boolean {
        val message = TagPayloadCodec.createNdef(request.tagId, request.phone, request.itemName, activity.packageName)
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                check(ndef.isWritable) { "标签已锁定或只读" }
                check(ndef.maxSize >= message.toByteArray().size) {
                    "标签容量不足：需要 ${message.toByteArray().size} 字节，可用 ${ndef.maxSize} 字节；可缩短物品名称或换用 NTAG215/216"
                }
                ndef.writeNdefMessage(message)
                val readBack = ndef.ndefMessage ?: error("无法读回标签")
                check(TagPayloadCodec.decodeNdef(readBack) == request.tagId) { "读回 tagId 不一致" }
            } finally {
                ndef.close()
            }
            return true
        }
        val formatable = NdefFormatable.get(tag) ?: error("标签不支持 NDEF 格式化")
        formatable.connect()
        try {
            formatable.format(message)
        } finally {
            formatable.close()
        }
        return false
    }

    private fun post(message: String) = activity.runOnUiThread { onMessage(message) }

    private fun hashUid(uid: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(uid)
        .joinToString("") { "%02x".format(it) }
}
