/*    TP-Link SMART API / PROTOCOL DRIVER SERIES for plugs, switches, bulbs, hubs and Hub-connected devices.
        Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
=================================================================================================*/
//def type() { return "tpLink_hub" }
//def gitPath() { return "DaveGut/tpLink_Hubitat/main/Drivers/" }
def type() {return "kasaSmart_hub" }
def gitPath() { return "DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/" }

metadata {
    definition (name: "kasaSmart_hub", namespace: nameSpace(), author: "Dave Gutheinz", 
                importUrl: "https://raw.githubusercontent.com/${gitPath()}${type()}.groovy")
    {
        capability "Switch"        //    Turns on or off.  easier Alexa/google integration
        command "configureAlarm", [
            [name: "Alarm Type", constraints: alarmTypes(), type: "ENUM"],
            [name: "Volume", constraints: ["low", "normal", "high"], type: "ENUM"],
            [name: "Duration", type: "NUMBER"]
        ]
        command "playAlarmConfig", [
            [name: "Alarm Type", constraints: alarmTypes(), type: "ENUM"],
            [name: "Volume", constraints: ["low", "normal", "high"], type: "ENUM"],
            [name: "Duration", type: "NUMBER"]
        ]
        attribute "alarmConfig", "JSON_OBJECT"
        attribute "commsError", "string"
    }
    preferences {
        input ("childPollInt", "enum", title: "CHILD DEVICE Poll interval (seconds)",
               options: ["5 sec", "10 sec", "15 sec", "30 sec", "1 min", "5 min"], 
               defaultValue: "30 sec")
        commonPreferences()
        securityPreferences()
    }
}

def installed() { 
    if (type().contains("kasaSmart")) {
        updateDataValue("deviceIp", getDataValue("deviceIP"))
        removeDataValue("deviceIP")
    }
    runIn(5, updated)
}

def updated() { commonUpdated() }
def delayedUpdates() {
    Map logData = [alarmConfig: getAlarmConfig()]
    logData << [childPoll: setChildPoll()]
    logData << [common: commonDelayedUpdates()]
    logInfo("delayedUpdates: ${logData}")
    runIn(5, installChildDevices)
}

def getAlarmConfig() {
    def cmdResp = syncPassthrough([method: "get_alarm_configure"])
    def alarmData = cmdResp.result
    updateAttr("alarmConfig", alarmData)
    return alarmData
}

def on() {
    logDebug("on: play default alarm configuraiton")
    List requests = [[method: "play_alarm"]]
    requests << [method: "get_alarm_configure"]
    requests << [method: "get_device_info"]
    asyncPassthrough(createMultiCmd(requests), "on", "alarmParse")
}

def off() {
    logDebug("off: stop alarm")
    List requests =[[method: "stop_alarm"]]
    requests << [method: "get_device_info"]
    asyncPassthrough(createMultiCmd(requests), "off", "deviceParse")
}

def configureAlarm(alarmType, volume, duration=30) {
    logDebug("configureAlarm: [alarmType: ${alarmType}, volume: ${volume}, duration: ${duration}]")
    if (duration < 0) { duration = -duration }
    else if (duration == 0) { duration = 30 }
    List requests = [
        [method: "set_alarm_configure",
         params: [custom: 0,
                  type: "${alarmType}",
                  volume: "${volume}",
                  duration: duration
                 ]]]
    requests << [method: "get_alarm_configure"]
    requests << [method: "get_device_info"]
    asyncPassthrough(createMultiCmd(requests), "configureAlarm", "alarmParse")
}

def playAlarmConfig(alarmType, volume, duration=30) {
    logDebug("playAlarmConfig: [alarmType: ${alarmType}, volume: ${volume}, duration: ${duration}]")
    if (duration < 0) { duration = -duration }
    else if (duration == 0) { duration = 30 }
    List requests = [
        [method: "set_alarm_configure",
         params: [custom: 0,
                  type: "${alarmType}",
                  volume: "${volume}",
                  duration: duration
                 ]]]
    requests << [method: "get_alarm_configure"]
    requests << [method: "play_alarm"]
    requests << [method: "get_device_info"]
    asyncPassthrough(createMultiCmd(requests), "playAlarmConfig", "alarmParse")
}

def alarmParse(resp, data) {
    def  devData = parseData(resp).cmdResp.result
    logDebug("alarmParse: ${devData}")
    if (devData && devData.responses) {
        def alarmData = devData.responses.find{it.method == "get_alarm_configure"}.result
        updateAttr("alarmConfig", alarmData)
        def devInfo = devData.responses.find{it.method == "get_device_info"}
        if (devInfo) {
            def inAlarm = devInfo.result.in_alarm
            def onOff = "off"
            if (inAlarm == true) {
                onOff = "on"
                runIn(alarmData.duration + 1, refresh)
            }
            updateAttr("switch", onOff)
        }
    } else {
        updateAttr("alarmConfig", devData)
    }
}

def alarmTypes() {
     return [
         "Doorbell Ring 1", "Doorbell Ring 2", "Doorbell Ring 3", "Doorbell Ring 4",
         "Doorbell Ring 5", "Doorbell Ring 6", "Doorbell Ring 7", "Doorbell Ring 8",
         "Doorbell Ring 9", "Doorbell Ring 10", "Phone Ring", "Alarm 1", "Alarm 2",
         "Alarm 3", "Alarm 4", "Alarm 5", "Dripping Tap", "Connection 1", "Connection 2"
     ] 
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
        if (devData.in_alarm == true) { 
            onOff = "on" 
            runIn(30, refresh)
        }
        updateAttr("switch", onOff)
    }
}

//    ===== Install Methods Unique to Hub =====
def getDriverId(category, model) {
    def driver = "tapoHub-NewType"
    switch(category) {
        case "subg.trigger.contact-sensor":
            driver = "tpLink_hub_contact"
            break
        case "subg.trigger.motion-sensor":
            driver = "tpLink_hub_motion"
            break
        case "subg.trigger.button":
            if (model == "S200B") {
                driver = "tpLink_hub_button"
            }
            //    Note: Installing only sensor version for now.  Need data to install D version.
            break
        case "subg.trigger.temp-hmdt-sensor":
            driver = "tpLink_hub_tempHumidity"
            break
        case "subg.trigger":
        case "subg.trigger.water-leak-sensor":
        case "subg.plugswitch":
        case "subg.plugswitch.plug":
        case "subg.plugswitch.switch":
        case "subg.trv":
        default:
            driver = "tapoHub-NewType"
    }
    return driver
}







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

// ~~~~~ start include (1353) davegut.lib_tpLink_parents ~~~~~
library (
    name: "lib_tpLink_parents",
    namespace: "davegut",
    author: "Compied by Dave Gutheinz",
    description: "Method common to Parent Device Drivers",
    category: "utilities",
    documentationLink: ""
)

def setChildPoll() {
    if (childPollInt.contains("sec")) {
        def pollInterval = childPollInt.replace(" sec", "").toInteger()
        schedule("3/${pollInterval} * * * * ?", "pollChildren")
    } else if (childPollInt == "1 min") {
        runEvery1Minute(pollChildren)
    } else {
        runEvery5Minutes(pollChildren)
    }
    return childPollInt
}

def pollChildren() {
    Map cmdBody = [
        method: "get_child_device_list"
    ]
    asyncPassthrough(cmdBody, "pollChildren", "childPollParse")
}
def childPollParse(resp, data) {
    def childData = parseData(resp).cmdResp.result.child_device_list
    def children = getChildDevices()
    children.each { child ->
        child.devicePollParse(childData)
    }
}

def distTriggerLog(resp, data) {
    def triggerData = parseData(resp)
    def child = getChildDevice(data.data)
    child.parseTriggerLog(triggerData)
}

def installChildDevices() {
    Map logData = [:]
    def respData = syncPassthrough([method: "get_child_device_list"])
    def children = respData.result.child_device_list
    children.each {
        String childDni = it.mac
        logData << [childDni: childDni]
        def isChild = getChildDevice(childDni)
        byte[] plainBytes = it.nickname.decodeBase64()
        String alias = new String(plainBytes)
        if (isChild) {
            logDebug("installChildDevices: [${alias}: device already installed]")
        } else {
            String model = it.model
            String category = it.category
            String driver = getDriverId(category, model)
            String deviceId = it.device_id
            Map instData = [model: model, category: category, driver: driver] 
            try {
                addChildDevice("davegut", driver, childDni, 
                               [label: alias, name: model, deviceId : deviceId])
                logInfo("installChildDevices: [${alias}: Installed, data: ${instData}]")
            } catch (e) {
                logWarn("installChildDevices: [${alias}: FAILED, data ${instData}, error: ${e}]")
            }
        }
    }
}





//command "TEST"
def xxTEST() {createPollList()}
def createPollList() {
    def children = getChildDevices()
    log.info children
    List requests = []
    children.each { child ->
        Map cmdBody = [
        method: "control_child",
        params: [
            device_id: child.getDataValue("deviceId"),
            requestData: [
                method: "get_device_info"
            ]]]
//log.warn syncPassthrough(cmdBody)
        requests << cmdBody
    }
    log.debug createMultiCmd(requests)
//    asyncPassthrough(createMultiCmd(requests), "TEST", "testParse")
    log.trace syncPassthrough(createMultiCmd(requests))
}

def xyTEST() {
    def command = [
        method:"multipleRequest", 
        params:[
            requests:[
                [method:"control_child", 
                 params:[
                     device_id:"802E2AA5F05058477DAE4F4F76CF2D9020C234A6",
                     requestData:[method:"get_device_info"]]], 
                [method:"control_child", 
                 params:[
                     device_id:"802ECD75CFC5EF371DCB6CB117CD38E420C25A00", 
                     requestData:[method:get_device_info]]]]]]

    log.trace syncPassthrough(command)
}

def TEST() {
    def command = [
        method: "control_child",
//        method: "multipleRequests",
        params:[
            method: "multipleRequests",
//            method: "control_child",
            params: [requests: [
                [params:[device_id:"802E2AA5F05058477DAE4F4F76CF2D9020C234A6",
                 requestData:[method:"get_device_info"]]],
                [params:[device_id:"802ECD75CFC5EF371DCB6CB117CD38E420C25A00",
                 requestData:[method:"get_device_info"]]]]]]]

    log.trace syncPassthrough(command)
}





def testParse(resp, data) {
    log.warn parseData(resp)
}



def childDeviceInfo() {
    List requests = [
        [method: "set_alarm_configure",
         params: [custom: 0,
                  type: "${alarmType}",
                  volume: "${volume}",
                  duration: duration
                 ]]]
    requests << [method: "get_alarm_configure"]
    requests << [method: "play_alarm"]
    requests << [method: "get_device_info"]
    asyncPassthrough(createMultiCmd(requests), "playAlarmConfig", "alarmParse")






/*    

        Map cmdBody = [
        method: "multipleRequest",
        params: [requests: requests]]

    List requests = [
        [method: "set_alarm_configure",
         params: [custom: 0,
                  type: "${alarmType}",
                  volume: "${volume}",
                  duration: duration
                 ]]]
    requests << [method: "get_alarm_configure"]
    requests << [method: "play_alarm"]
    requests << [method: "get_device_info"]
    asyncPassthrough(createMultiCmd(requests), "playAlarmConfig", "alarmParse")

    Map cmdBody = [
        method: "multipleRequest",
        params: [requests: requests]]



        method: "control_child",
        params: [
            device_id: getDataValue("deviceId"),
            requestData: [
                method: "get_device_running_info"
//                method: "get_trigger_logs",
//                params: [page_size: 5,"start_id": 0]
            ]
        ]
*/    
}



// ~~~~~ end include (1353) davegut.lib_tpLink_parents ~~~~~

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
