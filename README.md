# OBD2 Monitor - Android App

אפליקציה לאנדרואיד שמתחברת למתאם OBD2 דרך Bluetooth ומשדרת נתונים ל-Home Assistant דרך MQTT.

## מה האפליקציה עושה

- ✅ מתחברת למתאם ELM327 דרך Bluetooth Classic
- ✅ קוראת נתונים בזמן אמת מה-OBD2 של הרכב
- ✅ שולחת נתונים ל-Home Assistant דרך MQTT
- ✅ Auto Discovery - חיישנים נוצרים אוטומטית ב-HA

## נתונים שנאספים

| נתון | PID | הערה |
|------|-----|-------|
| מהירות | 01 0D | ק"מ/שעה |
| סל"ד | 01 0C | RPM |
| טמפ' מנוע | 01 05 | °C |
| רמת דלק | 01 2F | % |
| צריכת דלק | 01 5E | L/שעה → L/100km |
| מתח סוללה | 01 42 | Volts |
| מצערת | 01 11 | % |
| אודומטר | 01 A6 | ק"מ (רכבים חדשים בלבד!) |

## התקנה

### דרישות
- Android Studio Hedgehog (2023.1) ומעלה
- Android SDK 34
- מכשיר אנדרואיד עם Bluetooth Classic
- מתאם ELM327 Bluetooth (לא BLE!)

### שלבי התקנה

1. **פתח ב-Android Studio:**
   ```
   File → Open → בחר את תיקיית OBD2Monitor
   ```

2. **Gradle Sync** - Android Studio יוריד את כל התלויות אוטומטית

3. **בנה ואינסטל** על המכשיר:
   ```
   Build → Generate Signed APK
   ```
   או הרץ ישירות על מכשיר מחובר.

## הגדרת MQTT

1. פתח את האפליקציה
2. לחץ על תפריט ☰ → **הגדרות MQTT**
3. הכנס:
   - **כתובת Broker**: `tcp://192.168.1.XXX:1883` (IP של שרת HA שלך)
   - שם משתמש וסיסמה (אם מוגדרים ב-Mosquitto)
   - Topic Prefix: `car/obd2` (ברירת מחדל)

## Topics ב-Home Assistant

לאחר חיבור, האפליקציה תיצור אוטומטית את החיישנים ב-HA דרך MQTT Discovery:

```
car/obd2/speed          → מהירות (km/h)
car/obd2/rpm            → סל"ד
car/obd2/engine_temp    → טמפרטורת מנוע (°C)
car/obd2/fuel_level     → רמת דלק (%)
car/obd2/fuel_rate      → צריכת דלק (L/100km)
car/obd2/battery_voltage → מתח סוללה (V)
car/obd2/odometer       → אודומטר (km)
car/obd2/throttle       → מצערת (%)
car/obd2/state          → כל הנתונים ב-JSON
car/obd2/status         → online/offline
```

### הגדרה ידנית ב-configuration.yaml (אם Auto Discovery לא עובד):

```yaml
mqtt:
  sensor:
    - name: "מהירות רכב"
      state_topic: "car/obd2/speed"
      unit_of_measurement: "km/h"
      icon: mdi:speedometer
      
    - name: "רמת דלק"
      state_topic: "car/obd2/fuel_level"
      unit_of_measurement: "%"
      icon: mdi:gas-station
      
    - name: "טמפרטורת מנוע"
      state_topic: "car/obd2/engine_temp"
      unit_of_measurement: "°C"
      device_class: temperature
```

## מבנה הפרויקט

```
app/src/main/java/com/obd2monitor/
├── model/
│   └── Models.kt           # Data classes
├── service/
│   ├── OBD2Commands.kt     # PID definitions + Parser
│   └── OBD2Service.kt      # Foreground service + BT connection
├── mqtt/
│   └── MqttManager.kt      # MQTT client + HA Discovery
└── ui/
    ├── MainActivity.kt      # Main dashboard
    ├── MainViewModel.kt     # ViewModel
    └── SettingsActivity.kt  # MQTT settings
```

## הערות חשובות

### אודומטר
PID A6 הוא סטנדרט OBD2 Mode 01 שנוסף ב-2018. רוב הרכבים מלפני 2018 **לא** תומכים בו.
במקרה כזה, `tvOdometer` יציג "לא זמין".

### ELM327 מזויף
רב מתאמי ה-OBD2 הזולים הם שיבוטים של ELM327. הם בדרך כלל עובדים אבל:
- חלקם לא תומכים בכל ה-PIDs
- חלקם איטיים יותר
- ELM327 מקורי מ-Elmelectronics מומלץ לתוצאות הטובות ביותר

### צריכת דלק
אם הרכב לא תומך ב-PID 5E (Fuel Rate), שדה צריכת הדלק יישאר על `--`.
ניתן לחשב בצורה חלופית דרך MAF + היחס סטוכיומטרי.

## תרומות ובאגים

הפרויקט נבנה כבסיס - מוזמן להרחיב!
