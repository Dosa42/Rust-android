package com.example.openclaw

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Extracts the Termux bootstrap archive into the app's private data directory,
 * creating a usable Linux-like prefix environment without root.
 * Ported and enhanced from Dosa42/openclaw-android-assistant.
 */
object BootstrapInstaller {
    private const val TAG = "BootstrapInstaller"
    private const val BOOTSTRAP_VERSION = "bootstrap-2026.02.12-r1+apt.android-7"

    data class Paths(
        val filesDir: String,
        val prefixDir: String,
        val homeDir: String,
        val tmpDir: String,
    )

    fun getPaths(context: Context): Paths {
        val filesDir = context.filesDir.absolutePath
        return Paths(
            filesDir = filesDir,
            prefixDir = "$filesDir/usr",
            homeDir = "$filesDir/home",
            tmpDir = "$filesDir/usr/tmp",
        )
    }

    fun isBootstrapInstalled(context: Context): Boolean {
        val paths = getPaths(context)
        return File(paths.prefixDir).isDirectory && File(paths.prefixDir, "bin/sh").exists()
    }

    /**
     * Extract bootstrap-<arch>.zip from assets, cache, or downloads it on demand.
     */
    fun install(
        context: Context,
        onProgress: (String) -> Unit = {},
    ) {
        val paths = getPaths(context)
        val prefixFile = File(paths.prefixDir)
        if (prefixFile.isDirectory && File(prefixFile, "bin/sh").exists()) {
            Log.i(TAG, "Bootstrap already installed at ${paths.prefixDir}")
            return
        }

        val arch = determineArchName()
        val assetName = "bootstrap-$arch.zip"
        val stagingPath = "${paths.filesDir}/usr-staging"
        val stagingFile = File(stagingPath)
        val termuxPrefix = "/data/data/com.termux/files/usr"

        onProgress("Preparing bootstrap environment for $arch…")
        if (stagingFile.exists()) {
            deleteRecursive(stagingFile)
        }
        stagingFile.mkdirs()

        // 1. Resolve bootstrap zip input stream (Assets -> Cache -> Network download)
        val inputStream: InputStream = resolveBootstrapStream(context, assetName, arch, onProgress)

        onProgress("Extracting bootstrap archive…")
        val buffer = ByteArray(32768)
        val symlinks = mutableListOf<Pair<String, String>>()

        inputStream.use { rawStream ->
            ZipInputStream(rawStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "SYMLINKS.txt") {
                        val reader = BufferedReader(InputStreamReader(zip))
                        var line = reader.readLine()
                        while (line != null) {
                            val parts = line.split("←")
                            if (parts.size == 2) {
                                var target = parts[0]
                                if (target.startsWith(termuxPrefix)) {
                                    target = target.replace(termuxPrefix, paths.prefixDir)
                                }
                                val linkPath = "$stagingPath/${parts[1]}"
                                symlinks.add(target to linkPath)
                                val parentFile = File(linkPath).parentFile
                                if (parentFile != null) {
                                    ensureParentDir(parentFile)
                                }
                            }
                            line = reader.readLine()
                        }
                    } else {
                        val targetFile = File(stagingPath, entry.name)
                        val isDir = entry.isDirectory
                        ensureParentDir(if (isDir) targetFile else targetFile.parentFile!!)
                        if (isDir) {
                            targetFile.mkdirs()
                        } else {
                            FileOutputStream(targetFile).use { out ->
                                var len = zip.read(buffer)
                                while (len != -1) {
                                    out.write(buffer, 0, len)
                                    len = zip.read(buffer)
                                }
                            }
                            if (shouldBeExecutable(entry.name)) {
                                Os.chmod(targetFile.absolutePath, 0b111_000_000) // 0700
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }

        if (symlinks.isEmpty()) {
            throw RuntimeException("No SYMLINKS.txt found in bootstrap archive")
        }

        onProgress("Creating symlinks…")
        for ((target, linkPath) in symlinks) {
            try {
                val linkFile = File(linkPath)
                if (linkFile.exists() || linkFile.isDirectory) {
                    deleteRecursive(linkFile)
                }
                Os.symlink(target, linkPath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink $linkPath -> $target: ${e.message}")
            }
        }

        if (prefixFile.exists()) {
            deleteRecursive(prefixFile)
        }
        if (!stagingFile.renameTo(prefixFile)) {
            throw RuntimeException("Failed to rename $stagingPath to ${paths.prefixDir}")
        }

        File(paths.homeDir).mkdirs()
        File(paths.tmpDir).mkdirs()

        onProgress("Configuring package manager…")
        fixTermuxPaths(context, paths)
        Log.i(TAG, "Bootstrap installed successfully at ${paths.prefixDir}")
    }

    private fun resolveBootstrapStream(
        context: Context,
        assetName: String,
        arch: String,
        onProgress: (String) -> Unit
    ): InputStream {
        // Try APK assets
        try {
            val assetsList = context.assets.list("") ?: emptyArray()
            if (assetsList.contains(assetName)) {
                return context.assets.open(assetName)
            }
        } catch (_: Exception) {}

        // Try cached file
        val cachedFile = File(context.cacheDir, assetName)
        if (cachedFile.exists() && cachedFile.length() > 1000) {
            return FileInputStream(cachedFile)
        }

        // Download on demand
        onProgress("Downloading Termux bootstrap ($arch)…")
        val downloadUrl = "https://github.com/termux/termux-packages/releases/download/$BOOTSTRAP_VERSION/$assetName"
        val mirrorUrl = "https://sourceforge.net/projects/termux-packages.mirror/files/$BOOTSTRAP_VERSION/$assetName/download"

        val downloaded = downloadFile(downloadUrl, cachedFile, onProgress) ||
                downloadFile(mirrorUrl, cachedFile, onProgress)

        if (downloaded && cachedFile.exists() && cachedFile.length() > 1000) {
            return FileInputStream(cachedFile)
        }

        throw RuntimeException("Unable to acquire $assetName from assets or download mirror")
    }

    private fun downloadFile(urlString: String, destination: File, onProgress: (String) -> Unit): Boolean {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.connect()
            if (conn.responseCode in 200..399) {
                val totalBytes = conn.contentLength
                conn.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(16384)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0 && downloaded % (512 * 1024) == 0L) {
                                val percent = (downloaded * 100 / totalBytes).toInt()
                                onProgress("Downloading bootstrap: $percent%")
                            }
                        }
                    }
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for $urlString: ${e.message}")
            false
        }
    }

    private fun fixTermuxPaths(context: Context, paths: Paths) {
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val appPkg = context.packageName

        val aptConf = File(prefix, "etc/apt/apt.conf")
        aptConf.parentFile?.mkdirs()
        aptConf.writeText(
            """
            Dir "/";
            Dir::State "$prefix/var/lib/apt/";
            Dir::State::status "$prefix/var/lib/dpkg/status";
            Dir::Cache "$prefix/var/cache/apt/";
            Dir::Log "$prefix/var/log/apt/";
            Dir::Etc "$prefix/etc/apt/";
            Dir::Etc::SourceList "$prefix/etc/apt/sources.list";
            Dir::Etc::SourceParts "";
            Dir::Bin::dpkg "$prefix/bin/dpkg";
            Dir::Bin::Methods "$prefix/lib/apt/methods/";
            Dir::Bin::apt-key "$prefix/bin/apt-key";
            Dpkg::Options:: "--force-configure-any";
            Dpkg::Options:: "--force-bad-path";
            Dpkg::Options:: "--instdir=$prefix";
            Acquire::AllowInsecureRepositories "true";
            """.trimIndent() + "\n"
        )

        File(prefix, "var/log/apt").mkdirs()

        val sourcesList = File(prefix, "etc/apt/sources.list")
        if (sourcesList.exists()) {
            val content = sourcesList.readText()
            sourcesList.writeText(
                content.replace("https://", "http://")
                    .replace("com.termux", appPkg)
            )
        }

        val dpkgStatus = File(prefix, "var/lib/dpkg/status")
        if (dpkgStatus.exists()) {
            val content = dpkgStatus.readText()
            dpkgStatus.writeText(content.replace(termuxPrefix, prefix))
        }

        File(prefix, "var/lib/dpkg/info").mkdirs()
        File(prefix, "var/lib/dpkg/updates").mkdirs()
        File(prefix, "var/lib/dpkg/triggers").mkdirs()
        File(prefix, "var/cache/apt/archives/partial").mkdirs()
        File(prefix, "var/lib/apt/lists/partial").mkdirs()

        val dpkgInfoDir = File(prefix, "var/lib/dpkg/info")
        if (dpkgInfoDir.isDirectory) {
            dpkgInfoDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".list")) {
                    try {
                        val text = file.readText()
                        if (text.contains(termuxPrefix)) {
                            file.writeText(text.replace(termuxPrefix, prefix))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fix ${file.name}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun shouldBeExecutable(entryName: String): Boolean {
        return entryName.startsWith("bin/") ||
                entryName.startsWith("libexec/") ||
                entryName.startsWith("lib/apt/methods/") ||
                entryName.startsWith("lib/bash/") ||
                entryName.endsWith(".so") ||
                entryName.contains("/bin/")
    }

    private fun ensureParentDir(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            if (!dir.isDirectory) {
                throw RuntimeException("Unable to create directory: ${dir.absolutePath}")
            }
        }
    }

    private fun determineArchName(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a" -> return "arm"
                "x86_64" -> return "x86_64"
                "x86" -> return "i686"
            }
        }
        return "aarch64"
    }

    private fun deleteRecursive(fileOrDir: File) {
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        fileOrDir.delete()
    }
}
