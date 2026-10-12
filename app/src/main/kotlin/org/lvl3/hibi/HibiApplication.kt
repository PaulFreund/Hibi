package org.lvl3.hibi

import android.app.Application
import org.lvl3.hibi.data.LocalDiary

class HibiApplication : Application() {
    val diary by lazy { LocalDiary(this) }
}
