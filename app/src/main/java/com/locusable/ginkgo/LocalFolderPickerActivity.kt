package com.locusable.ginkgo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.Collator
import java.util.concurrent.Executors

class LocalFolderPickerActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: File
    private lateinit var current: File
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LegacyFolders.root()
        val initial = savedInstanceState?.getString("path") ?: intent.getStringExtra(LegacyFolders.EXTRA_LOCAL_DIRECTORY)
        load(initial?.let { LegacyFolders.directory(File(it)) } ?: root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("path", current.path)
    }

    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Back navigates within the local picker on Android 8.1")
    override fun onBackPressed() {
        if (current == root) finish() else goUp()
    }

    private fun goUp() = load(LegacyFolders.directory(current.parentFile ?: root) ?: root)

    private fun load(directory: File) {
        current = directory
        val request = ++generation
        render(null, "正在读取目录…")
        worker.execute {
            val collator = Collator.getInstance()
            val paths = LegacyFolders.directories(directory)?.sortedWith { a, b -> collator.compare(a.name, b.name) }
            runOnUiThread {
                if (isDestroyed || request != generation) return@runOnUiThread
                render(paths, if (paths == null) "无法读取此目录" else "没有下级目录，可选择当前目录")
            }
        }
    }

    private fun render(paths: List<File>?, message: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_INSET_DP), dp(18), dp(PAGE_INSET_DP), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        panel.addView(text("选择目录", PAGE_TITLE_SP).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val path = if (current == root) "内部存储" else "内部存储 / ${current.relativeTo(root).path.replace(File.separator, " / ")}"
        panel.addView(text(path, 13f, SOFT_INK).apply {
            setPadding(0, dp(4), 0, dp(12))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.addView(paperRule(INK), LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        if (current != root) {
            val back = DirectoryRow(back = true).apply {
                bind("返回上一级")
                isFocusable = true
                background = pressBackground()
                setOnClickListener { goUp() }
            }
            panel.addView(back, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        if (paths.isNullOrEmpty()) {
            panel.addView(text(message, 15f, SOFT_INK).apply { setPadding(0, dp(16), 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            val list = ListView(this).apply {
                divider = null
                dividerHeight = 0
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setSelector(paperShape(Color.TRANSPARENT, PRESS_FILL))
                adapter = object : BaseAdapter() {
                    override fun getCount() = paths.size
                    override fun getItem(position: Int) = paths[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val row = convertView as? DirectoryRow ?: DirectoryRow()
                        row.bind(paths[position].name)
                        return row
                    }
                }
                setOnItemClickListener { _, _, position, _ -> load(paths[position]) }
            }
            panel.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(paperButton("取消", primary = false) { finish() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        actions.addView(paperButton("选择此目录", primary = true, enabled = paths != null) {
            setResult(RESULT_OK, Intent().putExtra(LegacyFolders.EXTRA_LOCAL_DIRECTORY, current.path))
            finish()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        panel.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        setContentView(panel)
    }

    private fun text(value: String, size: Float, color: Int = INK) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    private inner class DirectoryRow(back: Boolean = false) : LinearLayout(this@LocalFolderPickerActivity) {
        private val title = text("", 17f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBaselineAligned(false)
            minimumHeight = dp(ACTION_HEIGHT_DP)
            setPadding(dp(4), dp(5), dp(2), dp(5))
            addView(InkGlyph(context, if (back) InkGlyph.Kind.LEFT else InkGlyph.Kind.FILE), LayoutParams(dp(29), dp(30)))
            addView(title, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(11) })
            if (!back) addView(InkGlyph(context, InkGlyph.Kind.RIGHT), LayoutParams(dp(29), dp(30)))
        }

        fun bind(name: String) { title.text = name }
    }
}
