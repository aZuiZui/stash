package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import android.os.Parcel
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import de.felixnuesse.extract.notifications.implementations.DeleteWorkerNotification
import de.felixnuesse.extract.notifications.implementations.DownloadWorkerNotification
import de.felixnuesse.extract.notifications.implementations.MoveWorkerNotification
import de.felixnuesse.extract.notifications.implementations.UploadWorkerNotification

class EphemeralTaskManager(private var mContext: Context) {

    companion object {

        fun queueDownload(
            context: Context,
            remote: RemoteItem,
            downloadItem: FileItem,
            selectedPath: String) {

            DownloadWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.DOWNLOAD.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.DOWNLOAD_TARGETPATH, selectedPath)

            addFileItemToData(EphemeralWorker.DOWNLOAD_SOURCE, downloadItem, data)
            EphemeralTaskManager(context).work(data.build(), "")
        }

        @JvmOverloads
        fun queueUpload(
            context: Context,
            remote: RemoteItem,
            file: String,
            targetpath: String,
            importDocs: Boolean = false,
            explicitSave: Boolean = false): UUID {

            UploadWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.UPLOAD.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.UPLOAD_TARGETPATH, targetpath)
            data.putString(EphemeralWorker.UPLOAD_FILE, file)
            data.putBoolean(EphemeralWorker.UPLOAD_IMPORT_DOCS, importDocs)
            data.putBoolean(EphemeralWorker.UPLOAD_EXPLICIT_SAVE, explicitSave)

            return EphemeralTaskManager(context).work(data.build(), "")
        }

        /** "Send to": copy [sourceSpec] (rclone path on any remote) into [targetpath] of [remote]. */
        fun queueCopy(
            context: Context,
            remote: RemoteItem,
            sourceSpec: String,
            isDir: Boolean,
            targetpath: String) {

            UploadWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.UPLOAD.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.UPLOAD_TARGETPATH, targetpath)
            data.putString(EphemeralWorker.UPLOAD_FILE, sourceSpec)
            data.putString(EphemeralWorker.UPLOAD_SOURCE_SPEC, sourceSpec)
            data.putBoolean(EphemeralWorker.UPLOAD_SOURCE_IS_DIR, isDir)

            EphemeralTaskManager(context).work(data.build(), "")
        }

        /**
         * Cut and paste across remotes: move [sourceSpec] (rclone path on [sourceRemoteName]) into
         * [targetpath] of [remote]. [sourceParent] is the folder it came from, refreshed when done.
         */
        fun queueCrossMove(
            context: Context,
            remote: RemoteItem,
            sourceSpec: String,
            isDir: Boolean,
            targetpath: String,
            sourceRemoteName: String,
            sourceParent: String) {

            MoveWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.UPLOAD.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.UPLOAD_TARGETPATH, targetpath)
            data.putString(EphemeralWorker.UPLOAD_FILE, sourceSpec)
            data.putString(EphemeralWorker.UPLOAD_SOURCE_SPEC, sourceSpec)
            data.putBoolean(EphemeralWorker.UPLOAD_SOURCE_IS_DIR, isDir)
            data.putBoolean(EphemeralWorker.UPLOAD_IS_MOVE, true)
            data.putString(EphemeralWorker.UPLOAD_SOURCE_REMOTE, sourceRemoteName)
            data.putString(EphemeralWorker.UPLOAD_SOURCE_PARENT, sourceParent)

            EphemeralTaskManager(context).work(data.build(), "")
        }

        fun queueMove(
            context: Context,
            remote: RemoteItem,
            currentPath: String,
            file: FileItem,
            readablePath: String
        ) {

            MoveWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.MOVE.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            addFileItemToData(EphemeralWorker.MOVE_FILE, file, data)
            data.putString(EphemeralWorker.MOVE_TARGETPATH, currentPath)
            // Pass the source path (where the file came from) so the UI can refresh it too
            data.putString(EphemeralWorker.MOVE_SOURCEPATH, readablePath)
            EphemeralTaskManager(context).work(data.build(), "")
        }

        fun queueDelete(
            context: Context,
            remote: RemoteItem,
            file: FileItem,
            currentPath: String
        ) {

            DeleteWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.DELETE.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            addFileItemToData(EphemeralWorker.DELETE_FILE, file, data)
            // Pass the current directory path so the UI knows which folder to refresh
            data.putString(EphemeralWorker.DELETE_CURRENTPATH, currentPath)
            EphemeralTaskManager(context).work(data.build(), "")
        }

        private fun addFileItemToData(key: String, fileItem: FileItem, data: Data.Builder){
            val parcel = Parcel.obtain()
            fileItem.writeToParcel(parcel, 0)
            data.putByteArray(key, parcel.marshall())
        }

        private fun addRemoteItemToData(key: String, remote: RemoteItem, data: Data.Builder){
            val parcel = Parcel.obtain()
            remote.writeToParcel(parcel, 0)
            data.putByteArray(key, parcel.marshall())
        }
    }


    protected fun work(inputData: Data, tag: String): UUID {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<EphemeralWorker>()
        uploadWorkRequest.setInputData(inputData)
        uploadWorkRequest.addTag(tag)
        val request = uploadWorkRequest.build()
        WorkManager.getInstance(mContext).enqueue(request)
        return request.id
    }

    fun cancel() {
        WorkManager.getInstance(mContext)
            .cancelAllWork()
    }
    fun cancel(tag: String) {

        //Intent syncIntent = new Intent(context, SyncService.class);
        //syncIntent.setAction(TASK_CANCEL_ACTION);
        //syncIntent.putExtra(EXTRA_TASK_ID, intent.getLongExtra(EXTRA_TASK_ID, -1));
        //context.startService(syncIntent);
        Log.e("TAG", "CANCEL"+tag)
        WorkManager
            .getInstance(mContext)
            .cancelAllWorkByTag(tag)
    }
}