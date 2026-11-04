package com.aide.mobilestudio

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.lang.ref.WeakReference
import java.util.LinkedHashMap

enum class FileIndicator {
    ERROR,      // red - analysis error
    WARNING,    // amber - analysis warning
    MODIFIED,   // blue - unsaved changes
    READONLY,   // gray - read-only file
    FAVORITE    // amber star - starred/bookmarked
}

data class FileItem(
    val file: File,
    val depth: Int,
    val isExpanded: Boolean = false,
    val indicators: Set<FileIndicator> = emptySet()
)

class FileAdapter(
    items: MutableList<FileItem>,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File, Activity) -> Boolean,
    private val onExpandToggle: (FileItem) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    var items: MutableList<FileItem> = items
    private var allItems: List<FileItem> = items.toList()
    private var currentFilter: String = ""
    private var selectedPath: String? = null
    private var openFilePaths: Set<String> = emptySet()

    init {
        setHasStableIds(true)
    }

    fun setSelected(path: String?) {
        val old = selectedPath
        selectedPath = path
        if (old != null) {
            val idx = items.indexOfFirst { it.file.absolutePath == old }
            if (idx >= 0) notifyItemChanged(idx)
        }
        if (path != null) {
            val idx = items.indexOfFirst { it.file.absolutePath == path }
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    fun setOpenFiles(paths: Set<String>) {
        val old = openFilePaths
        openFilePaths = paths
        items.forEachIndexed { idx, item ->
            val wasOpen = item.file.absolutePath in old
            val isOpen = item.file.absolutePath in paths
            if (wasOpen != isOpen) notifyItemChanged(idx)
        }
    }

    fun filter(query: String) {
        currentFilter = query.trim().lowercase()
        if (currentFilter.isEmpty()) {
            updateItems(allItems)
            return
        }
        val filtered = allItems.filter { item ->
            item.file.name.lowercase().contains(currentFilter) ||
                item.file.absolutePath.lowercase().contains(currentFilter)
        }
        items.clear()
        items.addAll(filtered)
        notifyDataSetChanged()
    }

    fun setAllItems(newAllItems: List<FileItem>) {
        allItems = newAllItems
        if (currentFilter.isEmpty()) {
            updateItems(newAllItems)
        } else {
            filter(currentFilter)
        }
    }

    fun updateItems(newItems: List<FileItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) =
                items[o].file.absolutePath == newItems[n].file.absolutePath
            override fun areContentsTheSame(o: Int, n: Int) = items[o].isExpanded == newItems[n].isExpanded &&
                items[o].file.length() == newItems[n].file.length()
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        return items[position].file.absolutePath.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                d(40)
            )
            setPadding(d(4), 0, d(8), 0)
        }

        // Chevron for directories
        val tvChevron = TextView(ctx).apply {
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(d(16), ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            setTextColor(StudioUi.color(StudioUi.Muted))
        }

        // File type icon / thumbnail
        val tvIcon = TextView(ctx).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(d(22), ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }

        val thumb = ImageView(ctx).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(d(20), d(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = d(3).toFloat()
                setColor(StudioUi.color(StudioUi.Surface2))
            }
        }

        // Indentation spacer
        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1)
        }

        val tvName = TextView(ctx).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Indicator container (small colored dots/star)
        val indicatorContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        row.addView(spacer)
        row.addView(tvChevron)
        row.addView(tvIcon)
        row.addView(thumb)
        row.addView(tvName)
        row.addView(indicatorContainer)

        return VH(row, spacer, tvChevron, thumb, tvIcon, tvName, indicatorContainer)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.row.context
        val d = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
        val act = ctx as? Activity

        holder.tvName.text = item.file.name

        // Indentation via spacer width
        val indentPx = d(14) * item.depth
        holder.spacer.layoutParams = holder.spacer.layoutParams.apply { width = indentPx }

        val isSelected = item.file.absolutePath == selectedPath
        val isOpen = item.file.absolutePath in openFilePaths

        if (item.file.isDirectory) {
            // Chevron rotation
            holder.tvChevron.text = "\u25B6"
            holder.tvChevron.visibility = View.VISIBLE
            holder.tvChevron.rotation = if (item.isExpanded) 90f else 0f

            val folderInfo = if (item.isExpanded) FileIcons.forFolder("open") else FileIcons.forFolderName(item.file.name)
            holder.tvIcon.text = folderInfo.symbol
            holder.tvIcon.setTextColor(StudioUi.color(folderInfo.color))
            holder.thumb.visibility = View.GONE
            holder.tvIcon.visibility = View.VISIBLE
            holder.tvName.setTextColor(StudioUi.color(StudioUi.Text))
            holder.tvName.typeface = Typeface.DEFAULT_BOLD
        } else {
            holder.tvChevron.visibility = View.INVISIBLE

            val info = FileIcons.forFile(item.file)
            holder.tvIcon.text = info.symbol
            holder.tvIcon.setTextColor(StudioUi.color(info.color))
            holder.tvName.setTextColor(StudioUi.color(StudioUi.TextSoft))
            holder.tvName.typeface = Typeface.MONOSPACE
            holder.thumb.visibility = View.GONE
            holder.thumb.setImageDrawable(null)
            holder.tvIcon.visibility = View.VISIBLE
            maybeBindThumb(item, holder)
        }

        holder.row.contentDescription =
            (if (item.file.isDirectory) "Folder, " else "File, ") + item.file.name

        // Selection / open file highlight
        when {
            isSelected -> {
                holder.row.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = d(4).toFloat()
                    setColor(StudioUi.palette.selectedBg)
                }
            }
            isOpen && !item.file.isDirectory -> {
                holder.row.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = d(4).toFloat()
                    setColor(StudioUi.color(StudioUi.Surface2) and 0x33FFFFFF.toInt())
                }
            }
            else -> {
                holder.row.background = null
            }
        }

        // Render semantic indicators
        renderIndicators(holder, item)

        holder.row.setOnClickListener {
            if (item.file.isDirectory) onExpandToggle(item)
            else onItemClick(item.file)
        }
        holder.row.setOnLongClickListener {
            if (act != null) showContextMenu(act, holder.row, item)
            true
        }
    }

    private fun showContextMenu(activity: Activity, anchor: View, item: FileItem) {
        val popup = PopupMenu(activity, anchor)
        fun label(s: String) = AppLanguage.literal(activity, s)
        popup.menu.add(0, 1, 0, label("Open"))
        if (item.file.isDirectory) {
            popup.menu.add(0, 2, 1, label("New File"))
            popup.menu.add(0, 3, 2, label("New Folder"))
            popup.menu.add(0, 8, 3, label("Expand All"))
            popup.menu.add(0, 9, 4, label("Collapse All"))
        }
        popup.menu.add(0, 4, 5, label("Rename"))
        popup.menu.add(0, 5, 6, label("Delete"))
        popup.menu.add(0, 6, 7, label("Copy Path"))
        popup.menu.add(0, 7, 8, label("Refresh"))
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> onItemClick(item.file)
                2 -> (activity as? FileOperationsHandler)?.createNewFile(item.file)
                3 -> (activity as? FileOperationsHandler)?.createNewFolder(item.file)
                4 -> (activity as? FileOperationsHandler)?.renameFile(item.file)
                5 -> (activity as? FileOperationsHandler)?.deleteFile(item.file)
                6 -> {
                    val clipboard = activity.getSystemService(Activity.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("path", item.file.absolutePath))
                    android.widget.Toast.makeText(activity, label("Path copied"), android.widget.Toast.LENGTH_SHORT).show()
                }
                7 -> (activity as? FileOperationsHandler)?.refreshFileTree()
                8 -> (activity as? FileOperationsHandler)?.expandAll()
                9 -> (activity as? FileOperationsHandler)?.collapseAll()
            }
            true
        }
        popup.show()
    }

    private fun renderIndicators(holder: VH, item: FileItem) {
        holder.indicatorContainer.removeAllViews()
        val ctx = holder.indicatorContainer.context
        val d = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }

        item.indicators.forEach { indicator ->
            val indicatorView = when (indicator) {
                FileIndicator.ERROR -> createDotIndicator(ctx, d, StudioUi.palette.indicatorError)
                FileIndicator.WARNING -> createDotIndicator(ctx, d, StudioUi.palette.indicatorWarning)
                FileIndicator.MODIFIED -> createDotIndicator(ctx, d, StudioUi.palette.indicatorModified)
                FileIndicator.READONLY -> createLockIndicator(ctx, d, StudioUi.palette.indicatorReadonly)
                FileIndicator.FAVORITE -> createStarIndicator(ctx, d, StudioUi.palette.indicatorFavorite)
            }
            indicatorView.layoutParams = LinearLayout.LayoutParams(d(8), d(8)).apply { marginStart = d(2) }
            holder.indicatorContainer.addView(indicatorView)
        }
    }

    private fun createDotIndicator(ctx: Context, d: (Int) -> Int, color: Int): View {
        return View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    private fun createLockIndicator(ctx: Context, d: (Int) -> Int, color: Int): TextView {
        return TextView(ctx).apply {
            text = "\uD83D\uDD12"
            textSize = 8f
            setTextColor(color)
            gravity = Gravity.CENTER
        }
    }

    private fun createStarIndicator(ctx: Context, d: (Int) -> Int, color: Int): TextView {
        return TextView(ctx).apply {
            text = "\u2B50"
            textSize = 8f
            setTextColor(color)
            gravity = Gravity.CENTER
        }
    }

    private fun maybeBindThumb(item: FileItem, holder: VH) {
        if (item.file.length() > 64L * 1024 * 1024) return
        val kind = MediaDetector.detect(item.file)
        if (!MediaDetector.isMedia(kind)) return
        val key = item.file.absolutePath + "@" + item.file.length()
        val cached = thumbCache[key]?.get()
        if (cached != null && !cached.isRecycled) {
            holder.thumb.setImageBitmap(cached)
            holder.thumb.visibility = View.VISIBLE
            holder.tvIcon.visibility = View.GONE
            return
        }
        Thread {
            val bmp = MediaDetector.thumbnail(item.file, 64)
            val boundPath = item.file.absolutePath
            Handler(Looper.getMainLooper()).post {
                thumbCache.put(key, bmp?.let { WeakReference(it) })
                if (bmp == null) return@post
                val pos = holder.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@post
                val boundItem = items.getOrNull(pos) ?: return@post
                if (boundItem.file.absolutePath != boundPath) return@post
                holder.thumb.setImageBitmap(bmp)
                holder.thumb.visibility = View.VISIBLE
                holder.tvIcon.visibility = View.GONE
            }
        }.start()
    }

    private val thumbCache = object {
        private val map = object : LinkedHashMap<String, WeakReference<android.graphics.Bitmap>>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WeakReference<android.graphics.Bitmap>>) =
                size > 96
        }
        operator fun get(key: String): WeakReference<android.graphics.Bitmap>? = map[key]
        fun put(key: String, ref: WeakReference<android.graphics.Bitmap>?) {
            if (ref != null) map[key] = ref else map.remove(key)
        }
    }

    class VH(
        val row: View,
        val spacer: View,
        val tvChevron: TextView,
        val thumb: ImageView,
        val tvIcon: TextView,
        val tvName: TextView,
        val indicatorContainer: LinearLayout
    ) : RecyclerView.ViewHolder(row)
}

interface FileOperationsHandler {
    fun createNewFile(parent: File)
    fun createNewFolder(parent: File)
    fun renameFile(file: File)
    fun deleteFile(file: File)
    fun refreshFileTree()
    fun expandAll()
    fun collapseAll()
}
