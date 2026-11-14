package com.aatorque.stats

import android.content.Context

/**
 * The Torque PIDs the DPF assistant and trip summary read, by role.
 *
 * Mazda's diesel PIDs are custom Mode 22 PIDs, and their Torque ids carry a hash of the
 * equation (`22f48b,115616640`), so the same ECU value can exist under several ids. Roles are
 * found by the PID's long name in Torque (the names of the common SKYACTIV-D PID pack) and can
 * be overridden by hand in Settings → DPF assistant.
 */
enum class PidRole(val key: String) {
    REGEN_STATUS("regenStatus"),
    SOOT("soot"),
    SOOT_LIMIT("sootLimit"),
    REGEN_TRIGGER("regenTrigger"),
    REGEN_COUNT("regenCount"),
    DISTANCE_SINCE_REGEN("distanceSinceRegen"),
    TOTAL_DISTANCE("totalDistance"),
    SPEED("speed"),
    COOLANT("coolant")
}

object PidRoles {
    const val PREFS = "pid_roles"
    const val AUTO = ""

    /** Standard SAE PIDs, the same on every car. */
    private val STANDARD = mapOf(
        PidRole.SPEED to "0d,0",
        PidRole.COOLANT to "05,0"
    )

    /**
     * Name tests, most specific first. A name is matched lower-case. Each test also says what
     * must not be in the name, so "PM Accumulation Desired" is not taken for the soot load.
     */
    private val NAME_RULES: Map<PidRole, List<Pair<String, List<String>>>> = mapOf(
        PidRole.REGEN_STATUS to
            listOf("dpf regeneration status" to emptyList(), "regen status" to emptyList()),
        PidRole.SOOT to
            listOf(
                "dpf pm accumulation" to listOf("desired"),
                "soot" to listOf("desired", "limit")
            ),
        PidRole.SOOT_LIMIT to listOf("dpf pm accumulation desired" to emptyList()),
        PidRole.REGEN_TRIGGER to listOf("dpf regeneration trigger" to emptyList()),
        PidRole.REGEN_COUNT to listOf("dpf regeneration count" to emptyList()),
        PidRole.DISTANCE_SINCE_REGEN to listOf("dpf regeneration distance" to listOf("average")),
        PidRole.TOTAL_DISTANCE to listOf("total distance" to emptyList(), "odometer" to emptyList())
    )

    /**
     * Pick a PID id per role from Torque's list of (id, long name). Manual choices win; a role
     * without a match is left out.
     */
    fun resolve(
        pids: List<Pair<String, String>>,
        manual: Map<PidRole, String>
    ): Map<PidRole, String> {
        val result = HashMap<PidRole, String>()
        for (role in PidRole.entries) {
            val chosen = manual[role]?.takeIf { it.isNotBlank() } ?: STANDARD[role]
            if (chosen != null) {
                result[role] = chosen
                continue
            }
            val rules = NAME_RULES[role] ?: continue
            val found = rules.firstNotNullOfOrNull { (needle, exclusions) ->
                pids.firstOrNull { (_, name) ->
                    val n = name.lowercase()
                    n.contains(needle) && exclusions.none { n.contains(it) }
                }?.first
            }
            if (found != null) result[role] = found
        }
        return result
    }

    fun manual(context: Context): Map<PidRole, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PidRole.entries.mapNotNull { role ->
            prefs.getString(role.key, AUTO)?.takeIf { it.isNotBlank() }?.let { role to it }
        }.toMap()
    }

    fun setManual(context: Context, role: PidRole, pid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(role.key, pid).apply()
    }
}
