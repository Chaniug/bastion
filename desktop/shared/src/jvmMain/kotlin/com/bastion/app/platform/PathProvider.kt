package com.bastion.app.platform

import java.io.File

actual object PathProvider {
    actual val dataDir: String by lazy {
        val base = System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"))
        val dir = File(base, "BastionDesktop")
        dir.mkdirs()
        dir.absolutePath
    }

    actual fun resolve(relativePath: String): String = File(dataDir, relativePath).absolutePath
}
