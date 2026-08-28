package com.obsidiancodx.entityinventory

import android.app.Application
import com.obsidiancodx.entityinventory.data.InventoryRepository

class EntityInventoryApplication : Application() {
    val repository by lazy { InventoryRepository(this) }
}
