package io.github.ntufar.deltasleep.data.db

import androidx.room.TypeConverter
import io.github.ntufar.deltasleep.data.model.AcousticBand
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.RiskBand
import io.github.ntufar.deltasleep.data.model.SignalQuality
import io.github.ntufar.deltasleep.data.model.SleepPhase

class Converters {
    // SleepPhase
    @TypeConverter fun fromPhase(p: SleepPhase): Int = p.ordinal
    @TypeConverter fun toPhase(v: Int): SleepPhase =
        SleepPhase.entries.getOrElse(v) { SleepPhase.AWAKE }

    // AcousticEventType
    @TypeConverter fun fromAcousticEventType(t: AcousticEventType): Int = t.ordinal
    @TypeConverter fun toAcousticEventType(v: Int): AcousticEventType =
        AcousticEventType.entries.getOrElse(v) { AcousticEventType.APNEA_LIKE }

    // SignalQuality
    @TypeConverter fun fromSignalQuality(q: SignalQuality): Int = q.ordinal
    @TypeConverter fun toSignalQuality(v: Int): SignalQuality =
        SignalQuality.entries.getOrElse(v) { SignalQuality.LOW }

    // RiskBand
    @TypeConverter fun fromRiskBand(r: RiskBand): Int = r.ordinal
    @TypeConverter fun toRiskBand(v: Int): RiskBand =
        RiskBand.entries.getOrElse(v) { RiskBand.LOW }

    // AcousticBand
    @TypeConverter fun fromAcousticBand(b: AcousticBand): Int = b.ordinal
    @TypeConverter fun toAcousticBand(v: Int): AcousticBand =
        AcousticBand.entries.getOrElse(v) { AcousticBand.NONE }
}
