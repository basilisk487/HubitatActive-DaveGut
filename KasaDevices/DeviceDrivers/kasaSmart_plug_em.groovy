/*    TP-Link SMART API / PROTOCOL DRIVER SERIES for plugs, switches, bulbs, hubs and Hub-connected devices.
        Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
=================================================================================================*/
//def type() { return "tpLink_plug_em" }
//def gitPath() { return "DaveGut/tpLink_Hubitat/main/Drivers/" }
def type() {return "kasaSmart_plug_em" }
def gitPath() { return "DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/" }

metadata {
    definition (name: "kasaSmart_plug_em", namespace: nameSpace(), author: "Dave Gutheinz", 
                importUrl: "https://raw.githubusercontent.com/${gitPath()}${type()}.groovy")
    {
        attribute "commsError", "string"
        capability "EnergyMeter"
        capability "PowerMeter"
        attribute "energyThisMonth", "number"
    }
    preferences {
        commonPreferences()
        plugSwitchPreferences()
        input ("powerProtect", "bool", title: "Enable Power Protection", defaultValue: false)
        input ("pwrProtectWatts", "NUMBER", title: "Power Protection Watts (Max 1660)", 
               defaultValue: 1000)
        securityPreferences()
    }
}

def installed() { 
    runIn(5, updated)
}

def updated() { commonUpdated() }

def delayedUpdates() {
    Map logData = [setAutoOff: setAutoOff()]
    logData << [setDefaultState: setDefaultState()]
    logData << [setPowerProtect: setPowerProtect()]
    logData << [common: commonDelayedUpdates()]
    logInfo("delayedUpdates: ${logData}")
}

def setPowerProtect() {
    Map logData = [:]
    if (pwrProtectWatts > 1660) {
        logWarn("setPowerProtect: entered watts exceed 1660.  Aborting Power Protect Setup.")
        device.updateSetting("pwrProtectWatts", [type: "number", value: 1000])
        logData << [FAILED: "Invalid User Entry"]
    } else {
        List requests = [[method: "set_protection_power",
                          params: [protection_power: pwrProtectWatts,
                                   enabled: powerProtect]]]
        requests << [method: "get_protection_power"]
        def devData = syncPassthrough(createMultiCmd(requests))
        def data = devData.result.responses.find { it.method == "get_protection_power" }.result
        device.updateSetting("pwrProtectWatts", [type: "number", value: data.protection_power])
        device.updateSetting("powerProtect", [type: "bool", value: data.enabled])
        logData << [powerProtect: data.enabled, pwrProtectWatts: data.protection_power]
    }
    return logData
}

def deviceParse(resp, data=null) {
    def cmdResp = parseData(resp)
    if (cmdResp.status == "OK") {
        def devData = cmdResp.cmdResp.result
        if (devData.responses) {
            devData = devData.responses.find{it.method == "get_device_info"}.result
        }
        logDebug("deviceParse: ${devData}")
        def onOff = "off"
        if (devData.device_on == true) { onOff = "on" }
        updateAttr("switch", onOff)
    }
    runIn(5, energyPoll)
}

def energyPoll() {
    asyncPassthrough([method: "get_energy_usage"], "energyPoll", "parseEnergyPoll")
}
def parseEnergyPoll(resp, data=null) {
    def devData = parseData(resp)
    if(devData.status == "OK") {
        devData = devData.cmdResp.result
        logDebug("parseEnergyPoll: [devData: ${devData}, data: ${data}]")
        updateAttr("power", devData.current_power)
        updateAttr("energy", devData.today_energy/1000)
        updateAttr("energyThisMonth", (devData.month_energy/1000).toInteger())
    }
}







// ~~~~~ start include (1354) davegut.lib_tpLink_CapSwitch ~~~~~
library (
    name: "lib_tpLink_CapSwitch",
    namespace: "davegut",
    author: "Compied by Dave Gutheinz",
    description: "Hubitat Capability Switch Methods for TPLink SMART devices.",
    category: "utilities",
    documentationLink: ""
)

capability "Switch"

def plugSwitchPreferences() {
    input ("autoOffEnable", "bool", title: "Enable Auto Off", defaultValue: false)
    input ("autoOffTime", "NUMBER", title: "Auto Off Time (minutes)", defaultValue: 120)
    input ("defState", "enum", title: "Power Loss Default State",
           options: ["lastState", "on", "off"], defaultValue: "lastState")
}

def on() {
    setPower(true)
    if (autoOffEnable) {
        runIn(5 + 60 * autoOffTime.toInteger(), refresh)
    }
}

def off() {
    setPower(false)
    unschedule(off)
}

def setPower(onOff) {
    logDebug("setPower: [device_on: ${onOff}]")
    List requests = [[
        method: "set_device_info",
        params: [device_on: onOff]]]
    requests << [method: "get_device_info"]
    asyncPassthrough(createMultiCmd(requests), "setPower", "deviceParse")
}

def setAutoOff() {
    List requests =  [[method: "set_auto_off_config",
                       params: [enable:autoOffEnable,
                                delay_min: autoOffTime.toInteger()]]]
    requests << [method: "get_auto_off_config"]
    def devData = syncPassthrough(createMultiCmd(requests))
    Map retData = [cmdResp: "ERROR"]
    if (cmdResp != "ERROR") {
        def data = devData.result.responses.find { it.method == "get_auto_off_config" }
        device.updateSetting("autoOffTime", [type: "number", value: data.result.delay_min])
        device.updateSetting("autoOffEnable", [type: "bool", value: data.result.enable])
        retData = [enable: data.result.enable, time: data.result.delay_min]
    }
    return retData
}

def setDefaultState() {
    def type = "last_states"
    def state = []
    if (defState == "on") {
        type = "custom"
        state = [on: true]
    } else if (defState == "off") {
        type = "custom"
        state = [on: false]
    }
    List requests = [[method: "set_device_info",
                      params: [default_states: [type: type, state: state]]]]
    requests << [method: "get_device_info"]
    def devData = syncPassthrough(createMultiCmd(requests))
    Map retData = [cmdResp: "ERROR"]
    if (cmdResp != "ERROR") {
        def data = devData.result.responses.find { it.method == "get_device_info" }
        def defaultStates = data.result.default_states
        def newState = "lastState"
        if (defaultStates.type == "custom"){
            newState = "off"
            if (defaultStates.state.on == true) {
                newState = "on"
            }
        }
        device.updateSetting("defState", [type: "enum", value: newState])
        retData = [defState: newState]
    }
    return retData
}

// ~~~~~ end include (1354) davegut.lib_tpLink_CapSwitch ~~~~~

// ~~~~~ start include (1335) davegut.lib_tpLink_common ~~~~~
library (
    name: "lib_tpLink_common",
    namespace: "davegut",
    author: "Compied by Dave Gutheinz",
    description: "Method common to tpLink device DRIVERS",
    category: "utilities",
    documentationLink: ""
)
def driverVer() { 
    if (type().contains("kasaSmart")) { return "2.3.6"}
    else { return "1.1" }
}

def nameSpace() { return "davegut" }

capability "Refresh"

def commonPreferences() {
    input ("nameSync", "enum", title: "Synchronize Names",
           options: ["none": "Don't synchronize",
                     "device" : "TP-Link device name master",
                     "Hubitat" : "Hubitat label master"],
           defaultValue: "none")
    input ("pollInterval", "enum", title: "Poll Interval (< 1 min can cause issues)",
           options: ["5 sec", "10 sec", "30 sec", "1 min", "10 min"],
           defaultValue: "10 min")
    input ("developerData", "bool", title: "Get Data for Developer", defaultValue: false)
    input ("rebootDev", "bool", title: "Reboot Device then run Save Preferences", defaultValue: false)
}

def commonUpdated() {
    unschedule()
    Map logData = [:]
    if (rebootDev == true) {
        runInMillis(50, rebootDevice)
        device.updateSetting("rebootDev",[type:"bool", value: false])
        pauseExecution(5000)
    }
    updateAttr("commsError", false)
    state.errorCount = 0
    state.lastCmd = ""
    logData << [login: setLoginInterval()]
    logData << setLogsOff()
    logData << deviceLogin()
    pauseExecution(5000)
    if (logData.status == "ERROR") {
        logError("updated: ${logData}")
    } else {
        logInfo("updated: ${logData}")
    }
    runIn(3, delayedUpdates)
    pauseExecution(10000)
}

def commonDelayedUpdates() {
    Map logData = [syncName: syncName()]
    logData << [pollInterval: setPollInterval()]
    if (developerData) { getDeveloperData() }
    runEvery10Minutes(refresh)
    logData << [refresh: "15 mins"]
    refresh()
    return logData
}

def rebootDevice() {
    logWarn("rebootDevice: Rebooting device per preference request")
    def result = syncPassthrough([method: "device_reboot"])
    logWarn("rebootDevice: ${result}")
}

def setPollInterval() {
    def method = "poll"
    if (getDataValue("capability") == "plug_em") {
        method = "emPoll"
    }
    if (pollInterval.contains("sec")) {
        def interval= pollInterval.replace(" sec", "").toInteger()
        def start = Math.round((interval-1) * Math.random()).toInteger()
        schedule("${start}/${interval} * * * * ?", method)
        logWarn("setPollInterval: Polling intervals of less than one minute " +
                "can take high resources and may impact hub performance.")
    } else {
        def interval= pollInterval.replace(" min", "").toInteger()
        def start = Math.round(59 * Math.random()).toInteger()
        schedule("${start} */${interval} * * * ?", method)
    }
    return pollInterval
}

def setLoginInterval() {
    def startS = Math.round((59) * Math.random()).toInteger()
    def startM = Math.round((59) * Math.random()).toInteger()
    def startH = Math.round((11) * Math.random()).toInteger()
    schedule("${startS} ${startM} ${startH}/12 * * ?", "deviceLogin")
    return "12 hrs"
}

def syncName() {
    def logData = [syncName: nameSync]
    if (nameSync == "none") {
        logData << [status: "Label Not Updated"]
    } else {
        def cmdResp
        String nickname
        if (nameSync == "device") {
            cmdResp = syncPassthrough([method: "get_device_info"])
            nickname = cmdResp.result.nickname
        } else if (nameSync == "Hubitat") {
            nickname = device.getLabel().bytes.encodeBase64().toString()
            List requests = [[method: "set_device_info",params: [nickname: nickname]]]
            requests << [method: "get_device_info"]
            cmdResp = syncPassthrough(createMultiCmd(requests))
            cmdResp = cmdResp.result.responses.find { it.method == "get_device_info" }
            nickname = cmdResp.result.nickname
        }
        byte[] plainBytes = nickname.decodeBase64()
        String label = new String(plainBytes)
        device.setLabel(label)
        logData << [nickname: nickname, label: label, status: "Label Updated"]
    }
    device.updateSetting("nameSync",[type: "enum", value: "none"])
    return logData
}

def getDeveloperData() {
    device.updateSetting("developerData",[type:"bool", value: false])
    def attrs = listAttributes()
    Date date = new Date()
    Map devData = [
        currentTime: date.toString(),
        lastLogin: state.lastSuccessfulLogin,
        name: device.getName(),
        status: device.getStatus(),
        aesKey: aesKey,
        cookie: getDataValue("deviceCookie"),
        tokenLen: getDataValue("deviceToken"),
        dataValues: device.getData(),
        attributes: attrs,
        cmdResp: syncPassthrough([method: "get_device_info"]),
        childData: getChildDevData()
    ]
    logWarn("DEVELOPER DATA: ${devData}")
}

def getChildDevData(){
    Map cmdBody = [
        method: "get_child_device_list"
    ]
    def childData = syncPassthrough(cmdBody)
    if (childData.error_code == 0) {
        return childData.result.child_device_list
    } else {
        return "noChildren"
    }
}

def deviceLogin() {
    Map logData = [:]
    def handshakeData = handshake(getDataValue("deviceIP"))
    if (handshakeData.respStatus == "OK") {
        Map credentials = [encUsername: getDataValue("encUsername"), 
                           encPassword: getDataValue("encPassword")]
        def tokenData = loginDevice(handshakeData.cookie, handshakeData.aesKey, 
                                    credentials, getDataValue("deviceIP"))
        if (tokenData.respStatus == "OK") {
            logData << [rsaKeys: handshakeData.rsaKeys,
                        cookie: handshakeData.cookie,
                        aesKey: handshakeData.aesKey,
                        token: tokenData.token]

            device.updateSetting("aesKey", [type:"password", value: handshakeData.aesKey])
            updateDataValue("deviceCookie", handshakeData.cookie)
            updateDataValue("deviceToken", tokenData.token)
            logData << [status: "OK"]
        } else {
            logData << [status: "ERROR.",tokenData: tokenData]
        }
    } else {
        logData << [status: "ERROR",handshakeData: handshakeData]
    }
    Map logStatus = [:]
    if (logData.status == "OK") {
        logInfo("deviceLogin: ${logData}")
        logStatus << [logStatus: "SUCCESS"]
    } else {
        logWarn("deviceLogin: ${logData}")
        logStatus << [logStatus: "FAILURE"]
    }
    return logStatus
}

def refresh() {
    logDebug("refresh")
    asyncPassthrough([method: "get_device_info"], "refresh", "deviceParse")
}

def poll() {
    logDebug("poll")
    asyncPassthrough([method: "get_device_running_info"], "poll", "pollParse")
}

def pollParse(resp, data=null) {
    def cmdResp = parseData(resp)
    if (cmdResp.status == "OK") {
        def devData = cmdResp.cmdResp.result
        def onOff = "off"
        if (devData.device_on == true) { onOff ="on" }
        updateAttr("switch", onOff)
    }
}

def emPoll() {
    logDebug("poll")
    List requests = [[method: "get_device_running_info"]]
    requests << [method: "get_energy_usage"]
    asyncPassthrough(createMultiCmd(requests), "emPoll", "emPollParse")
}

def emPollParse(resp, data=null) {
    def cmdResp = parseData(resp)
    if (cmdResp.status == "OK") {
        def devData = cmdResp.cmdResp.result.responses.find{it.method == "get_device_running_info"}.result
        def onOff = "off"
        if (devData.device_on == true) { onOff ="on" }
        updateAttr("switch", onOff)
        def emData = cmdResp.cmdResp.result.responses.find{it.method == "get_energy_usage"}
        if (emData.error_code == 0) {
            emData = emData.result
            updateAttr("power", emData.current_power)
        }
    }
}

def updateAttr(attr, value) {
    if (device.currentValue(attr) != value) {
        sendEvent(name: attr, value: value)
    }
}

// ~~~~~ end include (1335) davegut.lib_tpLink_common ~~~~~

// ~~~~~ start include (1327) davegut.lib_tpLink_comms ~~~~~
library (
    name: "lib_tpLink_comms",
    namespace: "davegut",
    author: "Dave Gutheinz",
    description: "Tapo Communications",
    category: "utilities",
    documentationLink: ""
)
import org.json.JSONObject
import groovy.json.JsonOutput
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

def createMultiCmd(requests) {
    Map cmdBody = [
        method: "multipleRequest",
        params: [requests: requests]]
    return cmdBody
}

def asyncPassthrough(cmdBody, method, action) {
    if (devIp == null) { devIp = getDataValue("deviceIP") }    //    used for Kasa Compatibility
    Map cmdData = [cmdBody: cmdBody, method: method, action: action]
    state.lastCmd = cmdData
    logDebug("asyncPassthrough: ${cmdData}")
    def uri = "http://${getDataValue("deviceIP")}/app?token=${getDataValue("deviceToken")}"
    Map reqBody = createReqBody(cmdBody)
    asyncPost(uri, reqBody, action, getDataValue("deviceCookie"), method)
}

def syncPassthrough(cmdBody) {
    if (devIp == null) { devIp = getDataValue("deviceIP") }    //    used for Kasa Compatibility
    Map logData = [cmdBody: cmdBody]
    def uri = "http://${getDataValue("deviceIP")}/app?token=${getDataValue("deviceToken")}"
    Map reqBody = createReqBody(cmdBody)
    def resp = syncPost(uri, reqBody, getDataValue("deviceCookie"))
    def cmdResp = "ERROR"
    if (resp.status == "OK") {
        try {
            cmdResp = new JsonSlurper().parseText(decrypt(resp.resp.data.result.response))
            logData << [status: "OK"]
        } catch (err) {
            logData << [status: "cryptoError", error: "Error decrypting response", data: err]
        }
    } else {
        logData << [status: "postJsonError", postJsonData: resp]
    }
    if (logData.status == "OK") {
        logDebug("syncPassthrough: ${logData}")
    } else {
        logWarn("syncPassthrough: ${logData}")
    }
    return cmdResp
}

def createReqBody(cmdBody) {
    def cmdStr = JsonOutput.toJson(cmdBody).toString()
    Map reqBody = [method: "securePassthrough",
                   params: [request: encrypt(cmdStr)]]
    return reqBody
}

//    ===== Sync comms for device update =====
def syncPost(uri, reqBody, cookie=null) {
    def reqParams = [
        uri: uri,
        headers: [
            Cookie: cookie
        ],
        body : new JsonBuilder(reqBody).toString()
    ]
    logDebug("syncPost: [cmdParams: ${reqParams}]")
    Map respData = [:]
    try {
        httpPostJson(reqParams) {resp ->
            if (resp.status == 200 && resp.data.error_code == 0) {
                respData << [status: "OK", resp: resp]
            } else {
                respData << [status: "lanDataError", respStatus: resp.status, 
                    errorCode: resp.data.error_code]
            }
        }
    } catch (err) {
        respData << [status: "HTTP Failed", data: err]
    }
    return respData
}

def asyncPost(uri, reqBody, parseMethod, cookie=null, reqData=null) {
    Map logData = [:]
    def reqParams = [
        uri: uri,
        requestContentType: 'application/json',
        contentType: 'application/json',
        headers: [
            Cookie: cookie
        ],
        timeout: 4,
        body : new groovy.json.JsonBuilder(reqBody).toString()
    ]
    try {
        asynchttpPost(parseMethod, reqParams, [data: reqData])
        logData << [status: "OK"]
    } catch (e) {
        logData << [status: e, reqParams: reqParams]
    }
    if (logData.status == "OK") {
        logDebug("asyncPost: ${logData}")
    } else {
        logWarn("asyncPost: ${logData}")
        handleCommsError()
    }
}

def parseData(resp) {
    def logData = [:]
    if (resp.status == 200 && resp.json.error_code == 0) {
        def cmdResp
        try {
            cmdResp = new JsonSlurper().parseText(decrypt(resp.json.result.response))
            setCommsError(false)
        } catch (err) {
            logData << [status: "cryptoError", error: "Error decrypting response", data: err]
        }
        if (cmdResp != null && cmdResp.error_code == 0) {
            logData << [status: "OK", cmdResp: cmdResp]
        } else {
            logData << [status: "deviceDataError", cmdResp: cmdResp]
        }
    } else {
        logData << [status: "lanDataError"]
    }
    if (logData.status == "OK") {
        logDebug("parseData: ${logData}")
    } else {
        logWarn("parseData: ${logData}")
        handleCommsError()
    }
    return logData
}

def handleCommsError() {
    Map logData = [:]
    if (state.lastCommand != "") {
        def count = state.errorCount + 1
        state.errorCount = count
        def cmdData = new JSONObject(state.lastCmd)
        def cmdBody = parseJson(cmdData.cmdBody.toString())
        logData << [count: count, command: cmdData]
        switch (count) {
            case 1:
                asyncPassthrough(cmdBody, cmdData.method, cmdData.action)
                logData << [status: "commandRetry"]
                logDebug("handleCommsError: ${logData}")
                break
            case 2:
                logData << [deviceLogin: deviceLogin()]
                Map data = [cmdBody: cmdBody, method: cmdData.method, action:cmdData.action]
                runIn(2, delayedPassThrough, [data:data])
                logData << [status: "newLogin and commandRetry"]
                logWarn("handleCommsError: ${logData}")
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

def delayedPassThrough(data) {
    asyncPassthrough(data.cmdBody, data.method, data.action)
}

def setCommsError(status) {
    if (!status) {
        updateAttr("commsError", false)
        state.errorCount = 0
    } else {
        updateAttr("commsError", true)
        return "commsErrorSet"
    }
}

// ~~~~~ end include (1327) davegut.lib_tpLink_comms ~~~~~

// ~~~~~ start include (1337) davegut.lib_tpLink_security ~~~~~
library (
    name: "lib_tpLink_security",
    namespace: "davegut",
    author: "Dave Gutheinz",
    description: "tpLink RSA and AES security measures",
    category: "utilities",
    documentationLink: ""
)
import groovy.json.JsonSlurper
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.Cipher
import java.security.KeyFactory

def securityPreferences() {
    input ("aesKey", "password", title: "Storage for the AES Key")
}

//    ===== Device Login Core =====
def handshake(devIp) {
    def rsaKeys = getRsaKeys()
    Map handshakeData = [method: "handshakeData", rsaKeys: rsaKeys.keyNo]
    def pubPem = "-----BEGIN PUBLIC KEY-----\n${rsaKeys.public}-----END PUBLIC KEY-----\n"
    Map cmdBody = [ method: "handshake", params: [ key: pubPem]]
    def uri = "http://${devIp}/app"
    def respData = syncPost(uri, cmdBody)
    if (respData.status == "OK") {
        String deviceKey = respData.resp.data.result.key
        try {
            def cookieHeader = respData.resp.headers["set-cookie"].toString()
            def cookie = cookieHeader.substring(cookieHeader.indexOf(":") +1, cookieHeader.indexOf(";"))
            handshakeData << [cookie: cookie]
        } catch (err) {
            handshakeData << [respStatus: "FAILED", check: "respData.headers", error: err]
        }
        def aesArray = readDeviceKey(deviceKey, rsaKeys.private)
        handshakeData << [aesKey: aesArray]
        if (aesArray == "ERROR") {
            handshakeData << [respStatus: "FAILED", check: "privateKey"]
        } else {
            handshakeData << [respStatus: "OK"]
        }
    } else {
        handshakeData << [respStatus: "FAILED", check: "pubPem. devIp", respData: respData]
    }
    if (handshakeData.respStatus == "OK") {
        logDebug("handshake: ${handshakeData}")
    } else {
        logWarn("handshake: ${handshakeData}")
    }
    return handshakeData
}

def readDeviceKey(deviceKey, privateKey) {
    def response = "ERROR"
    def logData = [:]
    try {
        byte[] privateKeyBytes = privateKey.decodeBase64()
        byte[] deviceKeyBytes = deviceKey.getBytes("UTF-8").decodeBase64()
        Cipher instance = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        instance.init(2, KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes)))
        byte[] cryptoArray = instance.doFinal(deviceKeyBytes)
        response = cryptoArray
        logData << [cryptoArray: "REDACTED for logs", status: "OK"]
        logDebug("readDeviceKey: ${logData}")
    } catch (err) {
        logData << [status: "READ ERROR", data: err]
        logWarn("readDeviceKey: ${logData}")
    }
    return response
}

def loginDevice(cookie, cryptoArray, credentials, devIp) {
    Map tokenData = [method: "loginDevice"]
    def uri = "http://${devIp}/app"
    Map cmdBody = [method: "login_device",
                   params: [password: credentials.encPassword,
                            username: credentials.encUsername],
                   requestTimeMils: 0]
    def cmdStr = JsonOutput.toJson(cmdBody).toString()
    Map reqBody = [method: "securePassthrough", params: [request: encrypt(cmdStr, cryptoArray)]]
    def respData = syncPost(uri, reqBody, cookie)
    if (respData.status == "OK") {
        if (respData.resp.data.error_code == 0) {
            try {
                def cmdResp = decrypt(respData.resp.data.result.response, cryptoArray)
                cmdResp = new JsonSlurper().parseText(cmdResp)
                if (cmdResp.error_code == 0) {
                    tokenData << [respStatus: "OK", token: cmdResp.result.token]
                } else {
                    tokenData << [respStatus: "Error from device", 
                                  check: "cryptoArray, credentials", data: cmdResp]
                }
            } catch (err) {
                tokenData << [respStatus: "Error parsing", error: err]
            }
        } else {
            tokenData << [respStatus: "Error in respData.data", data: respData.data]
        }
    } else {
        tokenData << [respStatus: "Error in respData", data: respData]
    }
    if (tokenData.respStatus == "OK") {
        logDebug("handshake: ${tokenData}")
    } else {
        logWarn("handshake: ${tokenData}")
    }
    return tokenData
}

//    ===== AES Methods =====
//def encrypt(plainText, keyData) {
def encrypt(plainText, keyData = null) {
    if (keyData == null) {
        keyData = new JsonSlurper().parseText(aesKey)
    }
    byte[] keyenc = keyData[0..15]
    byte[] ivenc = keyData[16..31]

    def cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    SecretKeySpec key = new SecretKeySpec(keyenc, "AES")
    IvParameterSpec iv = new IvParameterSpec(ivenc)
    cipher.init(Cipher.ENCRYPT_MODE, key, iv)
    String result = cipher.doFinal(plainText.getBytes("UTF-8")).encodeBase64().toString()
    return result.replace("\r\n","")
}

def decrypt(cypherText, keyData = null) {
    if (keyData == null) {
        keyData = new JsonSlurper().parseText(aesKey)
    }
    byte[] keyenc = keyData[0..15]
    byte[] ivenc = keyData[16..31]

    byte[] decodedBytes = cypherText.decodeBase64()
    def cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    SecretKeySpec key = new SecretKeySpec(keyenc, "AES")
    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(ivenc))
    String result = new String(cipher.doFinal(decodedBytes), "UTF-8")
    return result
}

//    ===== RSA Key Methods =====
def getRsaKeys() {
    def keyNo = Math.round(5 * Math.random()).toInteger()
    def keyData = keyData()
    def RSAKeys = keyData.find { it.keyNo == keyNo }
    return RSAKeys
}

def keyData() {
/*    User Note.  You can update these keys at you will using the site:
        https://www.devglan.com/online-tools/rsa-encryption-decryption
    with an RSA Key Size: 1024 bit
    This is at your risk.*/
    return [
        [
            keyNo: 0,
            public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDGr/mHBK8aqx7UAS+g+TuAvE3J2DdwsqRn9MmAkjPGNon1ZlwM6nLQHfJHebdohyVqkNWaCECGXnftnlC8CM2c/RujvCrStRA0lVD+jixO9QJ9PcYTa07Z1FuEze7Q5OIa6pEoPxomrjxzVlUWLDXt901qCdn3/zRZpBdpXzVZtQIDAQAB",
            private: "MIICeAIBADANBgkqhkiG9w0BAQEFAASCAmIwggJeAgEAAoGBAMav+YcErxqrHtQBL6D5O4C8TcnYN3CypGf0yYCSM8Y2ifVmXAzqctAd8kd5t2iHJWqQ1ZoIQIZed+2eULwIzZz9G6O8KtK1EDSVUP6OLE71An09xhNrTtnUW4TN7tDk4hrqkSg/GiauPHNWVRYsNe33TWoJ2ff/NFmkF2lfNVm1AgMBAAECgYEAocxCHmKBGe2KAEkq+SKdAxvVGO77TsobOhDMWug0Q1C8jduaUGZHsxT/7JbA9d1AagSh/XqE2Sdq8FUBF+7vSFzozBHyGkrX1iKURpQFEQM2j9JgUCucEavnxvCqDYpscyNRAgqz9jdh+BjEMcKAG7o68bOw41ZC+JyYR41xSe0CQQD1os71NcZiMVqYcBud6fTYFHZz3HBNcbzOk+RpIHyi8aF3zIqPKIAh2pO4s7vJgrMZTc2wkIe0ZnUrm0oaC//jAkEAzxIPW1mWd3+KE3gpgyX0cFkZsDmlIbWojUIbyz8NgeUglr+BczARG4ITrTV4fxkGwNI4EZxBT8vXDSIXJ8NDhwJBAIiKndx0rfg7Uw7VkqRvPqk2hrnU2aBTDw8N6rP9WQsCoi0DyCnX65Hl/KN5VXOocYIpW6NAVA8VvSAmTES6Ut0CQQCX20jD13mPfUsHaDIZafZPhiheoofFpvFLVtYHQeBoCF7T7vHCRdfl8oj3l6UcoH/hXMmdsJf9KyI1EXElyf91AkAvLfmAS2UvUnhX4qyFioitjxwWawSnf+CewN8LDbH7m5JVXJEh3hqp+aLHg1EaW4wJtkoKLCF+DeVIgbSvOLJw"
        ],[
            keyNo: 1,
            public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCshy+qBKbJNefcyJUZ/3i+3KyLji6XaWEWvebUCC2r9/0jE6hc89AufO41a13E3gJ2es732vaxwZ1BZKLy468NnL+tg6vlQXaPkDcdunQwjxbTLNL/yzDZs9HRju2lJnupcksdJWBZmjtztMWQkzBrQVeSKzSTrKYK0s24EEXmtQIDAQAB",
            private: "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAKyHL6oEpsk159zIlRn/eL7crIuOLpdpYRa95tQILav3/SMTqFzz0C587jVrXcTeAnZ6zvfa9rHBnUFkovLjrw2cv62Dq+VBdo+QNx26dDCPFtMs0v/LMNmz0dGO7aUme6lySx0lYFmaO3O0xZCTMGtBV5IrNJOspgrSzbgQRea1AgMBAAECgYBSeiX9H1AkbJK1Z2ZwEUNF6vTJmmUHmScC2jHZNzeuOFVZSXJ5TU0+jBbMjtE65e9DeJ4suw6oF6j3tAZ6GwJ5tHoIy+qHRV6AjA8GEXjhSwwVCyP8jXYZ7UZyHzjLQAK+L0PvwJY1lAtns/Xmk5GH+zpNnhEmKSZAw23f7wpj2QJBANVPQGYT7TsMTDEEl2jq/ZgOX5Djf2VnKpPZYZGsUmg1hMwcpN/4XQ7XOaclR5TO/CJBJl3UCUEVjdrR1zdD8g8CQQDPDoa5Y5UfhLz4Ja2/gs2UKwO4fkTqqR6Ad8fQlaUZ55HINHWFd8FeERBFgNJzszrzd9BBJ7NnZM5nf2OPqU77AkBLuQuScSZ5HL97czbQvwLxVMDmLWyPMdVykOvLC9JhPgZ7cvuwqnlWiF7mEBzeHbBx9JDLJDd4zE8ETBPLgapPAkAHhCR52FaSdVQSwfNjr1DdHw6chODlj8wOp8p2FOiQXyqYlObrOGSpkH8BtuJs1sW+DsxdgR5vE2a2tRYdIe0/AkEAoQ5MzLcETQrmabdVCyB9pQAiHe4yY9e1w7cimsLJOrH7LMM0hqvBqFOIbSPrZyTp7Ie8awn4nTKoZQtvBfwzHw=="
        ],[
            keyNo: 2,
            public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCBeqRy4zAOs63Sc5yc0DtlFXG1stmdD6sEfUiGjlsy0S8aS8X+Qcjcu5AK3uBBrkVNIa8djXht1bd+pUof5/txzWIMJw9SNtNYqzSdeO7cCtRLzuQnQWP7Am64OBvYkXn2sUqoaqDE50LbSQWbuvZw0Vi9QihfBYGQdlrqjCPUsQIDAQAB",
            private: "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAIF6pHLjMA6zrdJznJzQO2UVcbWy2Z0PqwR9SIaOWzLRLxpLxf5ByNy7kAre4EGuRU0hrx2NeG3Vt36lSh/n+3HNYgwnD1I201irNJ147twK1EvO5CdBY/sCbrg4G9iRefaxSqhqoMTnQttJBZu69nDRWL1CKF8FgZB2WuqMI9SxAgMBAAECgYBBi2wkHI3/Y0Xi+1OUrnTivvBJIri2oW/ZXfKQ6w+PsgU+Mo2QII0l8G0Ck8DCfw3l9d9H/o2wTDgPjGzxqeXHAbxET1dS0QBTjR1zLZlFyfAs7WO8tDKmHVroUgqRkJgoQNQlBSe1E3e7pTgSKElzLuALkRS6p1jhzT2wu9U04QJBAOFr/G36PbQ6NmDYtVyEEr3vWn46JHeZISdJOsordR7Wzbt6xk6/zUDHq0OGM9rYrpBy7PNrbc0JuQrhfbIyaHMCQQCTCvETjXCMkwyUrQT6TpxVzKEVRf1rCitnNQCh1TLnDKcCEAnqZT2RRS3yNXTWFoJrtuEHMGmwUrtog9+ZJBlLAkEA2qxdkPY621XJIIO404mPgM7rMx4F+DsE7U5diHdFw2fO5brBGu13GAtZuUQ7k2W1WY0TDUO+nTN8XPDHdZDuvwJABu7TIwreLaKZS0FFJNAkCt+VEL22Dx/xn/Idz4OP3Nj53t0Guqh/WKQcYHkowxdYmt+KiJ49vXSJJYpiNoQ/NQJAM1HCl8hBznLZLQlxrCTdMvUimG3kJmA0bUNVncgUBq7ptqjk7lp5iNrle5aml99foYnzZeEUW6jrCC7Lj9tg+w=="
        ],[
            keyNo: 3,
            public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCFYaoMvv5kBxUUbp4PQyd7RoZlPompsupXP2La0qGGxacF98/88W4KNUqLbF4X5BPqxoEA+VeZy75qqyfuYbGQ4fxT6usE/LnzW8zDY/PjhVBht8FBRyAUsoYAt3Ip6sDyjd9YzRzUL1Q/OxCgxz5CNETYxcNr7zfMshBHDmZXMQIDAQAB",
            private: "MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAIVhqgy+/mQHFRRung9DJ3tGhmU+iamy6lc/YtrSoYbFpwX3z/zxbgo1SotsXhfkE+rGgQD5V5nLvmqrJ+5hsZDh/FPq6wT8ufNbzMNj8+OFUGG3wUFHIBSyhgC3cinqwPKN31jNHNQvVD87EKDHPkI0RNjFw2vvN8yyEEcOZlcxAgMBAAECgYA3NxjoMeCpk+z8ClbQRqJ/e9CC9QKUB4bPG2RW5b8MRaJA7DdjpKZC/5CeavwAs+Ay3n3k41OKTTfEfJoJKtQQZnCrqnZfq9IVZI26xfYo0cgSYbi8wCie6nqIBdu9k54nqhePPshi22VcFuOh97xxPvY7kiUaRbbKqxn9PFwrYQJBAMsO3uOnYSJxN/FuxksKLqhtNei2GUC/0l7uIE8rbRdtN3QOpcC5suj7id03/IMn2Ks+Vsrmi0lV4VV/c8xyo9UCQQCoKDlObjbYeYYdW7/NvI6cEntgHygENi7b6WFk+dbRhJQgrFH8Z/Idj9a2E3BkfLCTUM1Z/Z3e7D0iqPDKBn/tAkBAHI3bKvnMOhsDq4oIH0rj+rdOplAK1YXCW0TwOjHTd7ROfGFxHDCUxvacVhTwBCCw0JnuriPEH81phTg2kOuRAkAEPR9UrsqLImUTEGEBWqNto7mgbqifko4T1QozdWjI10K0oCNg7W3Y+Os8o7jNj6cTz5GdlxsHp4TS/tczAH7xAkBY6KPIlF1FfiyJAnBC8+jJr2h4TSPQD7sbJJmYw7mvR+f1T4tsWY0aGux69hVm8BoaLStBVPdkaENBMdP+a07u"
        ],[
            keyNo: 4,
            public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQClF0yuCpo3r1ZpYlGcyI5wy5nnvZdOZmxqz5U2rklt2b8+9uWhmsGdpbTv5+qJXlZmvUKbpoaPxpJluBFDJH2GSpq3I0whh0gNq9Arzpp/TDYaZLb6iIqDMF6wm8yjGOtcSkB7qLQWkXpEN9T2NsEzlfTc+GTKc07QXHnzxoLmwQIDAQAB",
            private: "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAKUXTK4KmjevVmliUZzIjnDLmee9l05mbGrPlTauSW3Zvz725aGawZ2ltO/n6oleVma9Qpumho/GkmW4EUMkfYZKmrcjTCGHSA2r0CvOmn9MNhpktvqIioMwXrCbzKMY61xKQHuotBaRekQ31PY2wTOV9Nz4ZMpzTtBcefPGgubBAgMBAAECgYB4wCz+05RvDFk45YfqFCtTRyg//0UvO+0qxsBN6Xad2XlvlWjqJeZd53kLTGcYqJ6rsNyKOmgLu2MS8Wn24TbJmPUAwZU+9cvSPxxQ5k6bwjg1RifieIcbTPC5wHDqVy0/Ur7dt+JVMOHFseR/pElDw471LCdwWSuFHAKuiHsaUQJBANHiPdSU3s1bbJYTLaS1tW0UXo7aqgeXuJgqZ2sKsoIEheEAROJ5rW/f2KrFVtvg0ITSM8mgXNlhNBS5OE4nSD0CQQDJXYJxKvdodeRoj+RGTCZGZanAE1naUzSdfcNWx2IMnYUD/3/2eB7ZIyQPBG5fWjc3bGOJKI+gy/14bCwXU7zVAkAdnsE9HBlpf+qOL3y0jxRgpYxGuuNeGPJrPyjDOYpBwSOnwmL2V1e7vyqTxy/f7hVfeU7nuKMB5q7z8cPZe7+9AkEAl7A6aDe+wlE069OhWZdZqeRBmLC7Gi1d0FoBwahW4zvyDM32vltEmbvQGQP0hR33xGeBH7yPXcjtOz75g+UPtQJBAL4gknJ/p+yQm9RJB0oq/g+HriErpIMHwrhNoRY1aOBMJVl4ari1Ch2RQNL9KQW7yrFDv7XiP3z5NwNDKsp/QeU="
        ],[
            keyNo: 5,
            public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQChN8Xc+gsSuhcLVM1W1E+e1o+celvKlOmuV6sJEkJecknKFujx9+T4xvyapzyePpTBn0lA9EYbaF7UDYBsDgqSwgt0El3gV+49O56nt1ELbLUJtkYEQPK+6Pu8665UG17leCiaMiFQyoZhD80PXhpjehqDu2900uU/4DzKZ/eywwIDAQAB",
            private: "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAKE3xdz6CxK6FwtUzVbUT57Wj5x6W8qU6a5XqwkSQl5yScoW6PH35PjG/JqnPJ4+lMGfSUD0RhtoXtQNgGwOCpLCC3QSXeBX7j07nqe3UQtstQm2RgRA8r7o+7zrrlQbXuV4KJoyIVDKhmEPzQ9eGmN6GoO7b3TS5T/gPMpn97LDAgMBAAECgYAy+uQCwL8HqPjoiGR2dKTI4aiAHuEv6m8KxoY7VB7QputWkHARNAaf9KykawXsNHXt1GThuV0CBbsW6z4U7UvCJEZEpv7qJiGX8UWgEs1ISatqXmiIMVosIJJvoFw/rAoScadCYyicskjwDFBVNU53EAUD3WzwEq+dRYDn52lqQQJBAMu30FEReAHTAKE/hvjAeBUyWjg7E4/lnYvb/i9Wuc+MTH0q3JxFGGMb3n6APT9+kbGE0rinM/GEXtpny+5y3asCQQDKl7eNq0NdIEBGAdKerX4O+nVDZ7PXz1kQ2ca0r1tXtY/9sBDDoKHP2fQAH/xlOLIhLaH1rabSEJYNUM0ohHdJAkBYZqhwNWtlJ0ITtvSEB0lUsWfzFLe1bseCBHH16uVwygn7GtlmupkNkO9o548seWkRpnimhnAE8xMSJY6aJ6BHAkEAuSFLKrqGJGOEWHTx8u63cxiMb7wkK+HekfdwDUzxO4U+v6RUrW/sbfPNdQ/FpPnaTVdV2RuGhg+CD0j3MT9bgQJARH86hfxp1bkyc7f1iJQT8sofdqqVz5grCV5XeGY77BNmCvTOGLfL5pOJdgALuOoP4t3e94nRYdlW6LqIVugRBQ=="
        ]
    ]
}

// ~~~~~ end include (1337) davegut.lib_tpLink_security ~~~~~

// ~~~~~ start include (1339) davegut.Logging ~~~~~
library (
    name: "Logging",
    namespace: "davegut",
    author: "Dave Gutheinz",
    description: "Common Logging and info gathering Methods",
    category: "utilities",
    documentationLink: ""
)

preferences {
    input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
    input ("infoLog", "bool", title: "Enable information logging",defaultValue: true)
    input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
}

def listAttributes() {
    def attrData = device.getCurrentStates()
    Map attrs = [:]
    attrData.each {
        attrs << ["${it.name}": it.value]
    }
    return attrs
}

def setLogsOff() {
    def logData = [logEnagle: logEnable, infoLog: infoLog, traceLog:traceLog]
    if (logEnable) {
        runIn(1800, debugLogOff)
        logData << [debugLogOff: "scheduled"]
    }
    if (traceLog) {
        runIn(1800, traceLogOff)
        logData << [traceLogOff: "scheduled"]
    }
    return logData
}

def logTrace(msg){
    if (traceLog == true) {
        log.trace "${device.displayName}-${driverVer()}: ${msg}"
    }
}

def traceLogOff() {
    device.updateSetting("traceLog", [type:"bool", value: false])
    logInfo("traceLogOff")
}

def logInfo(msg) { 
    if (textEnable || infoLog) {
        log.info "${device.displayName}-${driverVer()}: ${msg}"
    }
}

def debugLogOff() {
    device.updateSetting("logEnable", [type:"bool", value: false])
    logInfo("debugLogOff")
}

def logDebug(msg) {
    if (logEnable || debugLog) {
        log.debug "${device.displayName}-${driverVer()}: ${msg}"
    }
}

def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" }

def logError(msg) { log.error "${device.displayName}-${driverVer()}: ${msg}" }

// ~~~~~ end include (1339) davegut.Logging ~~~~~
