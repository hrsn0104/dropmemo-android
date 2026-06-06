package com.dropmemo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingText(intent)
        finish()
    }

    private fun handleIncomingText(intent: Intent?) {
        val source = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> Source.SELECT
            Intent.ACTION_SEND -> Source.SHARE
            else -> null
        } ?: return

        val incomingText = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trimEnd()

        if (incomingText.isNullOrBlank()) {
            toast("저장할 텍스트가 없습니다.")
            return
        }

        if (appendMemo(incomingText, source)) {
            toast("DropMemo에 저장되었습니다.")
        }
    }

    private fun appendMemo(text: String, source: Source): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val file = ensureDropMemoFile(prefs) ?: run {
            toast("DropMemo 앱에서 먼저 폴더를 선택해 주세요.")
            return false
        }

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
        if (!writeFile(file, existing + entry)) return false

        prefs.edit().putInt(KEY_SAVE_COUNT, nextCount).apply()
        return true
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private enum class Source { SELECT, SHARE }

    companion object {
        private const val PREFS_NAME = "dropmemo_preferences"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_SAVE_COUNT = "save_count"
        private const val DROP_MEMO_FILE = "DropMemo.md"
    }
}
