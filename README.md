# Stufe a Pellet Italia – Hubitat driver

*English · [Deutsch](README.de.md)*

A Hubitat driver for pellet stoves controlled with the **“Stufe a pellet Italia”** app
(Micronova controller with Navel Wi-Fi module, “Agua IOT” cloud).

The driver uses the same cloud API as the app (`https://stufepelletitalia.agua-iot.com`,
customer code `015142`). Values are converted using the register map the server supplies for
your stove. The driver should therefore work with all models of this brand.

## Features

| Hubitat | Meaning |
|---|---|
| `switch` on/off, `thermostatMode` heat/off | Switch the stove on/off |
| `temperature` | Room temperature at the stove |
| `heatingSetpoint` / `setHeatingSetpoint()` | Room target temperature |
| `thermostatOperatingState` | `heating` or `idle` |
| `powerLevel` / `setPowerLevel()` | Power level (min/max in `powerLevelMin`/`powerLevelMax`) |
| `stoveStatus`, `stoveStatusCode` | Operating state as text (e.g. START, WORK, ECO STOP) |
| `alarm`, `alarmCode` | Current alarm |
| `flueGasTemperature`, `realPower`, `exhaustFanSpeed`, `pelletsLow`, working hours … | Only if your stove has the register |
| `waterTemperature`, `waterSetpoint` / `setWaterSetpoint()` | For boiler (water-heating) stoves |
| `syncClock` | Set the stove clock to hub time |
| `setRegister(key, value)` / `listRegisters` | Expert access to all registers |
| `cloudStatus`, `lastUpdate` | Connection status |

Because the driver has the Hubitat *Thermostat* capability, the stove also shows up as a
thermostat in dashboards, Rule Machine, Alexa, Google Home and HomeKit.

## Installation

1. Open the Hubitat web UI, then **Drivers Code → New Driver**.
2. Paste the contents of [`drivers/stufe-a-pellet-italia.groovy`](drivers/stufe-a-pellet-italia.groovy) and click **Save**.
3. Choose **Devices → Add Device → Virtual**, give it a name (e.g. “Pellet stove”) and select
   **Stufe a Pellet Italia (Agua IOT)** as the type.
4. On the device page, enter the **Email** and **Password** of the app account and click **Save Preferences**.
5. After a few seconds `cloudStatus` should show `online` and the values should appear.

Tip: after the first refresh, *State Variables* lists the available language codes
(`availableLanguages`). Enter one of them, e.g. `ENG`, `ITA` or `GER`, in the preference
*Language of status texts* to get the stove’s status texts in that language.

## Notes

- **The app keeps working.** The driver signs in as its own “app instance”.
- **Polling interval:** every poll wakes up the stove’s Wi-Fi module. Five minutes is a good value;
  shorter intervals put unnecessary load on the cloud and the module.
- **Several stoves:** create one device per stove and enter the stove name or serial number under
  *Stove (name or serial number)*.
- **Fahrenheit:** if the hub is set to °F, the driver converts automatically.
- **Troubleshooting:** enable debug logging, run `refresh` and check the logs. `listRegisters`
  prints all registers with their current values.

### Example rules (Rule Machine)

- Switch the stove on at 6:00 if someone is home: *Actions → Switch → on*.
- Notify when `alarm` changes and is not “NO ALARM”, or when `pelletsLow` = true.
- Set the setpoint depending on the “Night” mode via `setHeatingSetpoint(18)`.

## Technical details

The protocol was derived from the app (version 2.1.3, Flutter) and matches the Home Assistant
project [home_assistant_micronova_agua_iot](https://github.com/vincentwolsink/home_assistant_micronova_agua_iot) (Apache-2.0):

`appSignup` → `userLogin` (JWT) → `deviceList` → `deviceGetInfo` → `deviceGetRegistersMap`, then
periodically `deviceGetBufferReading` + `deviceJobStatus/{id}`. Writes use `deviceRequestWriting`
(protocol `RWMSmaster`). The register formulas (e.g. `IF(#>32767,(#-65536)/10,#/10)`) are evaluated
by a small parser of its own, because `eval` is not allowed on Hubitat.
