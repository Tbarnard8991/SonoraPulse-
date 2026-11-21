package com.sonorapulse.app.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.sonorapulse.app.dsp.AudioTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ExportResult {
    data class Saved(val fileName: String) : ExportResult
    data object NoSamples : ExportResult
    data class Failed(val reason: String) : ExportResult
}

object TelemetryExporter {
    suspend fun exportCsv(context: Context, snapshots: List<AudioTelemetry>): ExportResult =
        withContext(Dispatchers.IO) {
            if (snapshots.isEmpty()) return@withContext ExportResult.NoSamples
            val resolver = context.contentResolver
            val fileName = "sonorapulse_${System.currentTimeMillis()}.csv"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SonoraPulse")
                put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden until fully written
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext ExportResult.Failed("Could not create file")

            try {
                val stream = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open file")
                stream.bufferedWriter().use { out ->
                    out.write("timestamp_ms,rms_dbfs,peak_hz,centroid_hz,sub_bass_db,bass_db,mid_db,presence_db,brilliance_db\n")
                    for (s in snapshots) {
                        out.write(
                            "${s.timestampMs},${s.rmsDbfs},${s.peakFreqHz},${s.spectralCentroidHz}," +
                            "${s.subBassDb},${s.bassDb},${s.midDb},${s.presenceDb},${s.brillianceDb}\n"
                        )
                    }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ExportResult.Saved(fileName)
            } catch (e: Exception) {
                resolver.delete(uri, null, null) // don't leave an empty file behind
                ExportResult.Failed(e.message ?: "Write failed")
            }
        }
}
