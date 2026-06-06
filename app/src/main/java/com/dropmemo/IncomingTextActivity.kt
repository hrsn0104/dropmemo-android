package com.dropmemo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IncomingTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingText(intent)
        closeHandlerActivity()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingText(intent)
        closeHandlerActivity()
    }

    private fun handleIncomingText(intent: Intent?) {
        val action = intent?.action ?: return
        val source = when (action) {
            Intent.ACTION_PROCESS_TEXT -> Source.SELECT
            Intent.ACTION_SEND -> Source.SHARE
            else -> null
        } ?: return

        val incomingText = when (action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trimEnd()

        if (incomingText.isNullOrBlank()) {
            toast("저장할 텍스트가 없습니다.")
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (appendMemo(prefs, incomingText, source)) {
            toast("DropMemo에 저장되었습니다.")
        }
    }

    private fun appendMemo(prefs: android.content.SharedPreferences, text: String, source: Source): Boolean = synchronized(SAVE_LOCK) {
        val file = ensureDropMemoFile(prefs) ?: run {
            toast("DropMemo 앱에서 먼저 폴더를 선택해 주세요.")
            return@synchronized false
        }
        if (isDuplicateRequest(prefs, text, source)) return@synchronized false

        val currentCount = prefs.getInt(KEY_SAVE_COUNT, 0)
        val nextCount = currentCount + 1
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            append("## ").append(timestamp).append("\n\n")
            append("- source: ").append(source.name).append("\n")
            append("- count: ").append(nextCount).append("\n\n")
            append(text).append("\n\n---\n")
        }

        val existing = readFile(file)
        if (!writeFile(file, existing + entry)) return@synchronized false

        prefs.edit().putInt(KEY_SAVE_COUNT, nextCount).apply()
        true
    }

    private fun ensureDropMemoFile(prefs: android.content.SharedPreferences): DocumentFile? {
        val treeUri = prefs.getString(KEY_FOLDER_URI, null)?.let(Uri::parse) ?: return null
        val folder = DocumentFile.fromTreeUri(this, treeUri) ?: return null
        if (!folder.canWrite()) return null
        return folder.findFile(DROP_MEMO_FILE) ?: folder.createFile("text/markdown", DROP_MEMO_FILE)
    }

    private fun readFile(file: DocumentFile): String {
        return runCatching {
            contentResolver.openInputStream(file.uri)?.use { input ->
                input.reader(Charsets.UTF_8).use { it.readText() }
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun writeFile(file: DocumentFile, content: String): Boolean {
        return runCatching {
            contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("Cannot open output stream")
            true
        }.getOrElse {
            toast("파일을 저장할 수 없습니다.")
            false
        }
    }

    private fun isDuplicateRequest(prefs: android.content.SharedPreferences, text: String, source: Source): Boolean {
        val now = System.currentTimeMillis()
        val signature = "${source.name}:${text.length}:${text.hashCode()}"
        val lastSignature = prefs.getString(KEY_LAST_INCOMING_SIGNATURE, null)
        val lastTime = prefs.getLong(KEY_LAST_INCOMING_TIME, 0L)
        if (signature == lastSignature && now - lastTime < DUPLICATE_WINDOW_MS) return true

        prefs.edit()
            .putString(KEY_LAST_INCOMING_SIGNATURE, signature)
            .putLong(KEY_LAST_INCOMING_TIME, now)
            .apply()
        return false
    }

    private fun closeHandlerActivity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private enum class Source { SELECT, SHARE }

    companion object {
        private val SAVE_LOCK = Any()
        private const val PREFS_NAME = "dropmemo_preferences"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_SAVE_COUNT = "save_count"
        private const val KEY_LAST_INCOMING_SIGNATURE = "last_incoming_signature"
        private const val KEY_LAST_INCOMING_TIME = "last_incoming_time"
        private const val DUPLICATE_WINDOW_MS = 2_000L
        private const val DROP_MEMO_FILE = "DropMemo.md"
    }
}
