package com.dropmemo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var vaultEdit: EditText
    private lateinit var filePathEdit: EditText
    private lateinit var folderStatusText: TextView
    private lateinit var saveCountText: TextView
    private lateinit var memoEdit: EditText
    private lateinit var previewText: TextView

    private var folderUri: Uri? = null
    private var saveCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        folderUri = prefs.getString(KEY_FOLDER_URI, null)?.let(Uri::parse)
        saveCount = prefs.getInt(KEY_SAVE_COUNT, 0)

        buildUi()
        loadPreferencesIntoUi()
        updateFolderStatus()
        refreshPreview()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_FOLDER && resultCode == RESULT_OK) {
            val pickedUri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(pickedUri, flags)
            folderUri = pickedUri
            prefs.edit().putString(KEY_FOLDER_URI, pickedUri.toString()).apply()
            updateFolderStatus()
            ensureDropMemoFile()
            refreshPreview()
            toast("폴더가 선택되었습니다.")
        }
    }

    private fun buildUi() {
        val outerScroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        outerScroll.addView(root)

        root.addView(TextView(this).apply {
            text = "DropMemo"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        })

        vaultEdit = editText("Obsidian Vault name")
        root.addView(label("Vault 이름"))
        root.addView(vaultEdit)

        filePathEdit = editText("00_Inbox/DropMemo")
        root.addView(label("Vault 안 파일 경로 (.md 제외)"))
        root.addView(filePathEdit)

        folderStatusText = TextView(this).apply { textSize = 14f }
        root.addView(folderStatusText)

        saveCountText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(saveCountText)

        root.addView(Button(this).apply {
            text = "폴더 선택"
            setOnClickListener { chooseFolder() }
        })

        root.addView(label("메모"))
        memoEdit = editText("저장할 텍스트를 입력하세요.", minLines = 5).apply {
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(memoEdit)

        root.addView(Button(this).apply {
            text = "저장"
            setOnClickListener { saveManualText() }
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        buttonRow.addView(Button(this).apply {
            text = "리셋"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { resetFile() }
        })
        buttonRow.addView(Button(this).apply {
            text = "옵시디언에서 열기"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener { openInObsidian() }
        })
        root.addView(buttonRow)

        root.addView(TextView(this).apply {
            text = "DropMemo.md 바로보기"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
        })

        val previewScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
            )
            setBackgroundColor(0xFFF5F5F5.toInt())
            isFillViewport = true
        }
        previewText = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        previewScroll.addView(previewText)
        root.addView(previewScroll)

        setContentView(outerScroll)
        addPreferenceWatchers()
    }

    private fun loadPreferencesIntoUi() {
        vaultEdit.setText(prefs.getString(KEY_VAULT_NAME, ""))
        filePathEdit.setText(prefs.getString(KEY_FILE_PATH, "00_Inbox/DropMemo"))
        updateSaveCountText()
    }

    private fun addPreferenceWatchers() {
        vaultEdit.addTextChangedListener(preferenceWatcher(KEY_VAULT_NAME))
        filePathEdit.addTextChangedListener(preferenceWatcher(KEY_FILE_PATH))
    }

    private fun preferenceWatcher(key: String): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            prefs.edit().putString(key, s?.toString().orEmpty()).apply()
        }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val incomingText = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> if (intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT) else null
            else -> null
        }?.trimEnd()

        if (incomingText.isNullOrBlank()) return

        val source = if (intent?.action == Intent.ACTION_PROCESS_TEXT) Source.SELECT else Source.SHARE
        if (folderUri == null) {
            memoEdit.setText(incomingText)
            toast("먼저 폴더를 선택해 주세요.")
            return
        }
        appendMemo(incomingText, source)
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_FOLDER)
    }

    private fun saveManualText() {
        val text = memoEdit.text?.toString().orEmpty().trimEnd()
        if (text.isBlank()) {
            toast("저장할 메모를 입력하세요.")
            return
        }
        if (appendMemo(text, Source.MANUAL)) {
            memoEdit.text?.clear()
        }
    }

    private fun appendMemo(text: String, source: Source): Boolean {
        val file = ensureDropMemoFile() ?: run {
            toast("DropMemo.md를 만들 폴더를 선택해 주세요.")
            return false
        }
        val nextCount = saveCount + 1
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            append("## ").append(timestamp).append("\n\n")
            append("- source: ").append(source.name).append("\n")
            append("- count: ").append(nextCount).append("\n\n")
            append(text).append("\n\n---\n")
        }
        val existing = readFile(file)
        if (!writeFile(file, existing + entry)) return false

        saveCount = nextCount
        prefs.edit().putInt(KEY_SAVE_COUNT, saveCount).apply()
        updateSaveCountText()
        refreshPreview()
        toast("저장되었습니다.")
        return true
    }

    private fun resetFile() {
        val file = ensureDropMemoFile() ?: run {
            toast("초기화할 폴더를 선택해 주세요.")
            return
        }
        if (writeFile(file, "")) {
            saveCount = 0
            prefs.edit().putInt(KEY_SAVE_COUNT, saveCount).apply()
            updateSaveCountText()
            refreshPreview()
            toast("DropMemo.md를 비웠습니다.")
        }
    }

    private fun openInObsidian() {
        val vaultName = vaultEdit.text?.toString().orEmpty().trim()
        val filePath = filePathWithoutMd()
        if (vaultName.isBlank() || filePath.isBlank()) {
            toast("Vault 이름과 파일 경로를 입력해 주세요.")
            return
        }
        val uri = Uri.parse("obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(filePath)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Obsidian 앱을 열 수 없습니다.")
        }
    }

    private fun ensureDropMemoFile(): DocumentFile? {
        val treeUri = folderUri ?: return null
        val folder = DocumentFile.fromTreeUri(this, treeUri) ?: return null
        if (!folder.canWrite()) return null
        return folder.findFile(DROP_MEMO_FILE) ?: folder.createFile("text/markdown", DROP_MEMO_FILE)
    }

    private fun refreshPreview() {
        val file = ensureDropMemoFile()
        previewText.text = if (file == null) {
            "폴더를 선택하면 DropMemo.md 내용이 여기에 표시됩니다."
        } else {
            readFile(file).ifBlank { "DropMemo.md가 비어 있습니다." }
        }
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

    private fun updateFolderStatus() {
        folderStatusText.text = if (folderUri == null) {
            "폴더 상태: 선택되지 않음"
        } else {
            "폴더 상태: 선택됨"
        }
    }

    private fun updateSaveCountText() {
        saveCountText.text = "Save count: $saveCount"
    }

    private fun editText(hintText: String, minLines: Int = 1): EditText {
        return EditText(this).apply {
            hint = hintText
            setSingleLine(minLines == 1)
            this.minLines = minLines
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun label(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, 0)
        }
    }

    private fun filePathWithoutMd(): String = filePathEdit.text?.toString().orEmpty().trim().removeSuffix(".md")

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Source { MANUAL, SELECT, SHARE }

    companion object {
        private const val PREFS_NAME = "dropmemo_preferences"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_VAULT_NAME = "vault_name"
        private const val KEY_FILE_PATH = "file_path"
        private const val KEY_SAVE_COUNT = "save_count"
        private const val DROP_MEMO_FILE = "DropMemo.md"
        private const val REQUEST_OPEN_FOLDER = 1001
    }
}
