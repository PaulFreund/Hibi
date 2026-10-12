package org.lvl3.hibi

import android.app.Application
import org.lvl3.hibi.data.LocalDiary
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

class HibiApplication : Application() {
    val diary by lazy { LocalDiary(this) }
    val diaryMutex = Mutex()
    private val cleaned = AtomicBoolean(false)
    fun claimTemporaryCleanup() = cleaned.compareAndSet(false, true)
}
