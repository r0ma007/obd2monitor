package com.obd2monitor.service

/**
 * Complete OBD2 PID catalog with descriptions
 * Mode 01 - Current Data
 */
data class PidInfo(
    val pid: String,         // e.g. "010C"
    val name: String,        // Hebrew name
    val nameEn: String,      // English name
    val unit: String,        // Unit of measurement
    val formula: String,     // How to decode
    val minVal: Double = 0.0,
    val maxVal: Double = 100.0
)

object PidCatalog {

    val ALL_PIDS = listOf(
        PidInfo("0100", "PIDs נתמכים 01-20", "Supported PIDs 01-20", "", "bitmask"),
        PidInfo("0101", "סטטוס מוניטור", "Monitor Status", "", "bitmask"),
        PidInfo("0103", "סטטוס דלק", "Fuel System Status", "", "enum"),
        PidInfo("0104", "עומס מנוע", "Engine Load", "%", "A*100/255", 0.0, 100.0),
        PidInfo("0105", "טמפרטורת קירור", "Coolant Temp", "°C", "A-40", -40.0, 215.0),
        PidInfo("0106", "תיקון דלק קצר-טווח בנק 1", "Short Term Fuel Trim B1", "%", "(A-128)*100/128", -100.0, 99.2),
        PidInfo("0107", "תיקון דלק ארוך-טווח בנק 1", "Long Term Fuel Trim B1", "%", "(A-128)*100/128", -100.0, 99.2),
        PidInfo("0108", "תיקון דלק קצר-טווח בנק 2", "Short Term Fuel Trim B2", "%", "(A-128)*100/128", -100.0, 99.2),
        PidInfo("0109", "תיקון דלק ארוך-טווח בנק 2", "Long Term Fuel Trim B2", "%", "(A-128)*100/128", -100.0, 99.2),
        PidInfo("010A", "לחץ דלק", "Fuel Pressure", "kPa", "A*3", 0.0, 765.0),
        PidInfo("010B", "לחץ מניפולד", "Intake Manifold Pressure", "kPa", "A", 0.0, 255.0),
        PidInfo("010C", "סל\"ד מנוע", "Engine RPM", "RPM", "((A*256)+B)/4", 0.0, 16383.75),
        PidInfo("010D", "מהירות רכב", "Vehicle Speed", "km/h", "A", 0.0, 255.0),
        PidInfo("010E", "עיתוי הצתה", "Timing Advance", "°", "A/2-64", -64.0, 63.5),
        PidInfo("010F", "טמפרטורת אוויר נכנס", "Intake Air Temp", "°C", "A-40", -40.0, 215.0),
        PidInfo("0110", "זרימת אוויר MAF", "MAF Air Flow Rate", "g/s", "((A*256)+B)/100", 0.0, 655.35),
        PidInfo("0111", "מיקום מצערת", "Throttle Position", "%", "A*100/255", 0.0, 100.0),
        PidInfo("0112", "סטטוס אוויר משני", "Commanded Secondary Air", "", "enum"),
        PidInfo("0113", "חיישני O2 זמינים", "O2 Sensors Present", "", "bitmask"),
        PidInfo("0114", "חיישן O2 1-1 מתח", "O2 Sensor B1S1 Voltage", "V", "A/200", 0.0, 1.275),
        PidInfo("0115", "חיישן O2 1-2 מתח", "O2 Sensor B1S2 Voltage", "V", "A/200", 0.0, 1.275),
        PidInfo("0116", "חיישן O2 1-3 מתח", "O2 Sensor B1S3 Voltage", "V", "A/200", 0.0, 1.275),
        PidInfo("0117", "חיישן O2 1-4 מתח", "O2 Sensor B1S4 Voltage", "V", "A/200", 0.0, 1.275),
        PidInfo("0118", "חיישן O2 2-1 מתח", "O2 Sensor B2S1 Voltage", "V", "A/200", 0.0, 1.275),
        PidInfo("0119", "חיישן O2 2-2 מתח", "O2 Sensor B2S2 Voltage", "V", "A/200", 0.0, 1.275),
        PidInfo("011A", "חיישן O2 2-3 מתח", "O2 Sensor B2S3 Voltage", "V", "A/200", 0.0, 1.275),
        PidInfo("011B", "חיישן O2 2-4 מתח", "O2 Sensor B2S4 Voltage", "V", "A/200", 0.0, 1.275),
        PidInfo("011C", "תקן OBD", "OBD Standard", "", "enum"),
        PidInfo("011F", "זמן ריצת מנוע", "Engine Run Time", "s", "(A*256)+B", 0.0, 65535.0),
        PidInfo("0120", "PIDs נתמכים 21-40", "Supported PIDs 21-40", "", "bitmask"),
        PidInfo("0121", "מרחק עם נורת MIL", "Distance w/ MIL on", "km", "(A*256)+B", 0.0, 65535.0),
        PidInfo("0122", "לחץ דלק מאייד", "Fuel Rail Pressure (vac)", "kPa", "((A*256)+B)*0.079"),
        PidInfo("0123", "לחץ מסלול דלק", "Fuel Rail Pressure (direct)", "kPa", "((A*256)+B)*10"),
        PidInfo("012C", "פקודת EGR", "Commanded EGR", "%", "A*100/255"),
        PidInfo("012D", "שגיאת EGR", "EGR Error", "%", "(A-128)*100/128"),
        PidInfo("012E", "פקודת ריקון טיהור", "Commanded Evap. Purge", "%", "A*100/255"),
        PidInfo("012F", "רמת דלק", "Fuel Tank Level", "%", "A*100/255", 0.0, 100.0),
        PidInfo("0130", "מרחק מאיפוס שגיאות", "Warm-ups since clear", "count", "A"),
        PidInfo("0131", "מרחק מאיפוס", "Distance since cleared", "km", "(A*256)+B"),
        PidInfo("0132", "לחץ אדי דלק", "Evap System Vapor Pressure", "Pa", "((A*256)+B)/4"),
        PidInfo("0133", "לחץ אטמוספרי", "Barometric Pressure", "kPa", "A"),
        PidInfo("0140", "PIDs נתמכים 41-60", "Supported PIDs 41-60", "", "bitmask"),
        PidInfo("0142", "מתח מודול בקרה", "Control Module Voltage", "V", "((A*256)+B)/1000", 0.0, 65.535),
        PidInfo("0143", "עומס מנוע מוחלט", "Absolute Engine Load", "%", "((A*256)+B)*100/255"),
        PidInfo("0145", "מיקום מצערת יחסי", "Relative Throttle Position", "%", "A*100/255"),
        PidInfo("0146", "טמפרטורת אוויר סביבה", "Ambient Air Temp", "°C", "A-40"),
        PidInfo("0147", "מיקום מצערת מוחלט B", "Absolute Throttle Position B", "%", "A*100/255"),
        PidInfo("0148", "מיקום מצערת מוחלט C", "Absolute Throttle Position C", "%", "A*100/255"),
        PidInfo("0149", "מיקום דוושת גז A", "Accelerator Pedal Position D", "%", "A*100/255"),
        PidInfo("014A", "מיקום דוושת גז B", "Accelerator Pedal Position E", "%", "A*100/255"),
        PidInfo("014C", "פקודת מצערת", "Commanded Throttle Actuator", "%", "A*100/255"),
        PidInfo("014D", "זמן ריצה עם MIL", "Time run with MIL on", "min", "(A*256)+B"),
        PidInfo("014E", "זמן מאיפוס שגיאות", "Time since trouble codes cleared", "min", "(A*256)+B"),
        PidInfo("0151", "סוג דלק", "Fuel Type", "", "enum"),
        PidInfo("0152", "אחוז אתנול", "Ethanol Fuel Percent", "%", "A*100/255"),
        PidInfo("015A", "מיקום מצערת E", "Relative Accelerator Pedal Pos", "%", "A*100/255"),
        PidInfo("015B", "טעינת סוללה היברידית", "Hybrid Battery Pack Life", "%", "A*100/255"),
        PidInfo("015C", "טמפרטורת שמן מנוע", "Engine Oil Temp", "°C", "A-40", -40.0, 210.0),
        PidInfo("015D", "הזרקת דלק זמן", "Fuel Injection Timing", "°", "((A*256)+B)/128-210"),
        PidInfo("015E", "קצב צריכת דלק", "Fuel Rate", "L/h", "((A*256)+B)*0.05", 0.0, 3276.75),
        PidInfo("0160", "PIDs נתמכים 61-80", "Supported PIDs 61-80", "", "bitmask"),
        PidInfo("0161", "עומס מנוע דרישה", "Driver Demand Engine Torque", "%", "A-125"),
        PidInfo("0162", "עומס מנוע בפועל", "Actual Engine Torque", "%", "A-125"),
        PidInfo("0163", "מומנט מנוע מקסימלי", "Engine Reference Torque", "Nm", "(A*256)+B"),
        PidInfo("01A6", "אודומטר", "Odometer", "km", "((A*2^24)+(B*2^16)+(C*2^8)+D)*0.1", 0.0, 429496729.5),
    )

    // Quick lookup by PID string
    private val pidMap = ALL_PIDS.associateBy { it.pid }

    fun getInfo(pid: String): PidInfo? = pidMap[pid.uppercase()]

    // PIDs worth scanning (skip bitmask PIDs that are just capability checks)
    val SCANNABLE_PIDS = ALL_PIDS.filter {
        it.unit.isNotEmpty() && it.formula != "bitmask" && it.formula != "enum"
    }

    // High priority PIDs - the most useful ones
    val HIGH_PRIORITY = setOf(
        "010C", "010D", "0105", "012F", "0110", "0111",
        "0142", "015C", "015E", "015D", "01A6", "010F",
        "0104", "0143", "0149", "014A"
    )
}
