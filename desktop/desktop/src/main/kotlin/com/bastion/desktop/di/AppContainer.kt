package com.bastion.desktop.di

import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.repository.BitwardenRepositoryStore
import com.bastion.app.bitwarden.repository.SqlDelightBitwardenRepositoryStore
import com.bastion.app.db.BastionDatabase
import com.bastion.app.db.BastionDatabaseBundle
import com.bastion.app.db.BastionDatabaseFactory
import com.bastion.app.platform.PathProvider
import com.bastion.app.security.DesktopCryptoManager
import com.bastion.desktop.platform.OneDriveBrowserAuth
import com.bastion.desktop.platform.OneDriveSessionManager

/**
 * 手动依赖装配（不引入 DI 框架）。
 * Phase 3 起仓储存储层使用 SQLDelight 持久化实现（[SqlDelightBitwardenRepositoryStore]），
 * 替换原先的 [com.bastion.app.bitwarden.repository.InMemoryBitwardenRepositoryStore]。
 */
object AppContainer {

    val cryptoManager: DesktopCryptoManager by lazy {
        DesktopCryptoManager().apply {
            ensureAppMasterKey()
        }
    }

    private val dbBundle: BastionDatabaseBundle by lazy {
        BastionDatabaseFactory.create(PathProvider.resolve("bastion.db"))
    }

    val database: BastionDatabase by lazy { dbBundle.database }

    val repositoryStore: BitwardenRepositoryStore by lazy {
        SqlDelightBitwardenRepositoryStore(dbBundle.database, dbBundle.driver, cryptoManager)
    }

    val bitwardenRepository: BitwardenRepository by lazy {
        BitwardenRepository(
            store = repositoryStore,
            cryptoManager = cryptoManager
        )
    }

    val oneDriveAuth: OneDriveBrowserAuth by lazy {
        OneDriveBrowserAuth()
    }

    val oneDriveSessionManager: OneDriveSessionManager by lazy {
        OneDriveSessionManager(cryptoManager)
    }
}
