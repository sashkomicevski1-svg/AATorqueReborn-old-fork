package com.aatorque.stats

import org.json.JSONArray
import org.json.JSONObject
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber

/** One diagnostic trouble code, for example P2002. */
data class Dtc(val code: String, val pending: Boolean) {
    val description: String get() = DtcText.describe(code)
}

/**
 * Parses ELM327 answers to Mode 03 (stored) and Mode 07 (pending) requests.
 *
 * It handles what an ELM327 returns on CAN, with or without headers, in one frame or in
 * several:
 * - `43 02 01 33 24 02`: CAN, no headers. 43 is the reply to mode 03, then the code count,
 *   then two bytes per code.
 * - `7E8 06 43 02 01 33 24 02`: with an 11-bit header and the frame length.
 * - `00A` / `0: 43 04 01 33 24 02` / `1: 00 4C ...`: a multi-frame reply without headers.
 * - `7E8 10 0A 43 04 ...` / `7E8 21 ...`: a multi-frame reply with headers.
 * - `NO DATA`, `SEARCHING...`, `>`: no codes.
 *
 * Several ECUs can answer; their codes are merged. Codes 0000 are padding.
 */
object DtcParser {
    private val HEX = Regex("^[0-9A-Fa-f]+$")

    fun parse(lines: Array<String>?, mode: Int): List<String> {
        if (lines == null) return emptyList()
        val reply = 0x40 + mode
        // Bytes per sender: a header when the ELM shows one, "" otherwise.
        val streams = LinkedHashMap<String, MutableList<Int>>()
        for (raw in lines) {
            val line = raw.trim().uppercase()
            if (line.isEmpty() || line.contains("NO DATA") || line.contains("SEARCHING") ||
                line.startsWith(">") || line.contains("ERROR") || line.contains("STOPPED")
            ) {
                continue
            }
            var tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var sender = ""
            // Multi-frame without headers: "0: 43 04 ..." (the total length line, "00A", is
            // a single 3-digit token and is dropped below).
            if (tokens.first().endsWith(":")) {
                tokens = tokens.drop(1)
            } else if (tokens.size > 1 && isHeader(tokens.first())) {
                // A header (7E8, 18DAF110), then the ISO-TP frame control byte.
                sender = tokens.first()
                tokens = tokens.drop(1)
                val pci = tokens.firstOrNull() ?: continue
                tokens = when (pci.firstOrNull()) {
                    '1' -> tokens.drop(2)

                    // first frame: 1L LL
                    '2' -> tokens.drop(1)

                    // consecutive frame
                    else -> tokens.drop(1) // single frame: length
                }
            } else if (tokens.size == 1 && tokens.first().length == 3) {
                continue
            }
            val bytes = tokens.filter { it.length == 2 && it.matches(HEX) }.map { it.toInt(16) }
            streams.getOrPut(sender) { mutableListOf() }.addAll(bytes)
        }
        val codes = LinkedHashSet<String>()
        for (bytes in streams.values) {
            val start = bytes.indexOf(reply)
            if (start < 0) continue
            val rest = bytes.drop(start + 1)
            val count = rest.firstOrNull() ?: continue
            // CAN replies carry a code count; older protocols do not. Use the count when the
            // bytes that follow fit it.
            val pairs = if (count <= (rest.size - 1) / 2) rest.drop(1).take(count * 2) else rest
            pairs.chunked(2).filter { it.size == 2 }.forEach { (a, b) ->
                if (a != 0 || b != 0) codes.add(decode(a, b))
            }
        }
        return codes.toList()
    }

    /** An 11-bit (7E8) or 29-bit (18DAF110) CAN header as the ELM prints it. */
    private fun isHeader(token: String): Boolean {
        val length = token.length
        return (length == 3 || length == 8) && token.matches(HEX)
    }

    fun decode(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 3]
        val digit = (a shr 4) and 3
        return "%c%d%X%02X".format(letter, digit, a and 0xF, b)
    }
}

/** Reads fault codes now and then, and shows them on the car screen. */
class DtcMonitor(
    private val store: JsonHistory<DtcReading>,
    private val notice: (CarNotices.Notice?) -> Unit,
    private val speak: (String) -> Unit,
    private val texts: (List<Dtc>) -> String = { codes ->
        "Fault codes: " + codes.joinToString { it.code }
    }
) {
    companion object {
        const val FIRST_READ_MS = 20_000L
        const val READ_EVERY_MS = 5 * 60_000L
        const val NOTICE_PRIORITY = 30
    }

    private var sessionStart = 0L
    private var lastRead = 0L
    private var announced: Set<String> = emptySet()

    @Synchronized
    fun maybeRead(ts: ITorqueService, nowElapsed: Long, nowWall: Long) {
        if (sessionStart == 0L) sessionStart = nowElapsed
        val due = if (lastRead == 0L) {
            nowElapsed - sessionStart >= FIRST_READ_MS
        } else {
            nowElapsed - lastRead >= READ_EVERY_MS
        }
        if (!due) return
        lastRead = nowElapsed
        val stored = read(ts, 3)
        val pending = read(ts, 7)
        if (stored == null && pending == null) return
        val codes = (
            stored.orEmpty().map { Dtc(it, false) } +
                pending.orEmpty().filter { it !in stored.orEmpty() }.map { Dtc(it, true) }
            )
        apply(codes, nowWall)
    }

    /** Show [codes] and speak the ones not announced yet in this session. */
    @Synchronized
    fun apply(codes: List<Dtc>, nowWall: Long) {
        store.save(listOf(DtcReading(nowWall, codes)))
        if (codes.isEmpty()) {
            notice(null)
            return
        }
        notice(CarNotices.Notice(texts(codes), CarNotices.Level.WARNING, NOTICE_PRIORITY))
        val fresh = codes.map { it.code }.toSet() - announced
        if (fresh.isNotEmpty()) speak(texts(codes.filter { it.code in fresh }))
        announced = announced + fresh
    }

    private fun read(ts: ITorqueService, mode: Int): List<String>? = try {
        val answer = ts.sendCommandGetResponse("", "%02d".format(mode))
        Timber.i("DTC: mode %02d raw %s", mode, answer?.joinToString(" | "))
        DtcParser.parse(answer, mode).also { Timber.i("DTC: mode %02d codes $it", mode) }
    } catch (e: Exception) {
        Timber.e(e, "DTC: mode %02d read failed", mode)
        null
    }
}

/** The last fault code reading, for the phone's Drive log. */
data class DtcReading(val wall: Long, val codes: List<Dtc>) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("wall", wall)
        put(
            "codes",
            JSONArray().apply {
                codes.forEach { put(JSONObject().put("code", it.code).put("pending", it.pending)) }
            }
        )
    }

    companion object {
        fun fromJson(o: JSONObject): DtcReading {
            val arr = o.optJSONArray("codes") ?: JSONArray()
            return DtcReading(
                o.getLong("wall"),
                List(arr.length()) {
                    val c = arr.getJSONObject(it)
                    Dtc(c.getString("code"), c.optBoolean("pending"))
                }
            )
        }
    }
}

/**
 * Short descriptions for codes that matter on a diesel like the 2.2 SKYACTIV-D. Generic SAE
 * meanings, plus Mazda's oil dilution codes. An unknown code shows without text.
 */
object DtcText {
    private val TEXT = mapOf(
        "P0087" to "Fuel rail pressure too low",
        "P0088" to "Fuel rail pressure too high",
        "P0093" to "Fuel system leak",
        "P0101" to "Mass air flow sensor range",
        "P0102" to "Mass air flow sensor low",
        "P0103" to "Mass air flow sensor high",
        "P0115" to "Coolant temperature sensor",
        "P0128" to "Coolant below thermostat temperature",
        "P0201" to "Injector 1 circuit",
        "P0202" to "Injector 2 circuit",
        "P0203" to "Injector 3 circuit",
        "P0204" to "Injector 4 circuit",
        "P0234" to "Turbo overboost",
        "P0299" to "Turbo underboost",
        "P0300" to "Random misfire",
        "P0380" to "Glow plug circuit",
        "P0401" to "EGR flow insufficient",
        "P0402" to "EGR flow excessive",
        "P0403" to "EGR circuit",
        "P0404" to "EGR range/performance",
        "P0405" to "EGR sensor A low",
        "P0406" to "EGR sensor A high",
        "P0471" to "Exhaust pressure sensor range",
        "P0480" to "Cooling fan circuit",
        "P0500" to "Vehicle speed sensor",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0670" to "Glow plug module",
        "P0704" to "Clutch switch",
        "P2002" to "DPF efficiency below threshold",
        "P2006" to "Intake manifold runner control",
        "P2031" to "Exhaust temperature sensor 2",
        "P2032" to "Exhaust temperature sensor 2 low",
        "P2033" to "Exhaust temperature sensor 2 high",
        "P2080" to "Exhaust temperature sensor 1 range",
        "P2263" to "Turbo boost system performance",
        "P2264" to "Water in fuel sensor",
        "P242F" to "DPF restriction: ash accumulation",
        "P244A" to "DPF differential pressure too low",
        "P244B" to "DPF differential pressure too high",
        "P2452" to "DPF pressure sensor circuit",
        "P2453" to "DPF pressure sensor range",
        "P2454" to "DPF pressure sensor low",
        "P2455" to "DPF pressure sensor high",
        "P2458" to "DPF regeneration duration",
        "P2459" to "DPF regeneration frequency",
        "P2463" to "DPF restriction: soot accumulation",
        "P252F" to "Engine oil level too high (oil dilution)",
        "P253F" to "Engine oil deteriorated",
        "U0073" to "CAN bus off",
        "U0100" to "Lost communication with engine ECU",
        "U0101" to "Lost communication with transmission ECU",
        "U0121" to "Lost communication with ABS"
    )

    fun describe(code: String): String = TEXT[code.uppercase()] ?: ""
}
