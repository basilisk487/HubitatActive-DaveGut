/*    Kasa Device Driver Series
        Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Link to list of changes =====
    https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Changes.pdf
===== Link to Documentation =====
    https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf
===================================================================================================*/
def driverVer() { return "2.3.6" }

metadata {
    definition (name: "Kasa Light Strip",
                namespace: nameSpace(),
                author: "Dave Gutheinz",
                importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/LightStrip.groovy"
               ) {
        capability "Light"
        capability "Switch"
        capability "Switch Level"
        capability "Change Level"
        capability "Refresh"
        capability "Actuator"
        capability "Configuration"
        capability "Color Temperature"
        capability "Color Mode"
        capability "Color Control"
        command "setRGB", [[
            name: "red,green,blue", 
            type: "STRING"]]
        command "bulbPresetCreate", [[
            name: "Name for preset.", 
            type: "STRING"]]
        command "bulbPresetDelete", [[
            name: "Name for preset.", 
            type: "STRING"]]
        command "bulbPresetSet", [[
            name: "Name for preset.", 
            type: "STRING"],[
            name: "Transition Time (seconds).", 
            type: "STRING"]]
        capability "Light Effects"
        command "effectSet", [[
            name: "Name for effect.", 
            type: "STRING"]]
        command "effectCreate"
        command "effectDelete", [[
            name: "Name for effect to delete.", 
            type: "STRING"]]
        command "setPollInterval", [[
            name: "Poll Interval in seconds",
            constraints: ["default", "1 minute", "5 minutes",  "10 minutes",
                          "30 minutes"],
            type: "ENUM"]]
        capability "Power Meter"
        capability "Energy Meter"
        attribute "currMonthTotal", "number"
        attribute "currMonthAvg", "number"
        attribute "lastMonthTotal", "number"
        attribute "lastMonthAvg", "number"
        attribute "connection", "string"
        attribute "commsError", "string"
    }
    preferences {
        input ("textEnable", "bool", 
               title: "Enable descriptionText logging",
               defaultValue: true)
        input ("logEnable", "bool",
               title: "Enable debug logging",
               defaultValue: false)
        input ("syncBulbs", "bool",
               title: "Sync Bulb Preset Data",
               defaultValue: false)
        input ("syncEffects", "bool",
               title: "Sync Effect Preset Data",
               defaultValue: false)
        input ("emFunction", "bool", 
               title: "Enable Energy Monitor", 
               defaultValue: false)
        if (emFunction) {
            input ("energyPollInt", "enum",
                   title: "Energy Poll Interval (minutes)",
                   options: ["1 minute", "5 minutes", "30 minutes"],
                   defaultValue: "30 minutes")
        }
        input ("bind", "bool",
               title: "Kasa Cloud Binding",
               defalutValue: true)
        input ("useCloud", "bool",
               title: "Use Kasa Cloud for device control",
               defaultValue: false)
        input ("nameSync", "enum", title: "Synchronize Names",
               defaultValue: "none",
               options: ["none": "Don't synchronize",
                         "device" : "Kasa device name master", 
                         "Hubitat" : "Hubitat label master"])
        input ("manualIp", "string",
               title: "Manual IP Update <b>[Caution]</b>",
               defaultValue: getDataValue("deviceIP"))
        input ("manualPort", "string",
               title: "Manual Port Update <b>[Caution]</b>",
               defaultValue: getDataValue("devicePort"))
        input ("rebootDev", "bool",
               title: "Reboot device <b>[Caution]</b>",
               defaultValue: false)
    }
}

def installed() {
    def instStatus= installCommon()
    state.bulbPresets = [:]
    state.effectPresets = []
        sendEvent(name: "lightEffects", value: [])
    logInfo("installed: ${instStatus}")
}

def updated() {
    def updStatus = updateCommon()
    if (syncBulbs) {
        updStatus << [syncBulbs: syncBulbPresets()]
    }
    if (syncEffects) {
        updStatus << [syncEffects: syncEffectPresets()]
    }
    updStatus << [emFunction: setupEmFunction()]
    if (!state.bulbPresets) { state.bulbPresets = [:] }
    if (!state.effectPresets) { state.effectPresets = [] }
    logInfo("updated: ${updStatus}")
    refresh()
}

def setColorTemperature(colorTemp, level = device.currentValue("level"), transTime = transition_Time) {
    def lowCt = 1000
    def highCt = 12000
    if (colorTemp < lowCt) { colorTemp = lowCt }
    else if (colorTemp > highCt) { colorTemp = highCt }
    def hsvData = getCtHslValue(colorTemp)
    setLightColor(level, colorTemp, hsvData.hue, hsvData.saturation, 0)
    state.currentCT = colorTemp
}

def distResp(response) {
    if (response.system) {
        if (response.system.get_sysinfo) {
            setSysInfo(response.system.get_sysinfo)
            if (nameSync == "device") {
                updateName(response.system.get_sysinfo)
            }
        } else if (response.system.set_dev_alias) {
            updateName(response.system.set_dev_alias)
        } else {
            logWarn("distResp: Unhandled response = ${response}")
        }
    } else if (response["smartlife.iot.smartbulb.lightingservice"]) {
        setSysInfo([light_state:response["smartlife.iot.smartbulb.lightingservice"].transition_light_state])
    } else if (response["smartlife.iot.lightStrip"]) {
        getSysinfo()
    } else if (response["smartlife.iot.lighting_effect"]) {
        parseEffect(response["smartlife.iot.lighting_effect"])
    } else if (response["smartlife.iot.common.emeter"]) {
        distEmeter(response["smartlife.iot.common.emeter"])
    } else if (response["smartlife.iot.common.cloud"]) {
        setBindUnbind(response["smartlife.iot.common.cloud"])
    } else if (response["smartlife.iot.common.system"]) {
        if (response["smartlife.iot.common.system"].reboot) {
            logWarn("distResp: Rebooting device")
        } else {
            logDebug("distResp: Unhandled reboot response: ${response}")
        }
    } else {
        logWarn("distResp: Unhandled response = ${response}")
    }
}

def setSysInfo(status) {
    def lightStatus = status.light_state
    if (state.lastStatus != lightStatus) {
        state.lastStatus = lightStatus
        logInfo("setSysinfo: [status: ${lightStatus}]")
        def onOff
        if (lightStatus.on_off == 0) {
            onOff = "off"
        } else {
            onOff = "on"
            int level = lightStatus.brightness
            sendEvent(name: "level", value: level, unit: "%")
            int colorTemp = lightStatus.color_temp
            int hue = lightStatus.hue
            int hubHue = (hue / 3.6).toInteger()
            int saturation = lightStatus.saturation
            def colorMode
            def colorName = " "
            def effectName = " "
            def color = ""
            def rgb = ""
            if (status.lighting_effect_state.enable == 1) {
                colorMode = "EFFECTS"
                effectName = status.lighting_effect_state.name
                colorTemp = 0
                hubHue = 0
                saturation = 0
            } else if (colorTemp > 0) {
                colorMode = "CT" 
                colorName = convertTemperatureToGenericColorName(colorTemp.toInteger())
            } else {
                colorMode = "RGB"
                colorName = convertHueToGenericColorName(hubHue.toInteger())
                color = "[hue: ${hubHue}, saturation: ${saturation}, level: ${level}]"
                rgb = hubitat.helper.ColorUtils.hsvToRGB([hubHue, saturation, level])
            }
            if (device.currentValue("colorTemperature") != colorTemp ||
                device.currentValue("color") != color) {
                sendEvent(name: "colorTemperature", value: colorTemp)
                sendEvent(name: "colorName", value: colorName)
                sendEvent(name: "color", value: color)
                sendEvent(name: "hue", value: hubHue)
                sendEvent(name: "saturation", value: saturation)
                sendEvent(name: "colorMode", value: colorMode)
                sendEvent(name: "RGB", value: rgb)
            }
            if (effectName != device.currentValue("effectName")) {
                sendEvent(name: "effectName", value: effectName)
                logInfo("setSysinfo: [effectName: ${effectName}]")
            }
        }
        sendEvent(name: "switch", value: onOff, type: "digital")
    }
    runIn(1, getPower)
}

def effectCreate() {
    state.createEffect = true
    sendCmd("""{"smartlife.iot.lighting_effect":{"get_lighting_effect":{}}}""")
}

def parseEffect(resp) {
    logDebug("parseEffect: ${resp}")
    if (resp.get_lighting_effect) {
        def effData = resp.get_lighting_effect
        def effName = effData.name
        if (state.createEffect == true) {
            def existngEffect = state.effectPresets.find { it.name == effName }
            if (existngEffect == null) {
                state.effectPresets << effData
                resetLightEffects()
                logDebug("parseEffect: ${effName} added to effectPresets")
            } else {
                logWarn("parseEffect: ${effName} already exists.")
            }
            state.remove("createEffect")
        }
        refresh()
    } else {
        if (resp.set_lighting_effect.err_code != 0) {
            logWarn("parseEffect: Error setting effect.")
        }
        sendCmd("""{"smartlife.iot.lighting_effect":{"get_lighting_effect":{}}}""")
    }
}

def resetLightEffects() {
    if (state.effectsPresets != [:]) {
        def lightEffects = []
        state.effectPresets.each{
            def name = """ "${it.name}" """
            lightEffects << name
        }
        sendEvent(name: "lightEffects", value: lightEffects)
    }
    return "Updated lightEffects list"
}

def setEffect(index) {
    logDebug("setEffect: effNo = ${index}")
    index = index.toInteger()
    def effectPresets = state.effectPresets
    if (effectPresets == []) {
        logWarn("setEffect: effectPresets database is empty.")
        return
    }
    def effData = effectPresets[index]
    sendEffect(effData)                         
}

def setPreviousEffect() {
    def effectPresets = state.effectPresets
    if (device.currentValue("colorMode") != "EFFECTS" || effectPresets == []) {
        logWarn("setPreviousEffect: Not available. Either not in Effects or data is empty.")
        return
    }
    def effName = device.currentValue("effectName").trim()
    def index = effectPresets.findIndexOf { it.name == effName }
    if (index == -1) {
        logWarn("setPreviousEffect: ${effName} not found in effectPresets.")
    } else {
        def size = effectPresets.size()
        if (index == 0) { index = size - 1 }
        else { index = index-1 }
        def effData = effectPresets[index]
        sendEffect(effData)                         
    }
}

def setNextEffect() {
    def effectPresets = state.effectPresets
    if (device.currentValue("colorMode") != "EFFECTS" || effectPresets == []) {
        logWarn("setNextEffect: Not available. Either not in Effects or data is empty.")
        return
    }
    def effName = device.currentValue("effectName").trim()
    def index = effectPresets.findIndexOf { it.name == effName }
    if (index == -1) {
        logWarn("setNextEffect: ${effName} not found in effectPresets.")
    } else {
        def size = effectPresets.size()
        if (index == size - 1) { index = 0 }
        else { index = index + 1 }
        def effData = effectPresets[index]
        sendEffect(effData)                         
    }
}

def effectSet(effName) {
    if (state.effectPresets == []) {
        logWarn("effectSet: effectPresets database is empty.")
        return
    }
    effName = effName.trim()
    logDebug("effectSet: ${effName}.")
    def effData = state.effectPresets.find { it.name == effName }
    if (effData == null) {
        logWarn("effectSet: ${effName} not found.")
        return
    }
    sendEffect(effData)
}

def effectDelete(effName) {
    sendEvent(name: "lightEffects", value: [])
    effName = effName.trim()
    def index = state.effectPresets.findIndexOf { it.name == effName }
    if (index == -1 || nameIndex == -1) {
        logWarn("effectDelete: ${effName} not in effectPresets!")
    } else {
        state.effectPresets.remove(index)
        resetLightEffects()
    }
    logDebug("effectDelete: deleted effect ${effName}")
}

def syncEffectPresets() {
    device.updateSetting("syncEffects", [type:"bool", value: false])
    parent.resetStates(device.deviceNetworkId)
    state.effectPresets.each{
        def effData = it
        parent.syncEffectPreset(effData, device.deviceNetworkId)
        pauseExecution(1000)
    }
    return "Synching"
}

def resetStates() { state.effectPresets = [] }

def updateEffectPreset(effData) {
    logDebug("updateEffectPreset: ${effData.name}")
    state.effectPresets << effData
    runIn(5, resetLightEffects)
}

def sendEffect(effData) {
    effData = new groovy.json.JsonBuilder(effData).toString()
    sendCmd("""{"smartlife.iot.lighting_effect":{"set_lighting_effect":""" +
            """${effData}},"context":{"source":"<id>"}}""")
}

def getCtHslValue(kelvin) {
    kelvin = (100 * Math.round(kelvin / 100)).toInteger()
    switch(kelvin) {
        case 1000: rgb= [255, 56, 0]; break
        case 1100: rgb= [255, 71, 0]; break
        case 1200: rgb= [255, 83, 0]; break
        case 1300: rgb= [255, 93, 0]; break
        case 1400: rgb= [255, 101, 0]; break
        case 1500: rgb= [255, 109, 0]; break
        case 1600: rgb= [255, 115, 0]; break
        case 1700: rgb= [255, 121, 0]; break
        case 1800: rgb= [255, 126, 0]; break
        case 1900: rgb= [255, 131, 0]; break
        case 2000: rgb= [255, 138, 18]; break
        case 2100: rgb= [255, 142, 33]; break
        case 2200: rgb= [255, 147, 44]; break
        case 2300: rgb= [255, 152, 54]; break
        case 2400: rgb= [255, 157, 63]; break
        case 2500: rgb= [255, 161, 72]; break
        case 2600: rgb= [255, 165, 79]; break
        case 2700: rgb= [255, 169, 87]; break
        case 2800: rgb= [255, 173, 94]; break
        case 2900: rgb= [255, 177, 101]; break
        case 3000: rgb= [255, 180, 107]; break
        case 3100: rgb= [255, 184, 114]; break
        case 3200: rgb= [255, 187, 120]; break
        case 3300: rgb= [255, 190, 126]; break
        case 3400: rgb= [255, 193, 132]; break
        case 3500: rgb= [255, 196, 137]; break
        case 3600: rgb= [255, 199, 143]; break
        case 3700: rgb= [255, 201, 148]; break
        case 3800: rgb= [255, 204, 153]; break
        case 3900: rgb= [255, 206, 159]; break
        case 4000: rgb= [100, 209, 200]; break
        case 4100: rgb= [255, 211, 168]; break
        case 4200: rgb= [255, 213, 173]; break
        case 4300: rgb= [255, 215, 177]; break
        case 4400: rgb= [255, 217, 182]; break
        case 4500: rgb= [255, 219, 186]; break
        case 4600: rgb= [255, 221, 190]; break
        case 4700: rgb= [255, 223, 194]; break
        case 4800: rgb= [255, 225, 198]; break
        case 4900: rgb= [255, 227, 202]; break
        case 5000: rgb= [255, 228, 206]; break
        case 5100: rgb= [255, 230, 210]; break
        case 5200: rgb= [255, 232, 213]; break
        case 5300: rgb= [255, 233, 217]; break
        case 5400: rgb= [255, 235, 220]; break
        case 5500: rgb= [255, 236, 224]; break
        case 5600: rgb= [255, 238, 227]; break
        case 5700: rgb= [255, 239, 230]; break
        case 5800: rgb= [255, 240, 233]; break
        case 5900: rgb= [255, 242, 236]; break
        case 6000: rgb= [255, 243, 239]; break
        case 6100: rgb= [255, 244, 242]; break
        case 6200: rgb= [255, 245, 245]; break
        case 6300: rgb= [255, 246, 247]; break
        case 6400: rgb= [255, 248, 251]; break
        case 6500: rgb= [255, 249, 253]; break
        case 6600: rgb= [254, 249, 255]; break
        case 6700: rgb= [252, 247, 255]; break
        case 6800: rgb= [249, 246, 255]; break
        case 6900: rgb= [247, 245, 255]; break
        case 7000: rgb= [245, 243, 255]; break
        case 7100: rgb= [243, 242, 255]; break
        case 7200: rgb= [240, 241, 255]; break
        case 7300: rgb= [239, 240, 255]; break
        case 7400: rgb= [237, 239, 255]; break
        case 7500: rgb= [235, 238, 255]; break
        case 7600: rgb= [233, 237, 255]; break
        case 7700: rgb= [231, 236, 255]; break
        case 7800: rgb= [230, 235, 255]; break
        case 7900: rgb= [228, 234, 255]; break
        case 8000: rgb= [227, 233, 255]; break
        case 8100: rgb= [225, 232, 255]; break
        case 8200: rgb= [224, 231, 255]; break
        case 8300: rgb= [222, 230, 255]; break
        case 8400: rgb= [221, 230, 255]; break
        case 8500: rgb= [220, 229, 255]; break
        case 8600: rgb= [218, 229, 255]; break
        case 8700: rgb= [217, 227, 255]; break
        case 8800: rgb= [216, 227, 255]; break
        case 8900: rgb= [215, 226, 255]; break
        case 9000: rgb= [214, 225, 255]; break
        case 9100: rgb= [212, 225, 255]; break
        case 9200: rgb= [211, 224, 255]; break
        case 9300: rgb= [210, 223, 255]; break
        case 9400: rgb= [209, 223, 255]; break
        case 9500: rgb= [208, 222, 255]; break
        case 9600: rgb= [207, 221, 255]; break
        case 9700: rgb= [207, 221, 255]; break
        case 9800: rgb= [206, 220, 255]; break
        case 9900: rgb= [205, 220, 255]; break
        case 10000: rgb= [207, 218, 255]; break
        case 10100: rgb= [207, 218, 255]; break
        case 10200: rgb= [206, 217, 255]; break
        case 10300: rgb= [205, 217, 255]; break
        case 10400: rgb= [204, 216, 255]; break
        case 10500: rgb= [204, 216, 255]; break
        case 10600: rgb= [203, 215, 255]; break
        case 10700: rgb= [202, 215, 255]; break
        case 10800: rgb= [202, 214, 255]; break
        case 10900: rgb= [201, 214, 255]; break
        case 11000: rgb= [200, 213, 255]; break
        case 11100: rgb= [200, 213, 255]; break
        case 11200: rgb= [199, 212, 255]; break
        case 11300: rgb= [198, 212, 255]; break
        case 11400: rgb= [198, 212, 255]; break
        case 11500: rgb= [197, 211, 255]; break
        case 11600: rgb= [197, 211, 255]; break
        case 11700: rgb= [197, 210, 255]; break
        case 11800: rgb= [196, 210, 255]; break
        case 11900: rgb= [195, 210, 255]; break
        case 12000: rgb= [195, 209, 255]; break
        default:
            logWarn("setRgbData: Unknown.")
            colorName = "Unknown"
    }
    def hsvData = hubitat.helper.ColorUtils.rgbToHSV([rgb[0].toInteger(), rgb[1].toInteger(), rgb[2].toInteger()])
    def hue = (0.5 + hsvData[0]).toInteger()
    def saturation = (0.5 + hsvData[1]).toInteger()
    def level = (0.5 + hsvData[2]).toInteger()
    def hslData = [
        hue: hue,
        saturation: saturation,
        level: level
        ]
    return hslData
}








// ~~~~~ start include (1359) davegut.kasaCommon ~~~~~
library (
    name: "kasaCommon",
    namespace: "davegut",
    author: "Dave Gutheinz",
    description: "Kasa Device Common Methods",
    category: "utilities",
    documentationLink: ""
)

def nameSpace() { return "davegut" }

def installCommon() {
    pauseExecution(3000)
    def instStatus = [:]
    if (getDataValue("deviceIP") == "CLOUD") {
        sendEvent(name: "connection", value: "CLOUD")
        device.updateSetting("useCloud", [type:"bool", value: true])
        instStatus << [useCloud: true, connection: "CLOUD"]
    } else {
        sendEvent(name: "connection", value: "LAN")
        device.updateSetting("useCloud", [type:"bool", value: false])
        instStatus << [useCloud: false, connection: "LAN"]
    }

    sendEvent(name: "commsError", value: "false")
    state.errorCount = 0
    state.pollInterval = "30 minutes"
    runIn(1, updated)
    return instStatus
}

def updateCommon() {
    def updStatus = [:]
    if (rebootDev) {
        updStatus << [rebootDev: rebootDevice()]
        return updStatus
    }
    unschedule()
    updStatus << [bind: bindUnbind()]
    if (nameSync != "none") {
        updStatus << [nameSync: syncName()]
    }
    if (logEnable) { runIn(1800, debugLogOff) }
    updStatus << [textEnable: textEnable, logEnable: logEnable]
    if (manualIp != getDataValue("deviceIP")) {
        updateDataValue("deviceIP", manualIp)
        updStatus << [ipUpdate: manualIp]
    }
    if (manualPort != getDataValue("devicePort")) {
        updateDataValue("devicePort", manualPort)
        updStatus << [portUpdate: manualPort]
    }
    state.errorCount = 0
    sendEvent(name: "commsError", value: "false")
    def pollInterval = state.pollInterval
    if (pollInterval == null) { pollInterval = "30 minutes" }
    updStatus << [pollInterval: setPollInterval(pollInterval)]
    state.remove("UPDATE_AVAILABLE")
    state.remove("releaseNotes")
    removeDataValue("driverVersion")
    if (emFunction) {
        scheduleEnergyAttrs()
        state.getEnergy = "This Month"
        updStatus << [emFunction: "scheduled"]
    }
    runIn(5, listAttributes)
    return updStatus
}

def configure() {
    if (parent == null) {
        logWarn("configure: No Parent Detected.  Configure function ABORTED.  Use Save Preferences instead.")
    } else {
        def confStatus = parent.updateConfigurations()
        logInfo("configure: ${confStatus}")
    }
}

def refresh() { poll() }

def poll() { getSysinfo() }

def setPollInterval(interval = state.pollInterval) {
    if (interval == "default" || interval == "off" || interval == null) {
        interval = "30 minutes"
    } else if (useCloud || altLan || getDataValue("altComms") == "true") {
        if (interval.contains("sec")) {
            interval = "1 minute"
            logWarn("setPollInterval: Device using Cloud or rawSocket.  Poll interval reset to minimum value of 1 minute.")
        }
    }
    state.pollInterval = interval
    def pollInterval = interval.substring(0,2).toInteger()
    if (interval.contains("sec")) {
        def start = Math.round((pollInterval-1) * Math.random()).toInteger()
        schedule("${start}/${pollInterval} * * * * ?", "poll")
        logWarn("setPollInterval: Polling intervals of less than one minute " +
                "can take high resources and may impact hub performance.")
    } else {
        def start = Math.round(59 * Math.random()).toInteger()
        schedule("${start} */${pollInterval} * * * ?", "poll")
    }
    logDebug("setPollInterval: interval = ${interval}.")
    return interval
}

def rebootDevice() {
    device.updateSetting("rebootDev", [type:"bool", value: false])
    reboot()
    pauseExecution(10000)
    return "REBOOTING DEVICE"
}

def bindUnbind() {
    def message
    if (getDataValue("deviceIP") == "CLOUD") {
        device.updateSetting("bind", [type:"bool", value: true])
        device.updateSetting("useCloud", [type:"bool", value: true])
        message = "No deviceIp.  Bind not modified."
    } else if (bind == null ||  getDataValue("feature") == "lightStrip") {
        message = "Getting current bind state"
        getBind()
    } else if (bind == true) {
        if (!parent.kasaToken || parent.userName == null || parent.userPassword == null) {
            message = "Username/pwd not set."
            getBind()
        } else {
            message = "Binding device to the Kasa Cloud."
            setBind(parent.userName, parent.userPassword)
        }
    } else if (bind == false) {
        message = "Unbinding device from the Kasa Cloud."
        setUnbind()
    }
    pauseExecution(5000)
    return message
}

def setBindUnbind(cmdResp) {
    def bindState = true
    if (cmdResp.get_info) {
        if (cmdResp.get_info.binded == 0) { bindState = false }
        logInfo("setBindUnbind: Bind status set to ${bindState}")
        setCommsType(bindState)
    } else if (cmdResp.bind.err_code == 0){
        getBind()
    } else {
        logWarn("setBindUnbind: Unhandled response: ${cmdResp}")
    }
}

def setCommsType(bindState) {
    def commsType = "LAN"
    def cloudCtrl = false
    if (bindState == false && useCloud == true) {
        logWarn("setCommsType: Can not use cloud.  Device is not bound to Kasa cloud.")
    } else if (bindState == true && useCloud == true && parent.kasaToken) {
        commsType = "CLOUD"
        cloudCtrl = true
    } else if (altLan == true) {
        commsType = "AltLAN"
        state.response = ""
    }
    def commsSettings = [bind: bindState, useCloud: cloudCtrl, commsType: commsType]
    device.updateSetting("bind", [type:"bool", value: bindState])
    device.updateSetting("useCloud", [type:"bool", value: cloudCtrl])
    sendEvent(name: "connection", value: "${commsType}")
    logInfo("setCommsType: ${commsSettings}")
    if (getDataValue("plugNo") != null) {
        def coordData = [:]
        coordData << [bind: bindState]
        coordData << [useCloud: cloudCtrl]
        coordData << [connection: commsType]
        coordData << [altLan: altLan]
        parent.coordinate("commsData", coordData, getDataValue("deviceId"), getDataValue("plugNo"))
    }
    pauseExecution(1000)
}

def syncName() {
    def message
    if (nameSync == "Hubitat") {
        message = "Hubitat Label Sync"
        setDeviceAlias(device.getLabel())
    } else if (nameSync == "device") {
        message = "Device Alias Sync"
    } else {
        message = "Not Syncing"
    }
    device.updateSetting("nameSync",[type:"enum", value:"none"])
    return message
}

def updateName(response) {
    def name = device.getLabel()
    if (response.alias) {
        name = response.alias
        device.setLabel(name)
    } else if (response.err_code != 0) {
        def msg = "updateName: Name Sync from Hubitat to Device returned an error."
        msg+= "\n\rNote: <b>Some devices do not support syncing name from the hub.</b>\n\r"
        logWarn(msg)
        return
    }
    logInfo("updateName: Hubitat and Kasa device name synchronized to ${name}")
}

def getSysinfo() {
    if (getDataValue("altComms") == "true") {
        sendTcpCmd("""{"system":{"get_sysinfo":{}}}""")
    } else {
        sendCmd("""{"system":{"get_sysinfo":{}}}""")
    }
}

def bindService() {
    def service = "cnCloud"
    def feature = getDataValue("feature")
    if (feature.contains("Bulb") || feature == "lightStrip") {
        service = "smartlife.iot.common.cloud"
    }
    return service
}

def getBind() {
    if (getDataValue("deviceIP") == "CLOUD") {
        logDebug("getBind: [status: notRun, reason: [deviceIP: CLOUD]]")
    } else {
        sendLanCmd("""{"${bindService()}":{"get_info":{}}}""")
    }
}

def setBind(userName, password) {
    if (getDataValue("deviceIP") == "CLOUD") {
        logDebug("setBind: [status: notRun, reason: [deviceIP: CLOUD]]")
    } else {
        sendLanCmd("""{"${bindService()}":{"bind":{"username":"${userName}",""" +
                   """"password":"${password}"}},""" +
                   """"${bindService()}":{"get_info":{}}}""")
    }
}

def setUnbind() {
    if (getDataValue("deviceIP") == "CLOUD") {
        logDebug("setUnbind: [status: notRun, reason: [deviceIP: CLOUD]]")
    } else {
        sendLanCmd("""{"${bindService()}":{"unbind":""},""" +
                   """"${bindService()}":{"get_info":{}}}""")
    }
}

def sysService() {
    def service = "system"
    def feature = getDataValue("feature")
    if (feature.contains("Bulb") || feature == "lightStrip") {
        service = "smartlife.iot.common.system"
    }
    return service
}

def reboot() {
    sendCmd("""{"${sysService()}":{"reboot":{"delay":1}}}""")
}

def setDeviceAlias(newAlias) {
    if (getDataValue("plugNo") != null) {
        sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
                """"system":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""")
    } else {
        sendCmd("""{"${sysService()}":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""")
    }
}

// ~~~~~ end include (1359) davegut.kasaCommon ~~~~~

// ~~~~~ start include (1360) davegut.kasaCommunications ~~~~~
library (
    name: "kasaCommunications",
    namespace: "davegut",
    author: "Dave Gutheinz",
    description: "Kasa Communications Methods",
    category: "communications",
    documentationLink: ""
)

import groovy.json.JsonSlurper
import org.json.JSONObject

def getPort() {
    def port = 9999
    if (getDataValue("devicePort")) {
        port = getDataValue("devicePort")
    }
    return port
}

def sendCmd(command) {
    state.lastCommand = command
    def connection = device.currentValue("connection")
    if (connection == "LAN") {
        sendLanCmd(command)
    } else if (connection == "CLOUD") {
        sendKasaCmd(command)
    } else if (connection == "AltLAN") {
        sendTcpCmd(command)
    } else {
        logWarn("sendCmd: attribute connection is not set.")
    }
}

///////////////////////////////////
def sendLanCmd(command) {
    logDebug("sendLanCmd: [ip: ${getDataValue("deviceIP")}, cmd: ${command}]")
    def myHubAction = new hubitat.device.HubAction(
        outputXOR(command),
        hubitat.device.Protocol.LAN,
        [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
         destinationAddress: "${getDataValue("deviceIP")}:${getPort()}",
         encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
         parseWarning: true,
         timeout: 9,
         ignoreResponse: false,
         callback: "parseUdp"])
    try {
        sendHubCommand(myHubAction)
    } catch (e) {
        handleCommsError()
        logWarn("sendLanCmd: LAN Error = ${e}.\n\rNo retry on this error.")
    }
}
def parseUdp(message) {
    def resp = parseLanMessage(message)
    if (resp.type == "LAN_TYPE_UDPCLIENT") {
        def clearResp = inputXOR(resp.payload)
        if (clearResp.length() > 1023) {
            if (clearResp.contains("preferred")) {
                clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
            } else if (clearResp.contains("child_num")) {
                clearResp = clearResp.substring(0,clearResp.indexOf("child_num") -2) + "}}}"
            } else {
                logWarn("parseUdp: [status: converting to altComms, error: udp msg can not be parsed]")
                logDebug("parseUdp: [messageData: ${clearResp}]")
                updateDataValue("altComms", "true")
                sendTcpCmd(state.lastCommand)
                return
            }
        }
        def cmdResp = new JsonSlurper().parseText(clearResp)
        logDebug("parseUdp: ${cmdResp}")
        distResp(cmdResp)
        setCommsError(false)
    } else {
        logDebug("parseUdp: [error: error, reason: not LAN_TYPE_UDPCLIENT, respType: ${resp.type}]")
        handleCommsError()
    }
}

def sendKasaCmd(command) {
    logDebug("sendKasaCmd: ${command}")
    def cmdResponse = ""
    def cmdBody = [
        method: "passthrough",
        params: [
            deviceId: getDataValue("deviceId"),
            requestData: "${command}"
        ]
    ]
    if (!parent.kasaCloudUrl || !parent.kasaToken) {
        logWarn("sendKasaCmd: Cloud interface not properly set up.")
        return
    }
    def sendCloudCmdParams = [
        uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}",
        requestContentType: 'application/json',
        contentType: 'application/json',
        headers: ['Accept':'application/json; version=1, */*; q=0.01'],
        timeout: 10,
        body : new groovy.json.JsonBuilder(cmdBody).toString()
    ]
    try {
        asynchttpPost("cloudParse", sendCloudCmdParams)
    } catch (e) {
        def msg = "sendKasaCmd: <b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable."
        msg += "\nAdditional Data: Error = ${e}\n\n"
        logWarn(msg)
    }
}
def cloudParse(resp, data = null) {
    try {
        response = new JsonSlurper().parseText(resp.data)
    } catch (e) {
        response = [error_code: 9999, data: e]
    }
    if (resp.status == 200 && response.error_code == 0 && resp != []) {
        def cmdResp = new JsonSlurper().parseText(response.result.responseData)
        logDebug("cloudParse: ${cmdResp}")
        distResp(cmdResp)
    } else {
        def msg = "cloudParse:\n<b>Error from the Kasa Cloud.</b> Most common cause is "
        msg += "your Kasa Token has expired.  Run Kasa Login and Token update and try again."
        msg += "\nAdditional Data: Error = ${resp.data}\n\n"
        logDebug(msg)
    }
}

def sendTcpCmd(command) {
    logDebug("sendTcpCmd: ${command}")
    try {
        interfaces.rawSocket.connect("${getDataValue("deviceIP")}",
                                     getPort().toInteger(), byteInterface: true)
    } catch (error) {
        logDebug("SendTcpCmd: [connectFailed: [ip: ${getDataValue("deviceIP")}, Error = ${error}]]")
    }
    state.response = ""
    interfaces.rawSocket.sendMessage(outputXorTcp(command))
}
def close() { interfaces.rawSocket.close() }
def socketStatus(message) {
    if (message != "receive error: Stream closed.") {
        logDebug("socketStatus: Socket Established")
    } else {
        logWarn("socketStatus = ${message}")
    }
}
def parse(message) {
    if (message != null || message != "") {
        def response = state.response.concat(message)
        state.response = response
        extractTcpResp(response)
    }
}
def extractTcpResp(response) {
    def cmdResp
    def clearResp = inputXorTcp(response)
    if (clearResp.endsWith("}}}")) {
        interfaces.rawSocket.close()
        try {
            cmdResp = parseJson(clearResp)
            distResp(cmdResp)
        } catch (e) {
            logWarn("extractTcpResp: [length: ${clearResp.length()}, clearResp: ${clearResp}, comms error: ${e}]")
        }
    } else if (clearResp.length() > 2000) {
        interfaces.rawSocket.close()
    }
}




////////////////////////////////////////
def handleCommsError() {
    Map logData = [:]
    if (state.lastCommand != "") {
        def count = state.errorCount + 1
        state.errorCount = count
        def retry = true
        def cmdData = new JSONObject(state.lastCmd)
        def cmdBody = parseJson(cmdData.cmdBody.toString())
        logData << [count: count, command: state.lastCommand]
        switch (count) {
            case 1:
            case 2:
                if (getDataValue("altComms") == "true") {
                    sendTcpCmd(state.lastCommand)
                } else {
                    sendCmd(state.lastCommand)
                }
                logDebug("handleCommsError: ${logData}")
                break
            case 3:
                logData << [setCommsError: setCommsError(true), status: "retriesDisabled"]
                logError("handleCommsError: ${logData}")
                break
            default:
                break
        }
    }
}
/////////////////////////////////////////////
def setCommsError(status) {
    if (!status) {
        sendEvent(name: "commsError", value: "false")
        state.errorCount = 0
    } else {
        sendEvent(name: "commsError", value: "false")
        return "commsErrorSet"
    }
}


////////////////////////////////////////////////////////////////////
def xxhandleCommsError() {
    if (state.lastCommand == "") { return }
    def count = state.errorCount + 1
    state.errorCount = count
    def retry = true
    def status = [count: count, command: state.lastCommand]
    if (count == 3) {
        def attemptFix = parent.fixConnection()
        status << [attemptFixResult: [attemptFix]]
    } else if (count >= 4) {
        retry = false
    }
    if (retry == true) {
        if (state.lastCommand != null) { 
            if (getDataValue("altComms") == "true") {
                sendTcpCmd(state.lastCommand)
            } else {
                sendCmd(state.lastCommand)
            }
        }
    } else {
        setCommsError()
    }
    status << [retry: retry]
    if (status.count > 2) {
        logWarn("handleCommsError: ${status}")
    } else {
        logDebug("handleCommsError: ${status}")
    }
}
///////////////////////////////////////////////////////
def xxsetCommsError() {
    if (device.currentValue("commsError") == "false") {
        def message = "Can't connect to your device at ${getDataValue("deviceIP")}:${getPort()}. "
        message += "Refer to troubleshooting guide commsError section."
        sendEvent(name: "commsError", value: "true")
        state.COMMS_ERROR = message            
        logWarn("setCommsError: <b>${message}</b>")
        runIn(15, limitPollInterval)
    }
}
/////////////////////////////////////////////////////////////////
def xxlimitPollInterval() {
    state.nonErrorPollInterval = state.pollInterval
    setPollInterval("30 minutes")
}
////////////////////////////////////////////////////////////////
def xxresetCommsError() {
    state.errorCount = 0
    if (device.currentValue("commsError") == "true") {
        sendEvent(name: "commsError", value: "false")
        setPollInterval(state.nonErrorPollInterval)
        state.remove("nonErrorPollInterval")
        state.remove("COMMS_ERROR")
        logInfo("resetCommsError: Comms error cleared!")
    }
}



private outputXOR(command) {
    def str = ""
    def encrCmd = ""
     def key = 0xAB
    for (int i = 0; i < command.length(); i++) {
        str = (command.charAt(i) as byte) ^ key
        key = str
        encrCmd += Integer.toHexString(str)
    }
       return encrCmd
}

private inputXOR(encrResponse) {
    String[] strBytes = encrResponse.split("(?<=\\G.{2})")
    def cmdResponse = ""
    def key = 0xAB
    def nextKey
    byte[] XORtemp
    for(int i = 0; i < strBytes.length; i++) {
        nextKey = (byte)Integer.parseInt(strBytes[i], 16)    // could be negative
        XORtemp = nextKey ^ key
        key = nextKey
        cmdResponse += new String(XORtemp)
    }
    return cmdResponse
}

private outputXorTcp(command) {
    def str = ""
    def encrCmd = "000000" + Integer.toHexString(command.length()) 
     def key = 0xAB
    for (int i = 0; i < command.length(); i++) {
        str = (command.charAt(i) as byte) ^ key
        key = str
        encrCmd += Integer.toHexString(str)
    }
       return encrCmd
}

private inputXorTcp(resp) {
    String[] strBytes = resp.substring(8).split("(?<=\\G.{2})")
    def cmdResponse = ""
    def key = 0xAB
    def nextKey
    byte[] XORtemp
    for(int i = 0; i < strBytes.length; i++) {
        nextKey = (byte)Integer.parseInt(strBytes[i], 16)    // could be negative
        XORtemp = nextKey ^ key
        key = nextKey
        cmdResponse += new String(XORtemp)
    }
    return cmdResponse
}

// ~~~~~ end include (1360) davegut.kasaCommunications ~~~~~

// ~~~~~ start include (1357) davegut.commonLogging ~~~~~
library (
    name: "commonLogging",
    namespace: "davegut",
    author: "Dave Gutheinz",
    description: "Common Logging Methods",
    category: "utilities",
    documentationLink: ""
)

//    Logging during development
def listAttributes(trace = false) {
    def attrs = device.getSupportedAttributes()
    def attrList = [:]
    attrs.each {
        def val = device.currentValue("${it}")
        attrList << ["${it}": val]
    }
    if (trace == true) {
        logInfo("Attributes: ${attrList}")
    } else {
        logDebug("Attributes: ${attrList}")
    }
}

//    6.7.2 Change B.  Remove driverVer()
def logTrace(msg){
    log.trace "${device.displayName}-${driverVer()}: ${msg}"
}

def logInfo(msg) { 
    if (textEnable || infoLog) {
        log.info "${device.displayName}-${driverVer()}: ${msg}"
    }
}

def debugLogOff() {
    if (logEnable) {
        device.updateSetting("logEnable", [type:"bool", value: false])
    }
    logInfo("debugLogOff")
}

def logDebug(msg) {
    if (logEnable || debugLog) {
        log.debug "${device.displayName}-${driverVer()}: ${msg}"
    }
}

def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" }

// ~~~~~ end include (1357) davegut.commonLogging ~~~~~

// ~~~~~ start include (1362) davegut.kasaLights ~~~~~
library (
    name: "kasaLights",
    namespace: "davegut",
    author: "Dave Gutheinz",
    description: "Kasa Bulb and Light Common Methods",
    category: "utilities",
    documentationLink: ""
)

def on() { setLightOnOff(1, transition_Time) }

def off() { setLightOnOff(0, transition_Time) }

def setLevel(level, transTime = transition_Time) {
    setLightLevel(level, transTime)
}

def startLevelChange(direction) {
    unschedule(levelUp)
    unschedule(levelDown)
    if (direction == "up") { levelUp() }
    else { levelDown() }
}

def stopLevelChange() {
    unschedule(levelUp)
    unschedule(levelDown)
}

def levelUp() {
    def curLevel = device.currentValue("level").toInteger()
    if (curLevel == 100) { return }
    def newLevel = curLevel + 4
    if (newLevel > 100) { newLevel = 100 }
    setLevel(newLevel, 0)
    runIn(1, levelUp)
}

def levelDown() {
    def curLevel = device.currentValue("level").toInteger()
    if (curLevel == 0 || device.currentValue("switch") == "off") { return }
    def newLevel = curLevel - 4
    if (newLevel < 0) { off() }
    else {
        setLevel(newLevel, 0)
        runIn(1, levelDown)
    }
}

def service() {
    def service = "smartlife.iot.smartbulb.lightingservice"
    if (getDataValue("feature") == "lightStrip") { service = "smartlife.iot.lightStrip" }
    return service
}

def method() {
    def method = "transition_light_state"
    if (getDataValue("feature") == "lightStrip") { method = "set_light_state" }
    return method
}

def checkTransTime(transTime) {
    if (transTime == null || transTime < 0) { transTime = 0 }
    transTime = 1000 * transTime.toInteger()
    if (transTime > 8000) { transTime = 8000 }
    return transTime
}

def checkLevel(level) {
    if (level == null || level < 0) {
        level = device.currentValue("level")
        logWarn("checkLevel: Entered level null or negative. Level set to ${level}")
    } else if (level > 100) {
        level = 100
        logWarn("checkLevel: Entered level > 100.  Level set to ${level}")
    }
    return level
}

def setLightOnOff(onOff, transTime = 0) {
    transTime = checkTransTime(transTime)
    sendCmd("""{"${service()}":{"${method()}":{"on_off":${onOff},""" +
            """"transition_period":${transTime}}}}""")
}

def setLightLevel(level, transTime = 0) {
    level = checkLevel(level)
    if (level == 0) {
        setLightOnOff(0, transTime)
    } else {
        transTime = checkTransTime(transTime)
        sendCmd("""{"${service()}":{"${method()}":{"ignore_default":1,"on_off":1,""" +
                """"brightness":${level},"transition_period":${transTime}}}}""")
    }
}

// ~~~~~ end include (1362) davegut.kasaLights ~~~~~

// ~~~~~ start include (1358) davegut.kasaColorLights ~~~~~
library (
    name: "kasaColorLights",
    namespace: "davegut",
    author: "Dave Gutheinz",
    description: "Kasa Color/CT Bulb and Light Common Methods",
    category: "utilities",
    documentationLink: ""
)

def setCircadian() {
    sendCmd("""{"${service()}":{"${method()}":{"mode":"circadian"}}}""")
}

def setHue(hue) { setColor([hue: hue]) }

def setSaturation(saturation) { setColor([saturation: saturation]) }

def setColor(Map color, transTime = transition_Time) {
    if (color == null) {
        LogWarn("setColor: Color map is null. Command not executed.")
    } else {
        def level = device.currentValue("level")
        if (color.level) { level = color.level }
        def hue = device.currentValue("hue")
        if (color.hue || color.hue == 0) { hue = color.hue.toInteger() }
        def saturation = device.currentValue("saturation")
        if (color.saturation || color.saturation == 0) { saturation = color.saturation }
        hue = Math.round(0.49 + hue * 3.6).toInteger()
        if (hue < 0 || hue > 360 || saturation < 0 || saturation > 100 || level < 0 || level > 100) {
            logWarn("setColor: Entered hue, saturation, or level out of range! (H:${hue}, S:${saturation}, L:${level}")
         } else {
            setLightColor(level, 0, hue, saturation, transTime)
        }
    }
}

def setRGB(rgb) {
    logDebug("setRGB: ${rgb}") 
    def rgbArray = rgb.split('\\,')
    def hsvData = hubitat.helper.ColorUtils.rgbToHSV([rgbArray[0].toInteger(), rgbArray[1].toInteger(), rgbArray[2].toInteger()])
    def hue = (0.5 + hsvData[0]).toInteger()
    def saturation = (0.5 + hsvData[1]).toInteger()
    def level = (0.5 + hsvData[2]).toInteger()
    def Map hslData = [
        hue: hue,
        saturation: saturation,
        level: level
        ]
    setColor(hslData)
}

def setLightColor(level, colorTemp, hue, saturation, transTime = 0) {
    level = checkLevel(level)
    if (level == 0) {
        setLightOnOff(0, transTime)
    } else {
        transTime = checkTransTime(transTime)
        sendCmd("""{"${service()}":{"${method()}":{"ignore_default":1,"on_off":1,""" +
                """"brightness":${level},"color_temp":${colorTemp},""" +
                """"hue":${hue},"saturation":${saturation},"transition_period":${transTime}}}}""")
    }
}

def bulbPresetCreate(psName) {
    if (!state.bulbPresets) { state.bulbPresets = [:] }
    psName = psName.trim().toLowerCase()
    logDebug("bulbPresetCreate: ${psName}")
    def psData = [:]
    psData["hue"] = device.currentValue("hue")
    psData["saturation"] = device.currentValue("saturation")
    psData["level"] = device.currentValue("level")
    def colorTemp = device.currentValue("colorTemperature")
    if (colorTemp == null) { colorTemp = 0 }
    psData["colTemp"] = colorTemp
    state.bulbPresets << ["${psName}": psData]
}

def bulbPresetDelete(psName) {
    psName = psName.trim()
    logDebug("bulbPresetDelete: ${psName}")
    def presets = state.bulbPresets
    if (presets.toString().contains(psName)) {
        presets.remove(psName)
    } else {
        logWarn("bulbPresetDelete: ${psName} is not a valid name.")
    }
}

def syncBulbPresets() {
    device.updateSetting("syncBulbs", [type:"bool", value: false])
    parent.syncBulbPresets(state.bulbPresets)
    return "Syncing"
}

def updatePresets(bulbPresets) {
    logInfo("updatePresets: ${bulbPresets}")
    state.bulbPresets = bulbPresets
}

def bulbPresetSet(psName, transTime = transition_Time) {
    psName = psName.trim()
    if (state.bulbPresets."${psName}") {
        def psData = state.bulbPresets."${psName}"
        def hue = Math.round(0.49 + psData.hue.toInteger() * 3.6).toInteger()
        setLightColor(psData.level, psData.colTemp, hue, psData.saturation, transTime)
    } else {
        logWarn("bulbPresetSet: ${psName} is not a valid name.")
    }
}

// ~~~~~ end include (1358) davegut.kasaColorLights ~~~~~

// ~~~~~ start include (1361) davegut.kasaEnergyMonitor ~~~~~
library (
    name: "kasaEnergyMonitor",
    namespace: "davegut",
    author: "Dave Gutheinz",
    description: "Kasa Device Energy Monitor Methods",
    category: "energyMonitor",
    documentationLink: ""
)

def setupEmFunction() {
    if (emFunction && device.currentValue("currMonthTotal") > 0) {
        state.getEnergy = "This Month"
        return "Continuing EM Function"
    } else if (emFunction) {
        zeroizeEnergyAttrs()
        state.response = ""
        state.getEnergy = "This Month"
        //    Run order / delay is critical for successful operation.
        getEnergyThisMonth()
        runIn(10, getEnergyLastMonth)
        return "Initialized"
    } else if (emFunction && device.currentValue("power") != null) {
        //    for power != null, EM had to be enabled at one time.  Set values to 0.
        zeroizeEnergyAttrs()
        state.remove("getEnergy")
        return "Disabled"
    } else {
        return "Not initialized"
    }
}

def scheduleEnergyAttrs() {
    schedule("10 0 0 * * ?", getEnergyThisMonth)
    schedule("15 2 0 1 * ?", getEnergyLastMonth)
    switch(energyPollInt) {
        case "1 minute":
            runEvery1Minute(getEnergyToday)
            break
        case "5 minutes":
            runEvery5Minutes(getEnergyToday)
            break
        default:
            runEvery30Minutes(getEnergyToday)
    }
}

def zeroizeEnergyAttrs() {
    sendEvent(name: "power", value: 0, unit: "W")
    sendEvent(name: "energy", value: 0, unit: "KWH")
    sendEvent(name: "currMonthTotal", value: 0, unit: "KWH")
    sendEvent(name: "currMonthAvg", value: 0, unit: "KWH")
    sendEvent(name: "lastMonthTotal", value: 0, unit: "KWH")
    sendEvent(name: "lastMonthAvg", value: 0, unit: "KWH")
}

def getDate() {
    def currDate = new Date()
    int year = currDate.format("yyyy").toInteger()
    int month = currDate.format("M").toInteger()
    int day = currDate.format("d").toInteger()
    return [year: year, month: month, day: day]
}

def distEmeter(emeterResp) {
    def date = getDate()
    logDebug("distEmeter: ${emeterResp}, ${date}, ${state.getEnergy}")
    def lastYear = date.year - 1
    if (emeterResp.get_realtime) {
        setPower(emeterResp.get_realtime)
    } else if (emeterResp.get_monthstat) {
        def monthList = emeterResp.get_monthstat.month_list
        if (state.getEnergy == "Today") {
            setEnergyToday(monthList, date)
        } else if (state.getEnergy == "This Month") {
            setThisMonth(monthList, date)
        } else if (state.getEnergy == "Last Month") {
            setLastMonth(monthList, date)
        } else if (monthList == []) {
            logDebug("distEmeter: monthList Empty. No data for year.")
        }
    } else {
        logWarn("distEmeter: Unhandled response = ${emeterResp}")
    }
}

def getPower() {
    if (emFunction) {
        if (device.currentValue("switch") == "on") {
            getRealtime()
        } else if (device.currentValue("power") != 0) {
            sendEvent(name: "power", value: 0, descriptionText: "Watts", unit: "W", type: "digital")
        }
    }
}

def setPower(response) {
    logDebug("setPower: ${response}")
    def power = response.power
    if (power == null) { power = response.power_mw / 1000 }
    power = (power + 0.5).toInteger()
    def curPwr = device.currentValue("power")
    def pwrChange = false
    if (curPwr != power) {
        if (curPwr == null || (curPwr == 0 && power > 0)) {
            pwrChange = true
        } else {
            def changeRatio = Math.abs((power - curPwr) / curPwr)
            if (changeRatio > 0.03) {
                pwrChange = true
            }
        }
    }
    if (pwrChange == true) {
        sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital")
    }
}

def getEnergyToday() {
    if (device.currentValue("switch") == "on") {
        state.getEnergy = "Today"
        def year = getDate().year
        logDebug("getEnergyToday: ${year}")
        runIn(5, getMonthstat, [data: year])
    }
}

def setEnergyToday(monthList, date) {
    logDebug("setEnergyToday: ${date}, ${monthList}")
    def data = monthList.find { it.month == date.month && it.year == date.year}
    def status = [:]
    def energy = 0
    if (data == null) {
        status << [msgError: "Return Data Null"]
    } else {
        energy = data.energy
        if (energy == null) { energy = data.energy_wh/1000 }
        energy = Math.round(100*energy)/100 - device.currentValue("currMonthTotal")
    }
    if (device.currentValue("energy") != energy) {
        sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH")
        status << [energy: energy]
    }
    if (status != [:]) { logInfo("setEnergyToday: ${status}") }
    if (!state.getEnergy) {
        schedule("10 0 0 * * ?", getEnergyThisMonth)
        schedule("15 2 0 1 * ?", getEnergyLastMonth)
        state.getEnergy = "This Month"
        getEnergyThisMonth()
        runIn(10, getEnergyLastMonth)
    }
}

def getEnergyThisMonth() {
    state.getEnergy = "This Month"
    def year = getDate().year
    logDebug("getEnergyThisMonth: ${year}")
    runIn(5, getMonthstat, [data: year])
}

def setThisMonth(monthList, date) {
    logDebug("setThisMonth: ${date} // ${monthList}")
    def data = monthList.find { it.month == date.month && it.year == date.year}
    def status = [:]
    def totEnergy = 0
    def avgEnergy = 0
    if (data == null) {
        status << [msgError: "Return Data Null"]
    } else {
        status << [msgError: "OK"]
        totEnergy = data.energy
        if (totEnergy == null) { totEnergy = data.energy_wh/1000 }
        if (date.day == 1) {
            avgEnergy = 0
        } else {
            avgEnergy = totEnergy /(date.day - 1)
        }
    }
    totEnergy = Math.round(100*totEnergy)/100
    avgEnergy = Math.round(100*avgEnergy)/100
    sendEvent(name: "currMonthTotal", value: totEnergy, 
              descriptionText: "KiloWatt Hours", unit: "KWH")
    status << [currMonthTotal: totEnergy]
    sendEvent(name: "currMonthAvg", value: avgEnergy, 
              descriptionText: "KiloWatt Hours per Day", unit: "KWH")
    status << [currMonthAvg: avgEnergy]
    getEnergyToday()
    logInfo("setThisMonth: ${status}")
}

def getEnergyLastMonth() {
    state.getEnergy = "Last Month"
    def date = getDate()
    def year = date.year
    if (date.month == 1) {
        year = year - 1
    }
    logDebug("getEnergyLastMonth: ${year}")
    runIn(5, getMonthstat, [data: year])
}

def setLastMonth(monthList, date) {
    logDebug("setLastMonth: ${date} // ${monthList}")
    def lastMonthYear = date.year
    def lastMonth = date.month - 1
    if (date.month == 1) {
        lastMonthYear -+ 1
        lastMonth = 12
    }
    def data = monthList.find { it.month == lastMonth }
    def status = [:]
    def totEnergy = 0
    def avgEnergy = 0
    if (data == null) {
        status << [msgError: "Return Data Null"]
    } else {
        status << [msgError: "OK"]
        def monthLength
        switch(lastMonth) {
            case 4:
            case 6:
            case 9:
            case 11:
                monthLength = 30
                break
            case 2:
                monthLength = 28
                if (lastMonthYear == 2020 || lastMonthYear == 2024 || lastMonthYear == 2028) { 
                    monthLength = 29
                }
                break
            default:
                monthLength = 31
        }
        totEnergy = data.energy
        if (totEnergy == null) { totEnergy = data.energy_wh/1000 }
        avgEnergy = totEnergy / monthLength
    }
    totEnergy = Math.round(100*totEnergy)/100
    avgEnergy = Math.round(100*avgEnergy)/100
    sendEvent(name: "lastMonthTotal", value: totEnergy, 
              descriptionText: "KiloWatt Hours", unit: "KWH")
    status << [lastMonthTotal: totEnergy]
    sendEvent(name: "lastMonthAvg", value: avgEnergy, 
              descriptionText: "KiloWatt Hoursper Day", unit: "KWH")
    status << [lastMonthAvg: avgEnergy]
    logInfo("setLastMonth: ${status}")
}

def getRealtime() {
    def feature = getDataValue("feature")
    if (getDataValue("plugNo") != null) {
        sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
                """"emeter":{"get_realtime":{}}}""")
    } else if (feature.contains("Bulb") || feature == "lightStrip") {
        sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""")
    } else {
        sendCmd("""{"emeter":{"get_realtime":{}}}""")
    }
}

def getMonthstat(year) {
    def feature = getDataValue("feature")
    if (getDataValue("plugNo") != null) {
        sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
                """"emeter":{"get_monthstat":{"year": ${year}}}}""")
    } else if (feature.contains("Bulb") || feature == "lightStrip") {
        sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""")
    } else {
        sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""")
    }
}

// ~~~~~ end include (1361) davegut.kasaEnergyMonitor ~~~~~
