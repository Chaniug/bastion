package com.bastion.app.platform

/**
 * 应用数据目录（配置/密钥 blob/数据库文件存放位置）。
 * 桌面端：%APPDATA%/BastionDesktop；安卓端：filesDir。
 */
expect object PathProvider {
    val dataDir: String
    fun resolve(relativePath: String): String
}
