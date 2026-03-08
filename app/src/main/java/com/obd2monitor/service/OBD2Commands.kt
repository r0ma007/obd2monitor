package com.obd2monitor.service

/**
 * OBD2 PID definitions and AT commands for ELM327
 */
object OBD2Commands {

    // AT Initialization commands
    const val RESET = "ATZ"
    const val ECHO_OFF = "ATE0"
    const val LINE_FEED_OFF = "ATL0"
    const val SPACES_OFF = "ATS0"
    const val HEADERS_OFF = "ATH0"
    const val PROTOCOL_AUTO = "ATSP0"
    const val DESCRIBE_PROTOCOL = "ATDP"

    // OBD2 Mode 01 PIDs
    const val ENGINE_RPM = "010C"         // RPM = ((A*256)+B)/4
    const val VEHICLE_SPEED = "010D"      // Speed = A km/h
    const val COOLANT_TEMP = "0105"       // Temp = A - 40 °C
    const val FUEL_LEVEL = "012F"         // Fuel% = A * 100 / 255
    const val THROTTLE_POS = "0111"       // Throttle% = A * 100 / 255
    const val FUEL_RATE = "015E"          // Fuel rate = ((A*256)+B)*0.05 L/h
    const val CONTROL_VOLTAGE = "0142"    // Voltage = ((A*256)+B)/1000 V
    const val ENGINE_OIL_TEMP = "015C"   // Oil temp = A - 40 °C
    const val MAF_RATE = "0110"           // MAF = ((A*256)+B) / 100 g/s

    // Odometer (OBD2 Mode 01 PID A6 - newer vehicles)
    const val ODOMETER = "01A6"          // Odometer = ((A*2^24)+(B*2^16)+(C*2^8)+D)*0.1 km

    // Supported PIDs check
    const val SUPPORTED_PIDS_01_20 = "0100"
    const val SUPPORTED_PIDS_21_40 = "0120"
    const val SUPPORTED_PIDS_41_60 = "0140"
    const val SUPPORTED_PIDS_61_80 = "0160"
    const val SUPPORTED_PIDS_A1_C0 = "01A0"
}

/**
 * Parses ELM327 OBD2 responses
 */
object OBD2Parser {

    /**
     * Parse raw ELM327 response string
     * Returns cleaned hex bytes or null if error
     */
    fun parseResponse(raw: String, pid: String): ByteArray? {
        val cleaned = raw.replace("\\s".toRegex(), "")
            .replace("\r", "")
            .replace("\n", "")
            .uppercase()

        // Check for errors
        if (cleaned.contains("NODATA") ||
            cleaned.contains("ERROR") ||
            cleaned.contains("?") ||
            cleaned.contains("UNABLE") ||
            cleaned.contains("STOPPED")) {
            return null
        }

        // Remove echo (the sent command might be echoed back)
        val pidClean = pid.replace(" ", "").uppercase()
        val withoutEcho = if (cleaned.startsWith(pidClean)) {
            cleaned.substring(pidClean.length)
        } else cleaned

        // Find "41 XX" response pattern (Mode 01 response is 41)
        val responsePrefix = "41" + pidClean.substring(2)
        val responseIdx = withoutEcho.indexOf(responsePrefix)
        val dataStr = if (responseIdx >= 0) {
            withoutEcho.substring(responseIdx + responsePrefix.length)
        } else withoutEcho

        return try {
            dataStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    fun parseRPM(raw: String): Int? {
        val bytes = parseResponse(raw, OBD2Commands.ENGINE_RPM) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF)) / 4
    }

    fun parseSpeed(raw: String): Int? {
        val bytes = parseResponse(raw, OBD2Commands.VEHICLE_SPEED) ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0].toInt() and 0xFF
    }

    fun parseCoolantTemp(raw: String): Int? {
        val bytes = parseResponse(raw, OBD2Commands.COOLANT_TEMP) ?: return null
        if (bytes.isEmpty()) return null
        return (bytes[0].toInt() and 0xFF) - 40
    }

    fun parseFuelLevel(raw: String): Int? {
        val bytes = parseResponse(raw, OBD2Commands.FUEL_LEVEL) ?: return null
        if (bytes.isEmpty()) return null
        return ((bytes[0].toInt() and 0xFF) * 100) / 255
    }

    fun parseThrottlePos(raw: String): Int? {
        val bytes = parseResponse(raw, OBD2Commands.THROTTLE_POS) ?: return null
        if (bytes.isEmpty()) return null
        return ((bytes[0].toInt() and 0xFF) * 100) / 255
    }

    fun parseFuelRate(raw: String): Float? {
        val bytes = parseResponse(raw, OBD2Commands.FUEL_RATE) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF)) * 0.05f
    }

    fun parseControlVoltage(raw: String): Float? {
        val bytes = parseResponse(raw, OBD2Commands.CONTROL_VOLTAGE) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF)) / 1000f
    }

    fun parseOdometer(raw: String): Long? {
        val bytes = parseResponse(raw, OBD2Commands.ODOMETER) ?: return null
        if (bytes.size < 4) return null
        val raw32 = ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
        return (raw32 * 0.1).toLong()
    }

    /**
     * Calculate fuel consumption in L/100km
     * fuelRateLH = liters per hour from OBD
     * speedKmh = current speed
     */
    fun calculateFuelConsumption(fuelRateLH: Float, speedKmh: Int): Float {
        if (speedKmh <= 0) return 0f
        return (fuelRateLH / speedKmh) * 100f
    }

    /**
     * Parse supported PIDs bitmask
     */
    fun parseSupportedPIDs(raw: String): Set<Int> {
        val bytes = parseResponse(raw, OBD2Commands.SUPPORTED_PIDS_01_20) ?: return emptySet()
        val supported = mutableSetOf<Int>()
        var pidNum = 1
        for (byte in bytes) {
            for (bit in 7 downTo 0) {
                if ((byte.toInt() and (1 shl bit)) != 0) {
                    supported.add(pidNum)
                }
                pidNum++
            }
        }
        return supported
    }
}
