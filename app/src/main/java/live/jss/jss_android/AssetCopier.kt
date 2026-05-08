package live.jss.jss_android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * On first launch (or after an app update) we copy the JSS bundle out of
 * APK assets into the app's writable filesDir, because Node needs to write
 * to its own project (for caches, accounts/, etc.). We track the build's
 * lastUpdateTime in a stamp file and skip the copy on subsequent launches
 * when nothing has changed — saves ~10 s on cold start.
 *
 * Pattern lifted (with attribution) from nodejs-mobile-react-native's
 * RNNodeJsMobileModule.java assetCopy routine. MIT licensed; covered by
 * AGPL on aggregation.
 */
object AssetCopier {

    private const val TAG = "AssetCopier"
    private const val STAMP_FILE = ".asset-copy-stamp"

    /**
     * Ensure [destRoot] mirrors `assets/<assetSubpath>` from the APK.
     * Returns the absolute path to the copied tree.
     */
    fun ensureAssets(context: Context, assetSubpath: String, destRoot: File): File {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val currentStamp = "${pkgInfo.versionName}@${pkgInfo.lastUpdateTime}"

        val stampFile = File(destRoot, STAMP_FILE)
        if (stampFile.exists() && stampFile.readText() == currentStamp) {
            Log.i(TAG, "Assets already up to date for $currentStamp; skip")
            return destRoot
        }

        Log.i(TAG, "Copying assets/$assetSubpath -> $destRoot (stamp: $currentStamp)")
        if (destRoot.exists()) destRoot.deleteRecursively()
        destRoot.mkdirs()

        copyDir(context, assetSubpath, destRoot)
        stampFile.writeText(currentStamp)

        Log.i(TAG, "Asset copy done")
        return destRoot
    }

    private fun copyDir(context: Context, src: String, dest: File) {
        val entries = context.assets.list(src) ?: return
        if (entries.isEmpty()) {
            // Files report list() as empty; copy as a regular file.
            copyFile(context, src, dest)
            return
        }
        dest.mkdirs()
        for (entry in entries) {
            val childSrc = "$src/$entry"
            val childDest = File(dest, entry)
            val childEntries = context.assets.list(childSrc) ?: emptyArray()
            if (childEntries.isEmpty()) {
                copyFile(context, childSrc, childDest)
            } else {
                copyDir(context, childSrc, childDest)
            }
        }
    }

    private fun copyFile(context: Context, src: String, dest: File) {
        context.assets.open(src).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }
}
