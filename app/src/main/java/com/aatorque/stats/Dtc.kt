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
 * - `NO DATA`: no codes. `UNABLE TO CONNECT`, `BUS BUSY`, `?`...: the request failed.
 *
 * Several ECUs can answer; each reply is read on its own and the codes are merged. Codes 0000
 * are padding.
 */
object DtcParser {
    private val HEX = Regex("^[0-9A-Fa-f]+$")
    private val SPACES = Regex("\\s+")

    /** Adapter answers that mean the request failed, not that there are no codes. */
    private val FAILURES = listOf(
        "UNABLE TO CONNECT",
        "BUS BUSY",
        "BUS ERROR",
        "CAN ERROR",
        "DATA ERROR",
        "FB ERROR",
        "BUFFER FULL",
        "STOPPED",
        "ERROR",
        "?"
    )

    /**
     * The codes in an ELM327 reply, an empty list for "no codes" (NO DATA, or a count of 0),
     * or null when the request failed and nothing is known.
     */
    fun parse(lines: Array<String>?, mode: Int): List<String>? {
        // Torque may return one string with the lines joined by CR or LF.
        val all = lines
            ?.flatMap { it.split('\r', '\n') }
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() && !it.startsWith(">") && !it.contains("SEARCHING") }
        if (all.isNullOrEmpty()) return null
        if (all.any { line -> FAILURES.any { line.contains(it) } }) return null
        val reply = 0x40 + mode
        val replyHex = "%02X".format(reply)
        // Bytes per sender: the header when the ELM shows one; without headers, every reply
        // that starts on a new line is its own sender, so two ECUs answering do not merge.
        val streams = LinkedHashMap<String, MutableList<Int>>()
        var current = ""
        var replies = 0
        for (line in all) {
            if (line.contains("NO DATA")) continue
            var tokens = line.split(SPACES).filter { it.isNotEmpty() }
            when {
                tokens.first().endsWith(":") -> {
                    // Multi-frame without headers; frame 0 starts a new reply.
                    if (tokens.first() == "0:") current = "#${replies++}"
                    tokens = tokens.drop(1)
                }

                tokens.size > 1 && isHeader(tokens.first()) -> {
                    // A header (7E8, 18DAF110), then the ISO-TP frame control byte(s).
                    current = tokens.first()
                    tokens = tokens.drop(1)
                    tokens = if (tokens.firstOrNull()?.startsWith("1") == true) {
                        tokens.drop(2) // first frame: 1L LL
                    } else {
                        tokens.drop(1) // single frame length, or consecutive frame 2N
                    }
                }

                tokens.size == 1 && tokens.first().length == 3 -> continue

                // "00A": total length

                tokens.first() == replyHex -> current = "#${replies++}" // single frame, no header
            }
            val bytes = tokens.filter { it.length == 2 && it.matches(HEX) }.map { it.toInt(16) }
            streams.getOrPut(current) { mutableListOf() }.addAll(bytes)
        }
        val codes = LinkedHashSet<String>()
        var sawReply = false
        for (bytes in streams.values) {
            val start = bytes.indexOf(reply)
            if (start < 0) continue
            sawReply = true
            val rest = bytes.drop(start + 1)
            val count = rest.firstOrNull() ?: continue
            // CAN replies carry a code count; older protocols do not. Use the count when the
            // bytes that follow fit it.
            val pairs = if (count <= (rest.size - 1) / 2) rest.drop(1).take(count * 2) else rest
            pairs.chunked(2).filter { it.size == 2 }.forEach { (a, b) ->
                if (a != 0 || b != 0) codes.add(decode(a, b))
            }
        }
        // NO DATA only, or nothing that answers the request: no codes.
        return if (sawReply || streams.isEmpty()) codes.toList() else null
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

    private var sessionStart: Long? = null
    private var lastRead: Long? = null
    private var announced: Set<String> = emptySet()

    /** True when a read is due; marks it as taken. Cheap, called on every tick. */
    @Synchronized
    fun due(nowElapsed: Long): Boolean {
        val start = sessionStart ?: nowElapsed.also { sessionStart = it }
        val last = lastRead
        val isDue = if (last == null) {
            nowElapsed - start >= FIRST_READ_MS
        } else {
            nowElapsed - last >= READ_EVERY_MS
        }
        if (isDue) lastRead = nowElapsed
        return isDue
    }

    /**
     * Read stored and pending codes. Runs on the session thread: each raw command waits for
     * the adapter. A failed read changes nothing; the last known codes stay.
     */
    fun readNow(ts: ITorqueService, nowWall: Long) {
        val stored = read(ts, 3)
        if (stored == null) {
            Timber.w("DTC: stored codes unknown (read failed); keeping the last result")
            return
        }
        val pending = read(ts, 7).orEmpty()
        val codes = stored.map { Dtc(it, false) } +
            pending.filter { it !in stored }.map { Dtc(it, true) }
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

    /**
     * Mode 03/07 go to 7DF, the functional OBD address every engine-related ECU answers.
     * Torque's own last header could be another module's (a tyre PID's 720). If that fails,
     * the request is sent once more with Torque's current header. Raw answers are logged.
     */
    private fun read(ts: ITorqueService, mode: Int): List<String>? {
        for (header in listOf("7DF", "")) {
            try {
                val answer = ts.sendCommandGetResponse(header, "%02d".format(mode))
                Timber.i(
                    "DTC: mode %02d header '%s' raw %s",
                    mode,
                    header,
                    answer?.joinToString(" | ")
                )
                val codes = DtcParser.parse(answer, mode)
                Timber.i("DTC: mode %02d codes %s", mode, codes)
                if (codes != null) return codes
            } catch (e: Exception) {
                Timber.e(e, "DTC: mode %02d header '%s' failed", mode, header)
            }
        }
        return null
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
