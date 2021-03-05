/**
 *  Lock History Connector (v.0.0.2)
 *
 * MIT License
 *
 * Copyright (c) 2018 fison67@nate.com
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
 
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

definition(
    name: "LH Connector",
    namespace: "fison67",
    author: "fison67",
    description: "A Connector between Lock History and ST",
    category: "My Apps",
    iconUrl: "https://cdn2.iconfinder.com/data/icons/Siena/256/unlock%20yellow.png",
    iconX2Url: "https://cdn2.iconfinder.com/data/icons/Siena/256/unlock%20yellow.png",
    iconX3Url: "https://cdn2.iconfinder.com/data/icons/Siena/256/unlock%20yellow.png",
    oauth: true
)

preferences {
   page(name: "mainPage")
   page(name: "devicePage")
}


def mainPage() {
	if(location.hubs.size() < 1) {
        section() {
            paragraph "[ERROR]\nSmartThings Hub not found.\nYou need a SmartThings Hub to use LH connector."
        }
        return
    }
    
    dynamicPage(name: "mainPage", title: "LH Connector", nextPage: null, uninstall: true, install: true) {
   		section("Setup"){
        	input "address", "text", title: "Server address", required: true
            href "devicePage", title: "Lock Device", description:"Select a device."
        	href url:"http://${settings.address}", style:"embedded", required:false, title:"Local Management", description:"This makes you easy to setup"
        }
        
       	section() {
            paragraph "View this SmartApp's configuration to use it in other places."
            href url:"${apiServerUrl("/api/smartapps/installations/${app.id}/config?access_token=${state.accessToken}")}", style:"embedded", required:false, title:"Config", description:"Tap, select, copy, then click \"Done\""
       	}
    }
}

def devicePage(){
    dynamicPage(name: "devicePage", title:"Choose a Lock Device") {
    	section ("Select") {
            input "lock", "capability.lock", title: "Lock", multiple: true, required: false
        }
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"
    
    if (!state.accessToken) {
        createAccessToken()
    }
    
    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"

    initialize()
}

def initialize() {
	log.debug "initialize"
    
    def options = [
     	"method": "POST",
        "path": "/settings/api/smartthings",
        "headers": [
        	"HOST": settings.address,
            "Content-Type": "application/json"
        ],
        "body":[
            "app_url":"${apiServerUrl}/api/smartapps/installations/",
            "app_id":app.id,
            "access_token":state.accessToken
        ]
    ]
    
    def myhubAction = new physicalgraph.device.HubAction(options, null, [callback: null])
    sendHubCommand(myhubAction)
}

def syncDevice(){
	log.debug "Sync Device"
    def json = request.JSON
    log.debug json
    unsubscribe()
    
    (settings.lock).each { device ->
       json.data.each { item -> 
           if(device.deviceNetworkId == item.dni){
                def str = item.list.toString().bytes.encodeAsBase64().toString()
                try{
               		device.setCustomCodeData(str)   
                }catch(err){
                	log.error err
                }
               subscribe(device, 'lock', stateChangeHandler)
           }
       }
    }
}

def getStatus(){
    def result = [:]
    (settings.lock).each { device ->
        if(device.deviceNetworkId == params.dni){
            result["lock"] = device.currentValue("lock")
            result["lockCode"] = device.currentValue("codeid") as int
//            result["eventSource"] = device.currentValue("eventSource")
        }
    }
    
    def configString = new groovy.json.JsonOutput().toJson("data":result)
    render contentType: "application/javascript", data: configString    
}

def stateChangeHandler(evt) {
    def device = evt.getDevice()
    if(!device){
    	return
    }
    
    if(state.lastState != device.deviceNetworkId + ":" + evt.value){
        
        def options = [
            "method": "POST",
            "path": ("/notify"),
            "headers": [
                "Content-Type": "application/json",
                "HOST": settings.address,
            ],
            "body":[
                "dni": device.deviceNetworkId,
                "lock": evt.value,
                "lockCode": device.currentValue("codeid") as int,
                "lockTypeId": device.currentValue("lockTypeId")
            ]
        ]
        def myhubAction = new physicalgraph.device.HubAction(options, null, [callback: null])
        sendHubCommand(myhubAction)
    }
	state.lastState = device.deviceNetworkId + ":" + evt.value
}

def serverCallback(physicalgraph.device.HubResponse hubResponse) {
    def msg, json, status
    try {
        msg = parseLanMessage(hubResponse.description)
 //       log.debug "${msg.body}"
    } catch (e) {
        logger('warn', "Exception caught while parsing data: "+e);
    }
}


def deviceList(){
	def list = getChildDevices();
    def resultList = [];
    (settings.lock).each { device ->
        def item = [name: device.displayName, dni: device.deviceNetworkId, status: device.currentStates[0].value]
        resultList.push(item)
    }
    
    def configString = new groovy.json.JsonOutput().toJson("list":resultList)
    render contentType: "application/javascript", data: configString
}

def renderConfig() {
    def configJson = new groovy.json.JsonOutput().toJson([
        description: "LH Connector API",
        platforms: [
            [
                platform: "SmartThings Lock History Connector",
                name: "LH Connector",
                app_url: apiServerUrl("/api/smartapps/installations/"),
                app_id: app.id,
                access_token:  state.accessToken
            ]
        ],
    ])

    def configString = new groovy.json.JsonOutput().prettyPrint(configJson)
    render contentType: "text/plain", data: configString
}


mappings {
    if (!params.access_token || (params.access_token && params.access_token != state.accessToken)) {
        path("/config")                           { action: [GET: "authError"]  }
        path("/getDevice")                        { action: [GET: "authError"]  }
        path("/sync")                             { action: [POST: "authError"]  }
        path("/getStatus")                        { action: [GET: "authError"]  }
    } else {
        path("/config")                           { action: [GET: "renderConfig"]  }
        path("/getDevice")                        { action: [GET: "deviceList"]  }
        path("/sync")                             { action: [POST: "syncDevice"]  }
        path("/getStatus")                        { action: [GET: "getStatus"]  }
    }
}
