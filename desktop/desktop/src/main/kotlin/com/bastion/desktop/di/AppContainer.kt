package com.bastion.desktop.di

import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.repository.BitwardenRepositoryStore
import com.bastion.app.bitwarden.repository.InMemoryBitwardenRepositoryStore
import com.bastion.app.security.DesktopCryptoManager
import com.bastion.desktop.platform.OneDriveBrowserAuth
import com.bastion.desktop.platform.OneDriveSessionManager

/**
 * 手动依赖装配（不引入 DI 框架）。
 * 后续 Phase 3 将 InMemory store 换成 SQLDelight 实现。
 */
object AppContainer {

    val cryptoManager: DesktopCryptoManager by lazy {
        DesktopCryptoManager().apply {
            ensureAppMasterKey()
        }
    }

    val repositoryStore: BitwardenRepositoryStore by lazy {
        InMemoryBitwardenRepositoryStore()
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
