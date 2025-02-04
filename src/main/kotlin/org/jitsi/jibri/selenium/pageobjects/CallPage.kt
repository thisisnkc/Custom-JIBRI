/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

 package org.jitsi.jibri.selenium.pageobjects

 import org.jitsi.utils.logging2.createLogger
 import org.openqa.selenium.TimeoutException
 import org.openqa.selenium.remote.RemoteWebDriver
 import org.openqa.selenium.support.PageFactory
 import org.openqa.selenium.support.ui.WebDriverWait
 import kotlin.time.measureTimedValue
 
 /**
  * Page object representing the in-call page on a jitsi-meet server.
  * NOTE that all the calls here which execute javascript may throw (if, for example, chrome has crashed).  It is
  * intentional that this exceptions are propagated up: the caller should handle those cases.
  */
 class CallPage(driver: RemoteWebDriver) : AbstractPageObject(driver) {
     private val logger = createLogger()
 
     init {
         PageFactory.initElements(driver, this)
     }
 
     override fun visit(url: String): Boolean {
         if (!super.visit(url)) {
             return false
         }
         val (result, totalTime) = measureTimedValue {
             try {
                 WebDriverWait(driver, 30).until {
                     val result = driver.executeScript(
                         """
                         try {
                             return APP.conference._room.isJoined();
                         } catch (e) {
                             return e.message;
                         }
                         """.trimMargin()
                     )
                     when (result) {
                         is Boolean -> result
                         else -> {
                             logger.debug { "Not joined yet: $result" }
                             false
                         }
                     }
                 }
                 true
             } catch (t: TimeoutException) {
                 logger.error("Timed out waiting for call page to load")
                 false
             }
         }
 
         if (result) {
             logger.info("Waited $totalTime to join the conference")
         }
 
         return result
     }
 
     /** Returns the number of participants excluding hidden participants. */
     fun getNumParticipants(): Int {
         val result = driver.executeScript(
             """
             try {
                 return (APP.conference._room.getParticipants().$PARTICIPANT_FILTER_SCRIPT).length + 1;
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         return when (result) {
             is Number -> result.toInt()
             else -> 1
         }
     }
 
     /**
      * Return true if there are no other participants in the conference.
      */
     fun isCallEmpty() = getNumParticipants() <= 1
 
     @Suppress("UNCHECKED_CAST")
     private fun getStats(): Map<String, Any?> {
         val result = driver.executeScript(
             """
             try {
                 return APP.conference.getStats();
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         if (result is String) {
             return mapOf()
         }
         return result as Map<String, Any?>
     }
 
     @Suppress("UNCHECKED_CAST")
     fun getBitrates(): Map<String, Any?> {
         val stats = getStats()
         return stats.getOrDefault("bitrate", mapOf<String, Any?>()) as Map<String, Any?>
     }
 
     fun drawLine(url: String): Boolean {
         logger.info("Initializing drawLine with URL: $url")
     
         val result = driver.executeScript(
             """
             try {
                 console.log('[drawLine] Initializing canvas setup');
     
                 // Ensure socket.io client is included only once
                 if (typeof io === 'undefined') {
                     console.log('[drawLine] Loading socket.io client');
                     const script = document.createElement('script');
                     script.src = 'https://cdn.socket.io/4.0.0/socket.io.min.js';
                     document.head.appendChild(script);
                     script.onload = () => initializeCanvas();
                 } else {
                     initializeCanvas();
                 }
     
                 function initializeCanvas() {
                     console.log('[drawLine] Setting up canvas and socket');
     
                     // Check if a canvas already exists to prevent duplicates
                     if (document.getElementById('customCanvas')) {
                         console.log('[drawLine] Canvas already exists, skipping creation');
                         return;
                     }
     
                     const canvas = document.createElement('canvas');
                     canvas.id = 'customCanvas';
                     canvas.width = window.innerWidth;
                     canvas.height = window.innerHeight;
                     canvas.style.position = 'absolute';
                     canvas.style.top = '0';
                     canvas.style.left = '0';
                     canvas.style.zIndex = '1000';
                     document.body.appendChild(canvas);
     
                     const ctx = canvas.getContext('2d');
                     let color = 'white';
     
                     const link = window.location.href;
                     console.log('[drawLine] Current URL:', link);
     
                     const meetingId = link.split('/')[3];
                     console.log('[drawLine] Extracted Meeting ID:', meetingId);
     
                     // Avoid multiple socket connections
                     if (window.activeSocket) {
                         console.log('[drawLine] Existing socket found, disconnecting old instance');
                         window.activeSocket.disconnect();
                     }
     
                     const socket = io('https://b1c1-112-196-5-34.ngrok-free.app', {
                         transports: ['websocket'],
                         query: { roomID: meetingId }
                     });
     
                     window.activeSocket = socket; // Store socket globally for cleanup
     
                     socket.on('connect', () => {
                         console.log('[drawLine] Connected to server, Socket ID:', socket.id);
                         socket.emit('client-ready');
                     });
     
                     socket.on('disconnect', (reason) => {
                         console.log('[drawLine] Disconnected from server, Reason:', reason);
                     });
     
                     
                     socket.on('get-canvas-state', () => {
                         console.log('[drawLine] Sending canvas state, Socket ID:', socket.id);
                         socket.emit('canvas-state', canvas.toDataURL());
                     });
     
                     socket.on('canvas-state-from-server', (state) => {
                         console.log('[drawLine] Received canvas state from server, Socket ID:', socket.id);
                         const img = new Image();
                         img.src = state;
                         img.onload = () => {
                             ctx.drawImage(img, 0, 0);
                         };
                     });
     
                     socket.on('draw-line', ({ prevPoint, currentPoint, color: receivedColor }) => {
                         try {
                             console.log('[drawLine] Received draw-line event');
                             if (prevPoint?.x && prevPoint?.y && currentPoint?.x && currentPoint?.y) {
                                 const scaledPrevPoint = {
                                     x: (prevPoint.x / 100) * window.innerWidth,
                                     y: (prevPoint.y / 100) * window.innerHeight
                                 };
     
                                 const scaledCurrentPoint = {
                                     x: (currentPoint.x / 100) * window.innerWidth,
                                     y: (currentPoint.y / 100) * window.innerHeight
                                 };
     
                                 const lineColor = receivedColor || color;
                                 drawline(scaledPrevPoint, scaledCurrentPoint, ctx, lineColor);
                             }
                         } catch (error) {
                             console.error('[drawLine] Error processing draw-line event:', error);
                         }
                     });
     
                     socket.on('clear', () => {
                         console.log('[drawLine] Clearing canvas');
                         ctx.clearRect(0, 0, canvas.width, canvas.height);
                     });
     
                     const sendDimensions = () => {
                         const userDimensions = { width: window.innerWidth, height: window.innerHeight };
                         console.log('[drawLine] Sending window dimensions:', userDimensions);
                         socket.emit('browser-dimensions', userDimensions);
                     };
                     sendDimensions();
     
                     window.addEventListener('resize', sendDimensions);
     
                     function drawline(prevPoint, currentPoint, ctx, color) {
                         try {
                             console.log('[drawLine] Drawing line:', prevPoint, currentPoint, color);
                             ctx.beginPath();
                             ctx.lineWidth = 5;
                             ctx.moveTo(prevPoint.x, prevPoint.y);
                             ctx.lineTo(currentPoint.x, currentPoint.y);
                             ctx.strokeStyle = color;
                             ctx.stroke();
     
                             ctx.fillStyle = color;
                             ctx.beginPath();
                             ctx.arc(prevPoint.x, prevPoint.y, 2, 0, 2 * Math.PI);
                             ctx.fill();
                         } catch (error) {
                             console.error('[drawLine] Error in drawline function:', error);
                         }
                     }
     
                     window.addEventListener('beforeunload', () => {
                         console.log('[drawLine] Cleaning up before page unload');
                         if (window.activeSocket) {
                             window.activeSocket.disconnect();
                             window.activeSocket = null;
                         }
                         window.removeEventListener('resize', sendDimensions);
                     });
     
                     console.log('[drawLine] Canvas and socket setup complete');
                 }
     
                 return true;
             } catch (e) {
                 console.error('[drawLine] Error initializing drawLine:', e);
                 return e.message;
             }
             """.trimMargin()
         )
     
         return result is Boolean && result
     }
     
     
     
 
     fun injectParticipantTrackerScript(): Boolean {
         val result = driver.executeScript(
             """
             try {
                 window._jibriParticipants = new Map();
                 const existingMembers = APP.conference._room.room.members || {};
                 const existingMemberJids = Object.keys(existingMembers);
                 console.log("There were " + existingMemberJids.length + " existing members");
                 existingMemberJids.forEach(jid => {
                     const existingMember = existingMembers[jid];
                     const nick = existingMember.nick;
                     if (existingMember.identity) {
                         console.log("Member ", existingMember, " has identity, adding");
                         if (nick && nick.length > 0 && existingMember.identity.user) {
                             existingMember.identity.user.name = nick;
                         }
                         window._jibriParticipants.set(jid, existingMember.identity);
                     } else {
                         console.log("Member ", existingMember.jid, " has no identity, skipping");
                     }
                 });
                 APP.conference._room.room.addListener(
                     "xmpp.muc_member_joined",
                     (from, nick, role, hidden, statsid, status, identity) => {
                         console.log("Jibri got MUC_MEMBER_JOINED: ", from, identity);
                         if (!hidden && identity) {
                             if (nick && nick.length > 0 && identity.user) {
                                 identity.user.name = nick;
                             }
                             window._jibriParticipants.set(from, identity);
                         }
                     }
                 );
                 APP.conference._room.room.addListener(
                     "xmpp.display_name_changed",
                     (jid, displayName) => {
                         const identity = window._jibriParticipants.get(jid);
                         if (displayName && displayName.length > 0 && identity && identity.user) {
                             identity.user.name = displayName;
                         }
                     }
                 );
 
                 return true;
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         return when (result) {
             is Boolean -> result
             else -> false
         }
     }
 
     fun injectLocalParticipantTrackerScript(): Boolean {
         val result = driver.executeScript(
             """
             try {
                 window._isLocalParticipantKicked=false
                 
                 APP.conference._room.room.addListener(
                     "xmpp.kicked",
                     (isSelfPresence, actorId, kickedParticipantId, reason) => {
                         console.log("Jibri got a KICKED event: ", isSelfPresence, actorId, kickedParticipantId, reason);
                         if (isSelfPresence) {
                             window._isLocalParticipantKicked=true
                         }
                     }
                 );
                 
                 return true;
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         return when (result) {
             is Boolean -> result
             else -> false
         }
     }
 
     fun getParticipants(): List<Map<String, Any>> {
         val result = driver.executeScript(
             """
             try {
                 return window._jibriParticipants.values().toArray();
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         if (result is List<*>) {
             @Suppress("UNCHECKED_CAST")
             return result as List<Map<String, Any>>
         } else {
             return listOf()
         }
     }
 
     /**
      * Return how many of the participants are Jigasi clients.
      * Note: excludes any participants that are hidden (for example transcribers)
      */
     fun numRemoteParticipantsJigasi(): Int {
         val result = driver.executeScript(
             """
             try {
                 return APP.conference._room.getParticipants()
                     .$PARTICIPANT_FILTER_SCRIPT
                     .filter(participant => participant.getProperty("features_jigasi") == true)
                     .length;
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         return when (result) {
             is Number -> result.toInt()
             else -> {
                 logger.error("error running numRemoteParticipantsJigasi script: $result ${result::class.java}")
                 0
             }
         }
     }
 
     /** How many of the participants are hidden or hiddenFromRecorder. */
     fun numHiddenParticipants(): Int {
         val result = driver.executeScript(
             """
             try {
                 return APP.conference._room.getParticipants()
                     .filter(p => (p.isHidden() || p.isHiddenFromRecorder()))
                     .length;
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         return when (result) {
             is Number -> result.toInt()
             else -> {
                 logger.error("error running numHiddenParticipants script: $result ${result::class.java}")
                 0
             }
         }
     }
 
     /**
      * Return true if ICE is connected.
      */
     fun isIceConnected(): Boolean {
         val result: Any? = driver.executeScript(
             """
             try {
                 return APP.conference.getConnectionState();
             } catch(e) {
                 return e.message;
             }
         """
         )
         return (result.toString().lowercase() == "connected").also {
             if (!it) {
                 logger.warn("ICE not connected: $result")
             }
         }
     }
 
     fun isLocalParticipantKicked(): Boolean {
         val result = driver.executeScript(
             """
             try {
                 return window._isLocalParticipantKicked;
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         if (result is Boolean) {
             return result
         } else {
             return false
         }
     }
 
     /**
      * Returns a count of how many remote participants are totally muted (audio
      * and video). We ignore jigasi participants as they maybe muted in their presence
      * but also hard muted via the device, and we later ignore their state.
      * Note: Excludes hidden participants.
      */
     fun numRemoteParticipantsMuted(): Int {
         val result = driver.executeScript(
             """
             try {
                 return APP.conference._room.getParticipants()
                     .$PARTICIPANT_FILTER_SCRIPT
                     .filter(participant => participant.isAudioMuted() && participant.isVideoMuted() 
                                 && participant.getProperty("features_jigasi") !== true)
                     .length;
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         return when (result) {
             is Number -> result.toInt()
             else -> {
                 logger.error("error running numRemoteParticipantsMuted script: $result ${result::class.java}")
                 0
             }
         }
     }
 
     /**
      * Add the given key, value pair to the presence map and send a new presence
      * message
      */
     fun addToPresence(key: String, value: String): Boolean {
         val result = driver.executeScript(
             """
             try {
                 APP.conference._room.room.addToPresence(
                     '$key',
                     {
                         value: '$value'
                     }
                 );
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         return when (result) {
             is String -> false
             else -> true
         }
     }
 
     fun sendPresence(): Boolean {
         val result = driver.executeScript(
             """
             try {
                 APP.conference._room.room.sendPresence();
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
         return when (result) {
             is String -> false
             else -> true
         }
     }
 
     fun leave(): Boolean {
         val result = driver.executeScript(
             """
             try {
                 return APP.conference._room.leave();
             } catch (e) {
                 return e.message;
             }
             """.trimMargin()
         )
 
         // Let's wait till we are alone in the room
         // (give time for the js Promise to finish before quitting selenium)
         WebDriverWait(driver, 2).until {
             getNumParticipants() == 1
         }
 
         return when (result) {
             is String -> false
             else -> true
         }
     }
 
     companion object {
         /**
          * Javascript to apply a filter to the list of participants to exclude ones which should be hidden from jibri.
          */
         const val PARTICIPANT_FILTER_SCRIPT = "filter(p => !(p.isHidden() || p.isHiddenFromRecorder()))"
     }
 }
 