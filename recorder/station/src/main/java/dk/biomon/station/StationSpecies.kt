package dk.biomon.station

import android.content.Context
import org.json.JSONObject

/** Loads `assets/station_species.json` (built by
 *  `analysis/tools/build_station_species.py`) — index-to-taxon for all 6522 BirdNET
 *  classes, in model-output order, including `regional` (Danish checklist membership)
 *  and `months` (GBIF seasonal fractions) — read at capture time by StationService to
 *  decide region/season plausibility per detection (Model.kt's `seasonallyPlausible`). */
class StationSpecies private constructor(val byIndex: Map<Int, Taxon>) {
    companion object {
        fun load(ctx: Context): StationSpecies {
            val txt = ctx.assets.open("station_species.json").bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            val arr = o.getJSONArray("species")
            val map = HashMap<Int, Taxon>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val idx = s.getInt("i")
                val monthsArr = s.optJSONArray("months")
                val months = if (monthsArr == null) null else
                    (0 until monthsArr.length()).map { monthsArr.getDouble(it).toFloat() }
                map[idx] = Taxon(
                    key = s.getString("key"), scientific = s.getString("sci"),
                    common = s.getString("com"), group = s.getString("group"),
                    rank = "species", modelIndex = idx,
                    regional = s.optBoolean("regional", true),
                    monthFractions = months,
                    monthsTotal = if (s.isNull("months_total")) null else s.optInt("months_total")
                )
            }
            return StationSpecies(map)
        }
    }
}
