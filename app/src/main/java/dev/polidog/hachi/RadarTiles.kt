package dev.polidog.hachi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** Web Mercator tile coordinates, fractional so a point can be placed inside its tile. */
fun tileX(longitude: Double, zoom: Int): Double = (longitude + 180.0) / 360.0 * (1 shl zoom)

fun tileY(latitude: Double, zoom: Int): Double {
    val radians = Math.toRadians(latitude)
    return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * (1 shl zoom)
}

/**
 * The rain radar, as map tiles.
 *
 * Yahoo's static map API — the obvious way to get a rendered radar image — was retired in 2020 and
 * answers 404. These two sources replace it and need no account at all: the Geospatial Information
 * Authority's base map, and the Japan Meteorological Agency's high-resolution nowcast for the rain
 * on top. Both require their attribution to be shown, which [RadarPage] draws.
 *
 * The base map never changes, so it is left to HTTP caching; only the rain layer is re-fetched.
 */
class RadarTiles(context: Context) {
    private val http = OkHttpClient.Builder()
        // The base map is the same tiles every time; without a cache this would refetch a megabyte
        // of coastline every five minutes.
        .cache(Cache(File(context.cacheDir, "tiles"), 32L * 1024 * 1024))
        .build()

    /** Blocking. The most recent nowcast frame's timestamp, or null if the index is unreachable. */
    fun latestFrame(): String? = try {
        val request = Request.Builder().url(TIMES_URL).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            // Newest first; each entry pairs the frame's base time with what it is valid for.
            JSONArray(body).optJSONObject(0)?.optString("basetime")?.ifBlank { null }
        }
    } catch (e: Exception) {
        Log.w(TAG, "nowcast index failed", e)
        null
    }

    /** Blocking. The base map tile, or null when it could not be fetched. */
    fun base(zoom: Int, x: Int, y: Int): Bitmap? =
        bitmap("https://cyberjapandata.gsi.go.jp/xyz/pale/$zoom/$x/$y.png")

    /** Blocking. The rain tile for [frame]; often mostly transparent, and null where there is none. */
    fun rain(frame: String, zoom: Int, x: Int, y: Int): Bitmap? =
        bitmap("https://www.jma.go.jp/bosai/jmatile/data/nowc/$frame/none/$frame/surf/hrpns/$zoom/$x/$y.png")

    private fun bitmap(url: String): Bitmap? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "tile failed: $url", e)
        null
    }

    private companion object {
        const val TIMES_URL = "https://www.jma.go.jp/bosai/jmatile/data/nowc/targetTimes_N1.json"
        const val TAG = "Hachi"
    }
}
