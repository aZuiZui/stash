package io.github.azuizui.stash

import android.content.Context
import ca.pkay.rcloneexplorer.BuildConfig
import ca.pkay.rcloneexplorer.Rclone

/**
 * The app has exactly one rclone remote, "teldrive", pointing at the local teldrive server
 * with the NAS rclone.conf settings. Channels are not split into separate remotes.
 */
object TeldriveRemotes {
    const val ROOT = "teldrive"

    private fun options(token: String): MutableList<String> = mutableListOf(
        ROOT, "teldrive",
        "api_host", TeldriveServer.BASE_URL,
        "access_token", token,
        "chunk_size", BuildConfig.TD_RCLONE_CHUNK_SIZE.ifEmpty { "500M" },
        "upload_concurrency", BuildConfig.TD_RCLONE_UPLOAD_CONCURRENCY.ifEmpty { "4" },
        "encrypt_files", BuildConfig.TD_RCLONE_ENCRYPT_FILES.ifEmpty { "false" },
        "random_chunk_name", BuildConfig.TD_RCLONE_RANDOM_CHUNK_NAME.ifEmpty { "true" },
    )

    /** Removes every teldrive remote except [ROOT] (earlier builds made one per channel). */
    fun pruneExtra(rclone: Rclone) {
        rclone.remotes
            .filter { it.typeReadable == "teldrive" && it.name != ROOT }
            .forEach { rclone.deleteRemote(it.name) }
    }

    /** (Re)creates the "teldrive" remote with the new token. */
    fun create(ctx: Context, token: String) {
        val rclone = Rclone(ctx)
        rclone.remotes.filter { it.typeReadable == "teldrive" }.forEach { rclone.deleteRemote(it.name) }
        val p = rclone.configCreate(options(token)) ?: throw IllegalStateException("could not start rclone")
        val err = p.errorStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw IllegalStateException("rclone config create $ROOT: $err")
    }
}
