package com.moe.starflow.library

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** A damaged working DB must retain its outbox file instead of invoking SQLite's delete-and-recreate recovery. */
internal class LibraryOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false).callback(PreservingCallback(configuration.callback)).build())

    internal class PreservingCallback(private val delegate: SupportSQLiteOpenHelper.Callback) : SupportSQLiteOpenHelper.Callback(delegate.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)
        override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onDowngrade(db, oldVersion, newVersion)
        override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)
        override fun onCorruption(db: SupportSQLiteDatabase) {
            throw IllegalStateException("本机书库工作数据库损坏；已停止覆盖并保留数据库与待写资料")
        }
    }
}
