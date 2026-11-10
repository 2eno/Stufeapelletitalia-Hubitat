/**
 *  Stufe a Pellet Italia – pellet stove (Micronova Agua IOT cloud)
 *
 *  Hubitat driver for pellet stoves controlled with the app "Stufe a pellet Italia"
 *  (Micronova Navel Wi-Fi module, Agua IOT cloud).
 *
 *  The driver talks to the same cloud API as the app:
 *    https://stufepelletitalia.agua-iot.com  (customer_code 015142)
 *
 *  Flow: appSignup -> userLogin -> deviceList -> deviceGetInfo ->
 *  deviceGetRegistersMap (once), then periodically
 *  deviceGetBufferReading + deviceJobStatus. Writes use
 *  deviceRequestWriting. All values are converted using the register map
 *  supplied by the server (offset, mask, formula), so the driver works
 *  for all stoves of this brand.
 *
 *  Protocol derived from the app (v2.1.3) and the project
 *  home_assistant_micronova_agua_iot (Apache-2.0).
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.0.0"

@Field static final String DEFAULT_API_URL = "https://stufepelletitalia.agua-iot.com"
@Field static final String DEFAULT_CUSTOMER_CODE = "015142"
@Field static final String DEFAULT_BRAND_ID = "1"
@Field static final String REGISTERS_MAP_LAST_UPDATE = "2018-06-03T08:59:54.043"

// Raw value the Wi-Fi module returns when reading a register failed
@Field static final long READING_ERROR_VALUE = 0x8000

@Field static final List AIR_VARIANTS = ["air", "air2", "air3", "air_palm"]
@Field static final List WATER_VARIANTS = ["water", "water_boiler", "water_puffer", "h2o", "h2o_mandata"]

@Field static final List STATUS_OFF = ["OFF", "FINAL CLEANING", "STOP", "SHUT OFF", "0", "6"]
@Field static final List STATUS_IDLE = [
    "ECO STOP", "STANDBY", "STAND BY", "STAND-BY", "ALARM", "MEMORY ALARM",
    "ALARM MEMORY", "MEM.ALM", "MEM. ALARM", "7", "8", "9"
]

// Fallback if the register map has no texts (enc_val)
@Field static final Map STATUS_FALLBACK = [
    0: "OFF", 1: "START", 2: "LOAD PELLETS", 3: "FLAME LIGHT", 4: "ON",
    5: "CLEANING FIRE-POT", 6: "FINAL CLEANING", 7: "ECO STOP", 8: "PELLETS DEPLETED",
    9: "IGNITION FAILED", 10: "ALARM", 11: "MEM.ALM"
]
@Field static final Map ALARM_FALLBACK = [
    0: "OK", 1: "BLACK OUT", 2: "NO SWITCH ON", 3: "PELLETS FINISHED", 4: "SMOKE TEMPERATURE",
    5: "EXTRACTOR ROTATIONS NOT RESPECTED", 6: "FAULTY SMOKE EXTRACTOR",
    7: "PELLET LOADING GEARMOTOR ROTATIONS NOT RESPECTED", 8: "PELLET LOADING GEARMOTOR FAULT",
    9: "PELLET LOADING AUGER BLOCKED", 10: "PELLET LOADING AUGER POWER SUPPLY DEFECT",
    11: "MINIMUM MEASURE PASCAL", 12: "BRAZIER CLEANER FAULT", 13: "NEGATIVE PRESSURE IN CHIMNEY FLUE",
    14: "THERMOSTAT MANUAL RESET", 15: "FIRE DOOR / ASH PAN OPEN", 16: "PELLET TANK DOOR OPEN",
    18: "FLAME PROBE", 22: "FLAME TEMPERATURE", 23: "AUGER TRIAC", 24: "AUGER PHASE",
    28: "REVOLUTION FAILURE SMOKE ENCODER", 29: "CYCLE LIMIT CLEANING"
]

// Additional readings: attribute -> register key (only if the stove supports it)
@Field static final Map EXTRA_NUMBERS = [
    realPower         : "real_power_get",
    exhaustFanSpeed   : "giri_estrattore_get",
    pressure          : "pascal_get",
    totalWorkingHours : "ore_lavoro_tot_get",
    partialWorkingHours: "ore_lavoro_par_get",
    hoursToService    : "ore_service_get",
    frontFanSpeed     : "vent_front2_get",
    rearFanSpeed      : "vent_rear2_get"
]

metadata {
    definition(name: "Stufe a Pellet Italia (Agua IOT)", namespace: "2eno", author: "2eno") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "Refresh"
        capability "Initialize"

        attribute "stoveStatus", "string"
        attribute "stoveStatusCode", "number"
        attribute "alarm", "string"
        attribute "alarmCode", "number"
        attribute "powerLevel", "number"
        attribute "powerLevelName", "string"
        attribute "powerLevelMin", "number"
        attribute "powerLevelMax", "number"
        attribute "realPower", "number"
        attribute "flueGasTemperature", "number"
        attribute "waterTemperature", "number"
        attribute "waterSetpoint", "number"
        attribute "exhaustFanSpeed", "number"
        attribute "frontFanSpeed", "number"
        attribute "rearFanSpeed", "number"
        attribute "pressure", "number"
        attribute "totalWorkingHours", "number"
        attribute "partialWorkingHours", "number"
        attribute "hoursToService", "number"
        attribute "pelletsLow", "enum", ["true", "false"]
        attribute "cloudStatus", "string"
        attribute "lastUpdate", "string"

        command "setPowerLevel", [[name: "level*", type: "NUMBER", description: "Power level (e.g. 1-5)"]]
        command "setWaterSetpoint", [[name: "temperature*", type: "NUMBER", description: "Water target temperature"]]
        command "syncClock"
        command "setRegister", [
            [name: "registerKey*", type: "STRING", description: "Register key, e.g. power_set"],
            [name: "value*", type: "STRING", description: "Value (number or text according to the register map)"]
        ]
        command "listRegisters"
        command "reloadRegisterMap"
        command "resetSession"
    }

    preferences {
        input name: "email", type: "text", title: "Email", description: "Login of the “Stufe a pellet Italia” app", required: true
        input name: "password", type: "password", title: "Password", required: true
        input name: "deviceSelector", type: "text", title: "Stove (name or serial number)",
              description: "Only needed if the account has several stoves. Empty = first stove.", required: false
        input name: "pollInterval", type: "enum", title: "Polling interval", defaultValue: "5",
              options: ["0": "manual only", "1": "1 minute", "2": "2 minutes", "5": "5 minutes",
                        "10": "10 minutes", "15": "15 minutes", "30": "30 minutes", "60": "60 minutes"]
        input name: "language", type: "text", title: "Language of status texts", defaultValue: "ENG",
              description: "Language code of the register map (e.g. ENG, ITA, GER). Available codes are listed under “State Variables” after the first refresh."
        input name: "airTempFix", type: "bool", title: "Ignore room temperatures > 100", defaultValue: false,
              description: "For stoves that misuse the temperature register."
        input name: "readingErrorFix", type: "bool", title: "Ignore read errors (0x8000)", defaultValue: false
        input name: "apiUrl", type: "text", title: "API URL (expert)", defaultValue: DEFAULT_API_URL, required: false
        input name: "customerCode", type: "text", title: "Customer code (expert)", defaultValue: DEFAULT_CUSTOMER_CODE, required: false
        input name: "brandId", type: "text", title: "Brand ID (expert)", defaultValue: DEFAULT_BRAND_ID, required: false
        input name: "httpTimeout", type: "number", title: "HTTP timeout (seconds)", defaultValue: 30, range: "5..120"
        input name: "bufferReadTimeout", type: "number", title: "Max. wait for stove response (seconds)", defaultValue: 30, range: "5..120"
        input name: "logEnable", type: "bool", title: "Debug logging (turns off after 30 min)", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Description text logging", defaultValue: true
    }
}

/* ======================================================================
 *  Lifecycle
 * ====================================================================== */

void installed() {
    log.info "${device.displayName}: installed (driver ${DRIVER_VERSION})"
    sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(["heat", "off"]))
    sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(["auto"]))
    sendEvent(name: "thermostatFanMode", value: "auto")
    sendEvent(name: "cloudStatus", value: "not configured")
}

void updated() {
    log.info "${device.displayName}: preferences saved"
    String fingerprint = [settings.email, settings.password, apiUrl(), customerCode(), brandId(), settings.deviceSelector].join("|").hashCode().toString()
    if (state.configFingerprint != fingerprint) {
        clearSession(true)
        state.configFingerprint = fingerprint
    }
    sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(["heat", "off"]))
    sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(["auto"]))
    if (settings.logEnable) runIn(1800, "logsOff")
    initialize()
}

void initialize() {
    state.remove("readStarted")
    schedulePolling()
    runIn(2, "refresh")
}

void uninstalled() {
    unschedule()
}

void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void schedulePolling() {
    unschedule("poll")
    int minutes = (settings.pollInterval ?: "5") as int
    if (minutes <= 0) return
    int second = new Random().nextInt(60)
    if (minutes >= 60) {
        schedule("${second} ${new Random().nextInt(60)} * ? * *", "poll")
    } else {
        schedule("${second} */${minutes} * ? * *", "poll")
    }
}

/* ======================================================================
 *  Commands
 * ====================================================================== */

void poll() {
    readDevice()
}

void refresh() {
    readDevice()
}

void on() {
    writeOnOff(true)
}

void off() {
    writeOnOff(false)
}

void heat() {
    writeOnOff(true)
}

void setThermostatMode(String mode) {
    switch (mode) {
        case "heat":
        case "emergency heat":
        case "auto":
            writeOnOff(true)
            break
        case "off":
            writeOnOff(false)
            break
        default:
            log.warn "${device.displayName}: mode '${mode}' is not supported (heat/off only)"
    }
}

void auto() { setThermostatMode("auto") }
void emergencyHeat() { setThermostatMode("emergency heat") }
void cool() { log.warn "${device.displayName}: cooling is not supported" }
void setCoolingSetpoint(temperature) { log.warn "${device.displayName}: cooling setpoint is not supported" }
void fanAuto() { sendEvent(name: "thermostatFanMode", value: "auto") }
void fanCirculate() { log.warn "${device.displayName}: fan mode is not supported" }
void fanOn() { log.warn "${device.displayName}: fan mode is not supported" }
void setThermostatFanMode(String mode) { if (mode != "auto") log.warn "${device.displayName}: fan mode '${mode}' is not supported" }
void setSchedule(schedule) { log.warn "${device.displayName}: please set up schedules in the app or with Rule Machine" }

void setHeatingSetpoint(temperature) {
    if (!ensureSession()) return
    String key = temperatureKey(AIR_VARIANTS, "set") ?: (state.regs?.containsKey("temp_air_set") ? "temp_air_set" : null)
    if (!key) {
        log.warn "${device.displayName}: stove has no register for the room target temperature"
        return
    }
    writeRegisters([(key): toCelsius(temperature as BigDecimal)], false, "target temperature ${temperature}°")
}

void setWaterSetpoint(temperature) {
    if (!ensureSession()) return
    String key = temperatureKey(WATER_VARIANTS, "set")
    if (!key) {
        log.warn "${device.displayName}: stove has no register for the water target temperature"
        return
    }
    writeRegisters([(key): toCelsius(temperature as BigDecimal)], false, "water target temperature ${temperature}°")
}

void setPowerLevel(level) {
    if (!ensureSession()) return
    writeRegisters([(powerKey()): level as BigDecimal], false, "power level ${level}")
}

void syncClock() {
    if (!ensureSession()) return
    Calendar c = Calendar.getInstance(location?.timeZone ?: TimeZone.getDefault())
    c.setTimeInMillis(now())
    Map items = [
        clock_hour_set    : c.get(Calendar.HOUR_OF_DAY),
        clock_minute_set  : c.get(Calendar.MINUTE),
        calendar_day_set  : c.get(Calendar.DAY_OF_MONTH),
        calendar_month_set: c.get(Calendar.MONTH) + 1,
        calendar_year_set : c.get(Calendar.YEAR)
    ].findAll { k, v -> state.regs?.containsKey(k) }
    if (!items) {
        log.warn "${device.displayName}: stove has no clock registers"
        return
    }
    writeRegisters(items, true, "clock")
}

void setRegister(String key, String value) {
    if (!ensureSession()) return
    key = key?.trim()?.toLowerCase()
    Map reg = state.regs?.get(key)
    if (!reg) {
        log.warn "${device.displayName}: register '${key}' does not exist (see listRegisters)"
        return
    }
    def numeric = valueForDescription(key, value?.trim())
    if (numeric == null) {
        log.warn "${device.displayName}: '${value}' is not a valid value for ${key}"
        return
    }
    writeRegisters([(key): numeric], false, "${key} = ${value}")
}

void listRegisters() {
    if (!ensureSession()) return
    StringBuilder sb = new StringBuilder("${device.displayName}: ${state.regs.size()} registers<br>")
    state.regs.keySet().sort().each { String key ->
        Map r = state.regs[key]
        def v = regValue(key)
        def d = regDescription(key, lang())
        sb << "${key} [offset ${r.o}, ${r.t ?: '-'}, ${r.mn}..${r.mx}] = ${v == null ? '-' : fmtNum(v)}"
        if (d != null && d.toString() != fmtNum(v)) sb << " (${d})"
        sb << "<br>"
    }
    log.info sb.toString()
}

void reloadRegisterMap() {
    state.remove("regs")
    state.remove("raw")
    refresh()
}

void resetSession() {
    clearSession(true)
    refresh()
}

/* ======================================================================
 *  Cloud session (synchronous, only when needed)
 * ====================================================================== */

private String apiUrl() { (settings.apiUrl ?: DEFAULT_API_URL).trim().replaceAll(/\/+$/, "") }
private String customerCode() { (settings.customerCode ?: DEFAULT_CUSTOMER_CODE).trim() }
private String brandId() { (settings.brandId ?: DEFAULT_BRAND_ID).trim() }
private int httpTimeout() { (settings.httpTimeout ?: 30) as int }
private int bufferReadTimeout() { (settings.bufferReadTimeout ?: 30) as int }
private String lang() { (settings.language ?: "ENG").trim().toUpperCase() }

private Map baseHeaders() {
    [
        "Accept"       : "application/json, text/javascript, */*; q=0.01",
        "Origin"       : "file://",
        "id_brand"     : brandId(),
        "customer_code": customerCode()
    ]
}

private Map authHeaders() {
    baseHeaders() + ["local": "false", "Authorization": state.token ?: ""]
}

private void clearSession(boolean includeDevice) {
    ["appRegistered", "token", "refreshToken", "tokenExp", "readStarted"].each { state.remove(it) }
    if (includeDevice) {
        ["idDevice", "idProduct", "idRegistersMap", "productSerial", "nameProduct", "regs", "raw",
         "availableDevices", "availableLanguages"].each { state.remove(it) }
    }
}

private boolean ensureSession() {
    if (!settings.email || !settings.password) {
        log.warn "${device.displayName}: please enter email and password in the preferences"
        setCloudStatus("not configured")
        return false
    }
    if (!state.appUuid) state.appUuid = UUID.randomUUID().toString()
    if (!state.appRegistered && !registerApp()) return false
    if (!state.token) {
        if (!login()) return false
    } else if (tokenExpired() && !renewToken()) {
        return false
    }
    if (!state.idDevice && !fetchDevice()) return false
    if (!state.regs && !fetchRegistersMap()) return false
    return true
}

private boolean tokenExpired() {
    state.tokenExp != null && (now() / 1000) > ((state.tokenExp as long) - 60)
}

private boolean renewToken() {
    if (state.refreshToken) {
        Map res = syncPost("/refreshToken", [refresh_token: state.refreshToken], baseHeaders())
        if (res.status == 201 && res.data?.token) {
            state.token = res.data.token
            state.tokenExp = jwtExp(state.token)
            logDebug "token renewed"
            return true
        }
        log.warn "${device.displayName}: token renewal failed (${res.status}), logging in again"
    }
    return login()
}

private boolean registerApp() {
    Map payload = [
        phone_type             : "Android",
        phone_id               : state.appUuid,
        phone_version          : "1.0",
        language               : "en",
        id_app                 : state.appUuid,
        push_notification_token: state.appUuid,
        push_notification_active: false
    ]
    Map res = syncPost("/appSignup", payload, baseHeaders())
    if (res.status != 201) {
        log.error "${device.displayName}: app signup failed (${res.status}) ${res.data ?: res.error ?: ''}"
        setCloudStatus("error: app signup failed")
        return false
    }
    state.appRegistered = true
    logDebug "app ID registered"
    return true
}

private boolean login() {
    Map res = syncPost("/userLogin", [email: settings.email, password: settings.password],
                       baseHeaders() + ["local": "true", "Authorization": state.appUuid])
    if (res.status != 200 || !res.data?.token) {
        if (res.status == 401 || res.status == 403 || res.status == 404) {
            log.error "${device.displayName}: login failed – check email/password (${res.status})"
            setCloudStatus("error: login failed")
        } else {
            log.error "${device.displayName}: login failed (${res.status}) ${res.data ?: res.error ?: ''}"
            setCloudStatus("error: cloud unreachable")
        }
        // On errors, register a new app ID on the next attempt
        state.remove("appRegistered")
        return false
    }
    state.token = res.data.token
    state.refreshToken = res.data.refresh_token
    state.tokenExp = jwtExp(state.token)
    logDebug "login successful"
    return true
}

private boolean fetchDevice() {
    Map res = authPost("/deviceList", [:])
    List devices = res.data?.device instanceof List ? res.data.device : null
    if (res.status != 200 || devices == null) {
        log.error "${device.displayName}: could not load device list (${res.status})"
        setCloudStatus("error: device list failed")
        return false
    }
    if (!devices) {
        log.error "${device.displayName}: no stove is registered in the account"
        setCloudStatus("error: no stove in account")
        return false
    }
    state.availableDevices = devices.collect { "${it.name} (serial ${it.product_serial}, ${it.name_product})".toString() }

    Map dev = devices[0]
    String sel = settings.deviceSelector?.trim()?.toLowerCase()
    if (sel) {
        dev = devices.find { d ->
            [d.name, d.product_serial, d.id_device, d.id].any { it != null && it.toString().toLowerCase() == sel }
        }
        if (!dev) {
            log.error "${device.displayName}: stove '${settings.deviceSelector}' not found. Available: ${state.availableDevices}"
            setCloudStatus("error: stove not found")
            return false
        }
    }

    def idRegistersMap = dev.id_registers_map
    Map info = authPost("/deviceGetInfo", [id_device: dev.id_device, id_product: dev.id_product])
    if (info.status == 200 && info.data?.device_info instanceof List && info.data.device_info) {
        idRegistersMap = info.data.device_info[0].id_registers_map ?: idRegistersMap
    } else {
        log.warn "${device.displayName}: deviceGetInfo failed (${info.status}), using data from the device list"
    }

    state.idDevice = dev.id_device
    state.idProduct = dev.id_product
    state.productSerial = dev.product_serial
    state.nameProduct = dev.name_product
    state.idRegistersMap = idRegistersMap
    device.updateDataValue("stoveName", dev.name?.toString())
    device.updateDataValue("model", dev.name_product?.toString())
    device.updateDataValue("serial", dev.product_serial?.toString())
    if (dev.is_online == false) log.warn "${device.displayName}: the cloud reports the stove as offline"
    log.info "${device.displayName}: connected to stove '${dev.name}' (${dev.name_product})"
    return true
}

private boolean fetchRegistersMap() {
    Map res = authPost("/deviceGetRegistersMap",
                       [id_device: state.idDevice, id_product: state.idProduct, last_update: REGISTERS_MAP_LAST_UPDATE])
    List maps = res.data?.device_registers_map?.registers_map
    if (res.status != 200 || !(maps instanceof List)) {
        log.error "${device.displayName}: could not load register map (${res.status})"
        setCloudStatus("error: registers map failed")
        return false
    }
    Map regMap = maps.find { it.id?.toString() == state.idRegistersMap?.toString() }
    if (!regMap && maps.size() == 1) regMap = maps[0]
    if (!regMap) {
        log.error "${device.displayName}: register map ${state.idRegistersMap} missing from the response"
        setCloudStatus("error: registers map failed")
        return false
    }

    String wanted = lang()
    Set languages = [] as Set
    Map regs = [:]
    regMap.registers?.each { Map reg ->
        if (reg.reg_key == null || reg.offset == null) return
        Map r = [
            o : reg.offset as int,
            m : reg.mask != null ? reg.mask as long : 0xFFFFL,
            f : reg.formula ?: "#",
            fi: reg.formula_inverse ?: "#",
            mn: reg.set_min,
            mx: reg.set_max,
            t : reg.reg_type
        ]
        if (reg.format_string) r.fs = reg.format_string
        if (reg.is_hex) r.h = true
        if (reg.step != null) r.st = reg.step
        if (reg.enc_val instanceof List) {
            Map enc = [:]
            reg.enc_val.each { e ->
                if (e.lang == null) return
                languages << e.lang
                if (e.lang == "ENG" || e.lang == wanted) {
                    Map perLang = enc[e.lang] ?: [:]
                    perLang[fmtNum(e.value as BigDecimal)] = e.description?.toString()
                    enc[e.lang] = perLang
                }
            }
            if (enc) r.e = enc
        }
        if (reg.enable_val instanceof List) {
            r.ev = reg.enable_val.collect { fmtNum(it.value as BigDecimal) }
        }
        regs[reg.reg_key.toString().toLowerCase()] = r
    }
    if (!regs) {
        log.error "${device.displayName}: register map is empty"
        return false
    }
    state.regs = regs
    state.availableLanguages = languages.sort().join(", ")
    log.info "${device.displayName}: register map loaded (${regs.size()} registers, languages: ${state.availableLanguages})"
    return true
}

private Map authPost(String path, Map body) {
    Map res = syncPost(path, body, authHeaders())
    if (res.status == 401 && renewToken()) {
        res = syncPost(path, body, authHeaders())
    }
    return res
}

private Map syncPost(String path, Map body, Map headers) {
    Map result = [status: null, data: null]
    Map params = [
        uri               : apiUrl() + path,
        headers           : headers,
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : body,
        timeout           : httpTimeout()
    ]
    logDebug "POST ${path}"
    try {
        httpPost(params) { resp ->
            result.status = resp.status
            result.data = resp.data
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        result.status = e.statusCode
        try { result.data = e.response?.data } catch (ignored) { }
    } catch (Exception e) {
        result.error = e.message
        log.error "${device.displayName}: connection to ${apiUrl()} failed: ${e.message}"
    }
    logDebug "POST ${path} -> ${result.status}"
    return result
}

private Long jwtExp(String token) {
    try {
        String part = token.tokenize(".")[1].replace("-", "+").replace("_", "/")
        while (part.length() % 4) part += "="
        Map claims = new JsonSlurper().parseText(new String(part.decodeBase64(), "UTF-8"))
        return claims.exp != null ? claims.exp as long : null
    } catch (Exception e) {
        logDebug "token without readable expiry: ${e.message}"
        return null
    }
}

/* ======================================================================
 *  Asynchronous read / write (deviceJobStatus polling)
 * ====================================================================== */

private void asyncApi(String method, String path, Map body, String handler, Map data) {
    Map params = [
        uri               : apiUrl() + path,
        headers           : authHeaders(),
        contentType       : "application/json",
        requestContentType: "application/json",
        timeout           : httpTimeout()
    ]
    if (method == "POST") params.body = body ?: [:]
    Map pass = (data ?: [:]) + [handler: handler, method: method, path: path, body: body]
    logDebug "${method} ${path} (async)"
    if (method == "POST") {
        asynchttpPost("asyncResponse", params, pass)
    } else {
        asynchttpGet("asyncResponse", params, pass)
    }
}

void asyncResponse(resp, Map data) {
    int status = 0
    try { status = resp.getStatus() as int } catch (ignored) { }
    if (status == 401 && !data.retried) {
        logDebug "401 on ${data.path}, renewing token"
        if (renewToken()) {
            asyncApi(data.method, data.path, data.body, data.handler, data + [retried: true])
            return
        }
    }
    Map json = null
    if (status == 200 || status == 201) {
        try {
            def parsed = new JsonSlurper().parseText(resp.getData())
            json = parsed instanceof Map ? parsed : null
        } catch (Exception e) {
            logDebug "response from ${data.path} not readable: ${e.message}"
        }
    } else {
        String err = null
        try { err = resp.getErrorMessage() } catch (ignored) { }
        logDebug "${data.path} -> ${status} ${err ?: ''}"
    }
    switch (data.handler) {
        case "readRequested": readRequested(status, json, data); break
        case "writeRequested": writeRequested(status, json, data); break
        case "jobStatus": jobStatus(status, json, data); break
    }
}

private void readDevice() {
    Long started = state.readStarted as Long
    long maxRuntime = (bufferReadTimeout() + 3L * httpTimeout()) * 1000L
    if (started && now() - started < maxRuntime) {
        logDebug "poll already running"
        return
    }
    if (!ensureSession()) return
    state.readStarted = now()
    asyncApi("POST", "/deviceGetBufferReading",
             [id_device: state.idDevice, id_product: state.idProduct, BufferId: 1], "readRequested", [:])
}

private void readRequested(int status, Map json, Map data) {
    if (status != 200 || !json?.idRequest) {
        state.remove("readStarted")
        log.warn "${device.displayName}: read request rejected (${status})"
        setCloudStatus(status == 0 ? "error: cloud unreachable" : "error: read request failed")
        return
    }
    runInMillis(1000, "pollJob", [overwrite: false,
        data: [kind: "read", id: json.idRequest.toString(), attempt: 1, started: now()]])
}

private void writeRequested(int status, Map json, Map data) {
    if (status != 200 || !json?.idRequest) {
        log.error "${device.displayName}: could not send ${data.label} (${status})"
        setCloudStatus("error: write request failed")
        return
    }
    runInMillis(1000, "pollJob", [overwrite: false,
        data: [kind: "write", id: json.idRequest.toString(), attempt: 1, started: now(), label: data.label]])
}

void pollJob(Map data) {
    asyncApi("GET", "/deviceJobStatus/${data.id}", null, "jobStatus", data.findAll { k, v -> k != "retried" })
}

private void jobStatus(int status, Map json, Map data) {
    String jobState = json?.jobAnswerStatus
    int attempt = data.attempt as int
    if (data.kind == "read") {
        boolean pending = (json == null || jobState == "waiting")
        if (pending && now() - (data.started as long) < bufferReadTimeout() * 1000L) {
            // like the app: the wait between polls increases
            runInMillis(Math.min(attempt + 1, 5) * 1000L, "pollJob", [overwrite: false, data: data + [attempt: attempt + 1]])
            return
        }
        state.remove("readStarted")
        if (jobState == "completed" && json.jobAnswerData?.Items instanceof List) {
            processBuffer(json.jobAnswerData)
        } else if (pending) {
            log.warn "${device.displayName}: stove did not respond within ${bufferReadTimeout()} s"
            setCloudStatus("stove not responding")
        } else {
            log.warn "${device.displayName}: read failed (status '${jobState}')"
            setCloudStatus("error: read failed")
        }
        return
    }

    // Writing: up to 10 retries, one per second
    if (jobState != "completed" && attempt <= 10) {
        runInMillis(1000, "pollJob", [overwrite: false, data: data + [attempt: attempt + 1]])
        return
    }
    if (jobState == "completed" && json.jobAnswerData instanceof Map && json.jobAnswerData.containsKey("Cmd")) {
        if (settings.txtEnable) log.info "${device.displayName}: ${data.label} applied"
        setCloudStatus("online")
        runIn(3, "refresh")
    } else {
        log.error "${device.displayName}: ${data.label} was not confirmed by the stove (status '${jobState}')"
        setCloudStatus("error: write not confirmed")
    }
}

private void writeOnOff(boolean turnOn) {
    if (!ensureSession()) return
    String key = "status_managed_get"
    if (!state.regs.containsKey(key)) {
        log.warn "${device.displayName}: stove has no register for switching on/off"
        return
    }
    def value = valueForDescription(key, turnOn ? "ON" : "OFF", "ENG")
    if (value == null) value = turnOn ? 85 : 170
    writeRegisters([(key): value], false, turnOn ? "Einschalten" : "Ausschalten")
}

private void writeRegisters(Map items, boolean limitValueRaw, String label) {
    List offsets = [], masks = [], values = [], bits = [], endians = []
    try {
        items.each { String key, value ->
            Map reg = state.regs[key]
            if (!reg) throw new IllegalArgumentException("unknown register ${key}")
            offsets << (reg.o as int)
            masks << (reg.m as long)
            values << prepareValue(key, value as BigDecimal, limitValueRaw)
            bits << 8
            endians << "L"
        }
    } catch (IllegalArgumentException e) {
        log.warn "${device.displayName}: ${label} not possible: ${e.message}"
        return
    }
    logDebug "writing ${label}: items=${offsets} values=${values}"
    asyncApi("POST", "/deviceRequestWriting", [
        id_device : state.idDevice,
        id_product: state.idProduct,
        Protocol  : "RWMSmaster",
        BitData   : bits,
        Endianess : endians,
        Items     : offsets,
        Masks     : masks,
        Values    : values
    ], "writeRequested", [label: label])
}

private long prepareValue(String key, BigDecimal value, boolean limitValueRaw) {
    Map reg = state.regs[key]
    BigDecimal min = reg.mn != null ? reg.mn as BigDecimal : null
    BigDecimal max = reg.mx != null ? reg.mx as BigDecimal : null
    if (!limitValueRaw && min != null && max != null && (value < min || value > max)) {
        throw new IllegalArgumentException("value must be between ${fmtNum(min)} and ${fmtNum(max)}: ${fmtNum(value)}")
    }
    String formula = (reg.fi ?: "#").replace("#", fmtNum(value)).replace("Mod", "%")
    long raw = truncate(evalFormula(formula))
    if (limitValueRaw && min != null && max != null && (raw < min || raw > max)) {
        throw new IllegalArgumentException("raw value must be between ${fmtNum(min)} and ${fmtNum(max)}: ${raw}")
    }
    if (reg.h) raw = Long.parseLong(raw.toString(), 16)
    return raw
}

/* ======================================================================
 *  Evaluating registers
 * ====================================================================== */

private void processBuffer(Map answer) {
    List items = answer.Items
    List vals = answer.Values
    Map raw = [:]
    items.eachWithIndex { item, i -> if (i < vals.size()) raw[item.toString()] = vals[i] }
    state.raw = raw
    updateAttributes()
    setCloudStatus("online")
    java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    fmt.setTimeZone(location?.timeZone ?: TimeZone.getDefault())
    sendEvent(name: "lastUpdate", value: fmt.format(new Date(now())))
}

private void updateAttributes() {
    // Status / on-off
    def status = regValue("status_get")
    if (status != null) {
        String descEng = regDescription("status_get", "ENG")?.toString()?.trim()?.toUpperCase()
        if (!state.regs.status_get?.e) descEng = STATUS_FALLBACK[status as int] ?: fmtNum(status)
        String desc = state.regs.status_get?.e ? regDescription("status_get", lang())?.toString() : descEng
        boolean alarm = descEng.contains("ALARM") || descEng.contains("ALLARM")
        boolean isOff = alarm || status == 0 || STATUS_OFF.contains(descEng)
        boolean idle = !isOff && STATUS_IDLE.contains(descEng)
        emit("stoveStatus", desc)
        emit("stoveStatusCode", status)
        emit("switch", isOff ? "off" : "on")
        emit("thermostatMode", isOff ? "off" : "heat")
        emit("thermostatOperatingState", (isOff || idle) ? "idle" : "heating")
    }

    // Alarm
    def alarmCode = regValue("alarms_get")
    if (alarmCode != null) {
        def desc = state.regs.alarms_get?.e ? regDescription("alarms_get", lang()) : (ALARM_FALLBACK[alarmCode as int] ?: "Code ${fmtNum(alarmCode)}")
        emit("alarm", desc?.toString())
        emit("alarmCode", alarmCode)
    }

    // Room temperature and setpoint
    String airGet = temperatureKey(AIR_VARIANTS, "get")
    if (airGet) {
        def t = regValue(airGet)
        if (t instanceof Number) emit("temperature", fromCelsius(t), temperatureUnit())
        String airSet = temperatureKey(AIR_VARIANTS, "set")
        if (airSet) {
            def sp = fromCelsius(regValue(airSet))
            emit("heatingSetpoint", sp, temperatureUnit())
            emit("thermostatSetpoint", sp, temperatureUnit())
        }
    }

    // Water (boiler stoves)
    String waterGet = temperatureKey(WATER_VARIANTS, "get")
    if (waterGet) emit("waterTemperature", fromCelsius(regValue(waterGet)), temperatureUnit())
    String waterSet = temperatureKey(WATER_VARIANTS, "set")
    if (waterSet) emit("waterSetpoint", fromCelsius(regValue(waterSet)), temperatureUnit())

    // Flue gas temperature
    String flueKey = ["temp_gas_flue_get", "temp_probe_k_get"].find { state.regs.containsKey(it) && regValue(it) != null }
    if (flueKey) emit("flueGasTemperature", fromCelsius(regValue(flueKey)), temperatureUnit())

    // Power level
    String pKey = powerKey()
    def power = regValue(pKey)
    if (power != null) {
        emit("powerLevel", power)
        emit("powerLevelName", regDescription(pKey, lang())?.toString())
        Map reg = state.regs[pKey]
        if (reg.mn != null) emit("powerLevelMin", reg.mn)
        if (reg.mx != null) emit("powerLevelMax", reg.mx)
    }

    if (state.regs.containsKey("ris_pellet_ris_get") && regValue("ris_pellet_ris_get") != null) {
        emit("pelletsLow", regValue("ris_pellet_ris_get") ? "true" : "false")
    }

    EXTRA_NUMBERS.each { String attr, String key ->
        if (state.regs.containsKey(key) && regEnabled(key)) {
            def v = regValue(key)
            if (v != null) emit(attr, v)
        }
    }
}

private String powerKey() {
    boolean hybrid = state.regs?.containsKey("power_wood_set")
    (hybrid && regEnabled("real_power_wood_get")) ? "power_wood_set" : "power_set"
}

// First usable temperature register (like the app: present, enabled, value != 0)
private String temperatureKey(List variants, String suffix) {
    variants.collect { "temp_${it}_${suffix}".toString() }.find { key ->
        state.regs?.containsKey(key) && regEnabled(key) && regValue(key)
    }
}

private boolean regEnabled(String key) {
    int idx = key.lastIndexOf("_")
    String enableKey = (idx > 0 ? key.substring(0, idx) : key) + "_enable"
    Map reg = state.regs?.get(enableKey)
    if (!reg || reg.t != "ENABLE") return true
    def v = regValue(enableKey)
    if (reg.ev) return v != null && reg.ev.contains(fmtNum(v))
    return v == 1
}

private regValue(String key) {
    Map reg = state.regs?.get(key)
    if (!reg) return null
    def raw = state.raw?.get(reg.o.toString())
    if (raw == null) return null
    long rawLong = raw as long
    if (settings.readingErrorFix && rawLong == READING_ERROR_VALUE) return null
    long masked = rawLong & (reg.m as long)
    BigDecimal value
    try {
        value = evalFormula((reg.f ?: "#").replace("#", masked.toString()).replace("Mod", "%"))
    } catch (Exception e) {
        logDebug "formula of ${key} ('${reg.f}') cannot be evaluated: ${e.message}"
        return null
    }
    if (settings.airTempFix && key.endsWith("air_get") && value > 100) return null
    return normalize(value)
}

private regDescription(String key, String language) {
    def value = regValue(key)
    if (value == null) return null
    Map enc = state.regs[key]?.e
    if (!enc) return value
    Map options = enc[language] ?: enc["ENG"]
    return options?.get(fmtNum(value)) ?: value
}

// Translate a text (e.g. "ON") or number into the numeric register value
private valueForDescription(String key, String text, String language = null) {
    if (text == null) return null
    Map enc = state.regs[key]?.e
    if (enc) {
        List langs = [language ?: lang(), "ENG"] + enc.keySet()
        for (String l : langs) {
            def match = enc[l]?.find { k, v -> v?.toString()?.equalsIgnoreCase(text) }
            if (match) return new BigDecimal(match.key)
        }
    }
    try {
        return new BigDecimal(text.replace(",", "."))
    } catch (NumberFormatException e) {
        return null
    }
}

/* ======================================================================
 *  Formula evaluation (replaces Python eval of the register formulas)
 *  Supports: + - * / // % ** & | ^ << >>, comparisons, and/or/not,
 *  "a if b else c", parentheses and IF(), int(), float(), abs(), round(), min(), max()
 * ====================================================================== */

private BigDecimal evalFormula(String expr) {
    Map ctx = [t: tokenize(expr), i: 0]
    BigDecimal v = parseTernary(ctx)
    if (ctx.i < ctx.t.size()) throw new IllegalArgumentException("unexpected character '${ctx.t[ctx.i]}'")
    return v
}

private List tokenize(String s) {
    List tokens = []
    int i = 0
    while (i < s.length()) {
        char c = s.charAt(i)
        if (Character.isWhitespace(c)) { i++; continue }
        if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
            int j = i
            if (c == '0' && i + 1 < s.length() && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                j = i + 2
                while (j < s.length() && Character.digit(s.charAt(j), 16) >= 0) j++
                tokens << [n: new BigDecimal(Long.parseLong(s.substring(i + 2, j), 16))]
            } else {
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++
                if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E') && j + 1 < s.length() &&
                        (Character.isDigit(s.charAt(j + 1)) || ((s.charAt(j + 1) == '-' || s.charAt(j + 1) == '+') && j + 2 < s.length() && Character.isDigit(s.charAt(j + 2))))) {
                    j += 2
                    while (j < s.length() && Character.isDigit(s.charAt(j))) j++
                }
                tokens << [n: new BigDecimal(s.substring(i, j))]
            }
            i = j
            continue
        }
        if (Character.isLetter(c) || c == '_') {
            int j = i
            while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++
            tokens << [id: s.substring(i, j)]
            i = j
            continue
        }
        String two = i + 1 < s.length() ? s.substring(i, i + 2) : null
        if (two in ["**", "//", "<=", ">=", "==", "!=", "<<", ">>", "&&", "||"]) {
            tokens << [op: two]
            i += 2
            continue
        }
        if ("+-*/%()<>,&|^~!".indexOf(c as int) >= 0) {
            tokens << [op: c.toString()]
            i++
            continue
        }
        throw new IllegalArgumentException("unknown character '${c}'")
    }
    return tokens
}

private boolean isOp(Map ctx, String op) {
    ctx.i < ctx.t.size() && ctx.t[ctx.i].op == op
}

private boolean isId(Map ctx, String id) {
    ctx.i < ctx.t.size() && ctx.t[ctx.i].id == id
}

private void expectOp(Map ctx, String op) {
    if (!isOp(ctx, op)) throw new IllegalArgumentException("'${op}' erwartet")
    ctx.i++
}

private BigDecimal parseTernary(Map ctx) {
    BigDecimal v = parseOr(ctx)
    if (isId(ctx, "if")) {
        ctx.i++
        BigDecimal cond = parseOr(ctx)
        if (!isId(ctx, "else")) throw new IllegalArgumentException("'else' expected")
        ctx.i++
        BigDecimal other = parseTernary(ctx)
        return cond != 0 ? v : other
    }
    return v
}

private BigDecimal parseOr(Map ctx) {
    BigDecimal v = parseAnd(ctx)
    while (isId(ctx, "or") || isOp(ctx, "||")) {
        ctx.i++
        BigDecimal r = parseAnd(ctx)
        v = v != 0 ? v : r
    }
    return v
}

private BigDecimal parseAnd(Map ctx) {
    BigDecimal v = parseNot(ctx)
    while (isId(ctx, "and") || isOp(ctx, "&&")) {
        ctx.i++
        BigDecimal r = parseNot(ctx)
        v = v == 0 ? v : r
    }
    return v
}

private BigDecimal parseNot(Map ctx) {
    if (isId(ctx, "not") || isOp(ctx, "!")) {
        ctx.i++
        return parseNot(ctx) == 0 ? BigDecimal.ONE : BigDecimal.ZERO
    }
    return parseComparison(ctx)
}

private BigDecimal parseComparison(Map ctx) {
    BigDecimal left = parseBitOr(ctx)
    boolean result = true
    boolean compared = false
    while (ctx.i < ctx.t.size() && ctx.t[ctx.i].op in ["<", ">", "<=", ">=", "==", "!="]) {
        String op = ctx.t[ctx.i].op
        ctx.i++
        BigDecimal right = parseBitOr(ctx)
        int c = left.compareTo(right)
        boolean ok = (op == "<" && c < 0) || (op == ">" && c > 0) || (op == "<=" && c <= 0) ||
                     (op == ">=" && c >= 0) || (op == "==" && c == 0) || (op == "!=" && c != 0)
        result = result && ok
        compared = true
        left = right
    }
    return compared ? (result ? BigDecimal.ONE : BigDecimal.ZERO) : left
}

private BigDecimal parseBitOr(Map ctx) {
    BigDecimal v = parseBitXor(ctx)
    while (isOp(ctx, "|")) { ctx.i++; v = new BigDecimal(truncate(v) | truncate(parseBitXor(ctx))) }
    return v
}

private BigDecimal parseBitXor(Map ctx) {
    BigDecimal v = parseBitAnd(ctx)
    while (isOp(ctx, "^")) { ctx.i++; v = new BigDecimal(truncate(v) ^ truncate(parseBitAnd(ctx))) }
    return v
}

private BigDecimal parseBitAnd(Map ctx) {
    BigDecimal v = parseShift(ctx)
    while (isOp(ctx, "&")) { ctx.i++; v = new BigDecimal(truncate(v) & truncate(parseShift(ctx))) }
    return v
}

private BigDecimal parseShift(Map ctx) {
    BigDecimal v = parseAdditive(ctx)
    while (isOp(ctx, "<<") || isOp(ctx, ">>")) {
        String op = ctx.t[ctx.i].op
        ctx.i++
        int n = truncate(parseAdditive(ctx)) as int
        v = new BigDecimal(op == "<<" ? truncate(v) << n : truncate(v) >> n)
    }
    return v
}

private BigDecimal parseAdditive(Map ctx) {
    BigDecimal v = parseMultiplicative(ctx)
    while (isOp(ctx, "+") || isOp(ctx, "-")) {
        String op = ctx.t[ctx.i].op
        ctx.i++
        BigDecimal r = parseMultiplicative(ctx)
        v = op == "+" ? v + r : v - r
    }
    return v
}

private BigDecimal parseMultiplicative(Map ctx) {
    BigDecimal v = parseUnary(ctx)
    while (isOp(ctx, "*") || isOp(ctx, "/") || isOp(ctx, "//") || isOp(ctx, "%")) {
        String op = ctx.t[ctx.i].op
        ctx.i++
        BigDecimal r = parseUnary(ctx)
        if (op == "*") {
            v = v * r
        } else {
            if (r == 0) throw new ArithmeticException("division by 0")
            BigDecimal floorDiv = v.divide(r, 0, BigDecimal.ROUND_FLOOR)
            if (op == "/") v = v.divide(r, 12, BigDecimal.ROUND_HALF_EVEN)
            else if (op == "//") v = floorDiv
            else v = v - r * floorDiv   // Python modulo (sign of the divisor)
        }
    }
    return v
}

private BigDecimal parseUnary(Map ctx) {
    if (isOp(ctx, "-")) { ctx.i++; return -parseUnary(ctx) }
    if (isOp(ctx, "+")) { ctx.i++; return parseUnary(ctx) }
    if (isOp(ctx, "~")) { ctx.i++; return new BigDecimal(~truncate(parseUnary(ctx))) }
    return parsePower(ctx)
}

private BigDecimal parsePower(Map ctx) {
    BigDecimal base = parsePrimary(ctx)
    if (isOp(ctx, "**")) {
        ctx.i++
        BigDecimal exp = parseUnary(ctx)
        if (exp.stripTrailingZeros().scale() <= 0 && exp >= 0) return base.pow(exp.intValueExact())
        return new BigDecimal(Math.pow(base.doubleValue(), exp.doubleValue()))
    }
    return base
}

private BigDecimal parsePrimary(Map ctx) {
    if (ctx.i >= ctx.t.size()) throw new IllegalArgumentException("formula ends unexpectedly")
    Map tok = ctx.t[ctx.i]
    if (tok.n != null) { ctx.i++; return tok.n }
    if (tok.op == "(") {
        ctx.i++
        BigDecimal v = parseTernary(ctx)
        expectOp(ctx, ")")
        return v
    }
    if (tok.id != null) {
        ctx.i++
        if (tok.id == "True") return BigDecimal.ONE
        if (tok.id == "False") return BigDecimal.ZERO
        if (!isOp(ctx, "(")) throw new IllegalArgumentException("unknown name '${tok.id}'")
        ctx.i++
        List args = []
        if (!isOp(ctx, ")")) {
            args << parseTernary(ctx)
            while (isOp(ctx, ",")) { ctx.i++; args << parseTernary(ctx) }
        }
        expectOp(ctx, ")")
        return callFunction(tok.id, args)
    }
    throw new IllegalArgumentException("unexpected character '${tok.op}'")
}

private BigDecimal callFunction(String name, List args) {
    switch (name) {
        case "IF":
            if (args.size() != 3) throw new IllegalArgumentException("IF needs 3 arguments")
            return args[0] != 0 ? args[1] : args[2]
        case "int": return new BigDecimal(truncate(args[0]))
        case "float": return args[0]
        case "abs": return args[0].abs()
        case "round":
            int digits = args.size() > 1 ? truncate(args[1]) as int : 0
            return args[0].setScale(digits, BigDecimal.ROUND_HALF_EVEN)
        case "min": return args.min()
        case "max": return args.max()
    }
    throw new IllegalArgumentException("unknown function '${name}'")
}

/* ======================================================================
 *  Helpers
 * ====================================================================== */

private long truncate(BigDecimal v) {
    v.setScale(0, BigDecimal.ROUND_DOWN).longValue()
}

private normalize(BigDecimal v) {
    if (v == null) return null
    BigDecimal s = v.stripTrailingZeros()
    if (s.scale() <= 0) return s.toBigInteger() as long
    return v.setScale(Math.min(s.scale(), 2), BigDecimal.ROUND_HALF_UP)
}

private String fmtNum(v) {
    if (v == null) return "null"
    if (v instanceof BigDecimal) {
        BigDecimal s = v.stripTrailingZeros()
        return s.scale() <= 0 ? s.toBigInteger().toString() : s.toPlainString()
    }
    return v.toString()
}

private String temperatureUnit() {
    "°${location?.temperatureScale ?: 'C'}"
}

private fromCelsius(value) {
    if (value == null) return null
    if (location?.temperatureScale == "F") {
        return ((value as BigDecimal) * 9 / 5 + 32).setScale(1, BigDecimal.ROUND_HALF_UP)
    }
    return value
}

private BigDecimal toCelsius(BigDecimal value) {
    if (location?.temperatureScale == "F") {
        return ((value - 32) * 5 / 9).setScale(1, BigDecimal.ROUND_HALF_UP)
    }
    return value
}

private void emit(String name, value, String unit = null) {
    if (value == null) return
    def v = value instanceof BigDecimal ? normalize(value) : value
    boolean changed = device.currentValue(name)?.toString() != v.toString()
    Map evt = [name: name, value: v]
    if (unit) evt.unit = unit
    if (changed) {
        evt.descriptionText = "${device.displayName} ${name} is ${v}${unit ?: ''}"
        if (settings.txtEnable) log.info evt.descriptionText
    }
    sendEvent(evt)
}

private void setCloudStatus(String status) {
    if (device.currentValue("cloudStatus") != status) {
        sendEvent(name: "cloudStatus", value: status)
    }
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug "${device.displayName}: ${msg}"
}
