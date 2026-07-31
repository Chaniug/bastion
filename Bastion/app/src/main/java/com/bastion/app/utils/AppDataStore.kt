package com.bastion.app.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 全模块唯一的 "settings" DataStore 委托。
 *
 * DataStore 要求同一个文件在进程内只能有一个实例，重复声明
 * preferencesDataStore(name = "settings") 会在运行时抛出
 * IllegalStateException: There are multiple DataStore active for the same file。
 *
 * 因此所有需要访问 settings 存储的组件（SettingsManager、AutofillPreferences 等）
 * 都必须复用这里的扩展属性，禁止再各自声明。
 */
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
