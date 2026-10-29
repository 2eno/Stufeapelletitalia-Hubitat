# Stufe a Pellet Italia – Hubitat-Treiber

Hubitat-Treiber für Pelletöfen, die mit der App **„Stufe a pellet Italia“** gesteuert werden
(Micronova-Steuerung mit Navel-WLAN-Modul, Cloud „Agua IOT“).

Der Treiber nutzt dieselbe Cloud-API wie die App (`https://stufepelletitalia.agua-iot.com`,
Customer-Code `015142`). Die Werte werden über die Registerkarte umgerechnet, die der Server
für deinen Ofen liefert. Deshalb sollte der Treiber mit allen Modellen dieser Marke funktionieren.

## Funktionen

| Hubitat | Bedeutung |
|---|---|
| `switch` on/off, `thermostatMode` heat/off | Ofen ein-/ausschalten |
| `temperature` | Raumtemperatur am Ofen |
| `heatingSetpoint` / `setHeatingSetpoint()` | Raum-Solltemperatur |
| `thermostatOperatingState` | `heating` oder `idle` |
| `powerLevel` / `setPowerLevel()` | Leistungsstufe (Min./Max. in `powerLevelMin`/`powerLevelMax`) |
| `stoveStatus`, `stoveStatusCode` | Betriebszustand im Klartext (z.B. START, WORK, ECO STOP) |
| `alarm`, `alarmCode` | aktueller Alarm |
| `flueGasTemperature`, `realPower`, `exhaustFanSpeed`, `pelletsLow`, Betriebsstunden … | nur wenn dein Ofen das Register hat |
| `waterTemperature`, `waterSetpoint` / `setWaterSetpoint()` | bei wassergeführten Öfen |
| `syncClock` | Ofenuhr auf Hub-Zeit stellen |
| `setRegister(key, value)` / `listRegisters` | Expertenzugriff auf alle Register |
| `cloudStatus`, `lastUpdate` | Verbindungsstatus |

Weil der Treiber die Hubitat-Capability *Thermostat* hat, taucht der Ofen auch in Dashboards,
Rule Machine, Alexa, Google Home und HomeKit als Thermostat auf.

## Installation

1. Hubitat-Oberfläche öffnen, dann **Drivers Code → New Driver**.
2. Den Inhalt von [`drivers/stufe-a-pellet-italia.groovy`](drivers/stufe-a-pellet-italia.groovy) einfügen und **Save** klicken.
3. **Devices → Add Device → Virtual** wählen, einen Namen vergeben (z.B. „Pelletofen“) und als Typ
   **Stufe a Pellet Italia (Agua IOT)** auswählen.
4. Auf der Geräteseite **E-Mail** und **Passwort** des App-Kontos eintragen und **Save Preferences** klicken.
5. Nach ein paar Sekunden sollte `cloudStatus` den Wert `online` zeigen und die Werte erscheinen.

Tipp: Unter *State Variables* stehen nach dem ersten Abruf die verfügbaren Sprachcodes
(`availableLanguages`). Steht dort zum Beispiel `GER` oder `DEU`, kannst du diesen Code in der
Einstellung *Sprache der Statustexte* eintragen. Dann erscheinen die Statustexte auf Deutsch.

## Hinweise

- **Die App funktioniert weiter.** Der Treiber meldet sich als eigene „App-Instanz“ an.
- **Abfrageintervall:** Jede Abfrage weckt das WLAN-Modul des Ofens. Fünf Minuten sind ein guter Wert,
  kürzere Intervalle belasten Cloud und Modul unnötig.
- **Mehrere Öfen:** Lege für jeden Ofen ein eigenes Gerät an und trage den Ofennamen oder die
  Seriennummer unter *Ofen* ein.
- **Fahrenheit:** Wenn der Hub auf °F steht, rechnet der Treiber automatisch um.
- **Fehlersuche:** Debug-Logging einschalten, `refresh` ausführen und die Logs ansehen.
  `listRegisters` gibt alle Register mit ihren aktuellen Werten aus.

### Beispiel-Regeln (Rule Machine)

- Ofen um 6:00 einschalten, wenn jemand zu Hause ist: *Actions → Switch → on*.
- Benachrichtigung, wenn `alarm` sich ändert und nicht „NO ALARM“ ist, oder wenn `pelletsLow` = true.
- Sollwert abhängig vom Modus „Nacht“ über `setHeatingSetpoint(18)` setzen.

## Technik

Das Protokoll wurde aus der App (Version 2.1.3, Flutter) ermittelt und stimmt mit dem
Home-Assistant-Projekt [home_assistant_micronova_agua_iot](https://github.com/vincentwolsink/home_assistant_micronova_agua_iot) (Apache-2.0) überein:

`appSignup` → `userLogin` (JWT) → `deviceList` → `deviceGetInfo` → `deviceGetRegistersMap`, dann
zyklisch `deviceGetBufferReading` + `deviceJobStatus/{id}`. Geschrieben wird mit `deviceRequestWriting`
(Protokoll `RWMSmaster`). Die Registerformeln (z.B. `IF(#>32767,(#-65536)/10,#/10)`) wertet ein
eigener kleiner Parser aus, weil `eval` auf Hubitat nicht erlaubt ist.
