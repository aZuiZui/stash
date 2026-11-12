package io.github.azuizui.stash

import android.content.Context
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.workmanager.EphemeralTaskManager

/**
 * App-wide clipboard for Cut / Copy / Paste in the file explorer. It outlives the explorer for a
 * single remote, so items cut on one remote (or phone storage) can be pasted on any other.
 */
object FileClipboard {

    enum class Mode { COPY, CUT }

    var mode: Mode = Mode.COPY
        private set
    var sourceRemote: RemoteItem? = null
        private set
    private val items = ArrayList<FileItem>()

    @JvmStatic
    fun isEmpty(): Boolean = items.isEmpty() || sourceRemote == null

    @JvmStatic
    fun size(): Int = items.size

    @JvmStatic
    fun set(mode: Mode, remote: RemoteItem, selection: List<FileItem>) {
        this.mode = mode
        sourceRemote = remote
        items.clear()
        items.addAll(selection)
    }

    @JvmStatic
    fun clear() {
        items.clear()
        sourceRemote = null
    }

    /** Short label for the paste bar, e.g. "Cut: 3 items" or "Copied: photo.jpg". */
    @JvmStatic
    fun label(): String {
        val what = if (items.size == 1) items[0].name else "${items.size} items"
        return (if (mode == Mode.CUT) "Cut: " else "Copied: ") + what
    }

    /** Folder that contains [path] ("a/b/c" -> "a/b"; top-level items -> "//remote"). */
    private fun parentOf(remote: RemoteItem, path: String): String {
        val i = path.lastIndexOf('/')
        return if (i > 0 && !path.startsWith("//")) path.substring(0, i) else "//" + remote.name
    }

    /**
     * Queues the clipboard items into folder [dstPath] of [dstRemote]. Items that would land on
     * themselves (same folder, or a folder into itself) are skipped. Clears the clipboard and
     * returns a message for the user.
     */
    @JvmStatic
    fun paste(context: Context, dstRemote: RemoteItem, dstPath: String): String {
        val src = sourceRemote ?: return "Nothing to paste"
        val rclone = Rclone(context)
        val sameRemote = src.name == dstRemote.name
        var queued = 0
        var skipped = 0

        for (item in items) {
            val parent = parentOf(src, item.path)
            if (sameRemote) {
                val intoSelf = item.isDir && (dstPath == item.path || dstPath.startsWith(item.path + "/"))
                if (parent == dstPath || intoSelf) {
                    skipped++
                    continue
                }
            }
            val spec = rclone.remotePathSpec(src, item.path)
            when {
                mode == Mode.COPY ->
                    EphemeralTaskManager.queueCopy(context, dstRemote, spec, item.isDir, dstPath)
                sameRemote ->
                    // server-side rename where the backend supports it
                    EphemeralTaskManager.queueMove(context, dstRemote, dstPath, item, parent)
                else ->
                    EphemeralTaskManager.queueCrossMove(context, dstRemote, spec, item.isDir,
                        dstPath, src.name, parent)
            }
            queued++
        }

        val verb = if (mode == Mode.CUT) "Moving" else "Copying"
        val message = when {
            queued == 0 -> "Already in this folder"
            skipped > 0 -> "$verb $queued here ($skipped already in this folder)"
            else -> "$verb $queued " + (if (queued == 1) "item" else "items") + " here"
        }
        if (queued > 0) {
            clear()
        }
        return message
    }
}
