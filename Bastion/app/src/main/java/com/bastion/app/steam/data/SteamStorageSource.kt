package com.bastion.app.steam.data

sealed interface SteamStorageSource {
    data object Local : SteamStorageSource
    data class Mdbx(val databaseId: Long) : SteamStorageSource
}
