package io.github.azuizui.stash

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.workmanager.EphemeralTaskManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Uploading files edited in Stash (text edits, the office editor, other apps) back over the original,
 * shared by ViewerActivity and OfficeEditorActivity.
 */
object EditUploads {

    class RecentEdit(val file: File, val upload: UUID)

    /**
     * Uploaded edits by remote + path. The viewer shows the local copy instead of the remote one while the
     * upload is pending or has failed: reopening the file before it finishes would fetch the old version.
     */
    val recent = ConcurrentHashMap<String, RecentEdit>()

    fun key(remote: RemoteItem, path: String) = remote.name + path

    /** Uploads [f] into the folder of [item] under the same name, replacing it. */
    fun upload(ctx: Context, remote: RemoteItem, item: FileItem, f: File): UUID {
        val app = ctx.applicationContext
        val target = item.path.trimEnd('/').let { p -> val i = p.lastIndexOf('/'); if (i > 0) p.substring(0, i) else "//${remote.name}" }
        // a native Google Doc/Sheet/Slides is listed with an unknown size (-1): convert on upload
        val googleDoc = remote.isRemoteType(RemoteItem.GOOGLE_DRIVE) && item.size < 0
        val upload = EphemeralTaskManager.queueUpload(app, remote, f.absolutePath, target, googleDoc, explicitSave = true)
        recent[key(remote, item.path)] = RecentEdit(f, upload)
        // say when it is done, also after the screen that started it has closed (uploads often take a second)
        val live = WorkManager.getInstance(app).getWorkInfoByIdLiveData(upload)
        live.observeForever(object : Observer<WorkInfo?> {
            override fun onChanged(value: WorkInfo?) {
                if (value?.state?.isFinished != true) return
                live.removeObserver(this)
                val ok = value.state == WorkInfo.State.SUCCEEDED
                Toast.makeText(app, if (ok) "${item.name} saved to cloud"
                    else "${item.name}: upload failed, edits only on this phone", Toast.LENGTH_LONG).show()
            }
        })
        // the viewer's cached copy of the old version
        File(app.cacheDir, "viewer").listFiles()?.filter { it.name.endsWith("_" + item.name) }?.forEach { it.delete() }
        return upload
    }
}
