package io.github.ntufar.deltasleep.data.db

import androidx.room.TypeConverter
import io.github.ntufar.deltasleep.data.model.SleepPhase

class Converters {
    @TypeConverter fun fromPhase(p: SleepPhase): Int = p.ordinal
    @TypeConverter fun toPhase(v: Int): SleepPhase = SleepPhase.entries[v]
}
