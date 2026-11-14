package com.aatorque.stats

/**
 * Torque's own calculated metrics carry ids starting with `ff` (GPS speed, acceleration,
 * power). They never come from the ECU, so they never appear in `listECUSupportedPIDs()` and
 * must survive the supported filter.
 */
const val SYNTHETIC_PID_PREFIX = "ff"

/**
 * Chooses which PIDs the user may bind a gauge to.
 *
 * Torque's dictionary (`listAllPIDs()`) holds every PID it knows, including the many the
 * connected car never answers. Offering those lets a gauge report `0` forever with no warning,
 * so the supported set wins whenever Torque has one.
 *
 * An empty supported set means "no adapter, or Torque not ready yet". The full dictionary is
 * returned in that case so dashboards can still be configured off-car.
 *
 * Kept free of Android and AIDL types so it can be unit tested. See docs/pid-filtering.md.
 */
fun selectPids(all: List<String>, supported: Set<String>): List<String> {
    if (supported.isEmpty()) return all
    return all.filter {
        it.startsWith(SYNTHETIC_PID_PREFIX) || supported.contains(it) || isCustomPid(it)
    }
}

/**
 * A user-defined PID, such as a manufacturer Mode 22 PID (`22f48b,115616640`). The ECU's
 * supported-PID bitmap covers standard Mode 01 PIDs only, so custom PIDs are never in it and
 * must always be offered. A standard PID's id is the two-digit Mode 01 number.
 */
fun isCustomPid(id: String): Boolean = id.substringBefore(',').length > 2
