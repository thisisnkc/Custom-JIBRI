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
import org.openqa.selenium.logging.LogType
import org.openqa.selenium.logging.LoggingPreferences
import org.openqa.selenium.remote.CapabilityType
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.support.PageFactory
import org.openqa.selenium.support.ui.WebDriverWait
import java.util.logging.Level
import kotlin.time.measureTimedValue

// Participant filter script to exclude hidden participants
private const val PARTICIPANT_FILTER_SCRIPT = "filter(p => !p.isHidden() && !p.isHiddenFromRecorder())"
 
 /**
  * Page object representing the in-call page on a jitsi-meet server.
  * NOTE that all the calls here which execute javascript may throw (if, for example, chrome has crashed).  It is
  * intentional that this exceptions are propagated up: the caller should handle those cases.
  */
 class CallPage(driver: RemoteWebDriver) : AbstractPageObject(driver) {
     private val logger = createLogger()
    
     private val CONSOLE_LOGGER_SCRIPT = """
     (() => {
         /*************************************************
          * 1. Preserve original console methods
          *************************************************/
         window._originalConsole = window._originalConsole || {
             log: console.log,
             error: console.error,
             warn: console.warn,
             debug: console.debug,
             info: console.info
         };
     
         /*************************************************
          * 2. Initialize our custom log storage
          *************************************************/
         window._browserLogs = window._browserLogs || [];
     
         /*************************************************
          * 3. Safe-stringify helper
          *************************************************/
         function safeStringify(value) {
             try {
                 if (value === null) return "null";
                 if (value === undefined) return "undefined";
     
                 const t = typeof value;
     
                 if (t === "string") return value;  // plain string, not JSON.stringify
                 if (t === "number" || t === "boolean") return String(value);
     
                 // Handle Errors
                 if (value instanceof Error) {
                     return value.name + ": " + value.message + "\\n" + value.stack;
                 }
     
                 // Handle DOM elements safely
                 if (value instanceof Element) {
                     return "<Element " + value.tagName + ">";
                 }
     
                 // Handle plain objects/arrays
                 return JSON.stringify(value);
             } catch (e) {
                 return "[unstringifiable]";
             }
         }
     
         /*************************************************
          * 4. Override console methods
          *************************************************/
         ['log', 'error', 'warn', 'debug', 'info'].forEach(method => {
             console[method] = function(...args) {
                 try {
                     // Convert all arguments to clean strings
                     const msg = args.map(a => safeStringify(a)).join(" ");
     
                     window._browserLogs.push({
                         level: method.toUpperCase(),
                         timestamp: new Date().toISOString(),
                         message: msg
                     });
                 } catch (e) {
                     // fallback if logging fails
                     window._browserLogs.push({
                         level: "ERROR",
                         timestamp: new Date().toISOString(),
                         message: "[console wrapper failure] " + String(e)
                     });
                 }
     
                 // Call original console method
                 if (window._originalConsole[method]) {
                     window._originalConsole[method].apply(console, args);
                 }
             };
         });
     
         /*************************************************
          * 5. Uncaught exception handler
          *************************************************/
         window.onerror = function(message, source, lineno, colno, error) {
             const errMsg = safeStringify({
                 message,
                 source,
                 line: lineno,
                 column: colno,
                 stack: error ? error.stack : "No stack"
             });
     
             console.error("[Uncaught Error]", errMsg);
         };
     
         /*************************************************
          * 6. Unhandled Promise rejection handler
          *************************************************/
         window.onunhandledrejection = function(event) {
             const reason = safeStringify(event.reason);
             console.error("[Unhandled Promise Rejection]", reason);
         };
     
         /*************************************************
          * 7. Initialization marker
          *************************************************/
         console.log("[EnhancedConsole] Initialized");
     
         return true;
     })();
     """.trimIndent()
     
 
     init {
        PageFactory.initElements(driver, this)
        initializeEnhancedLogging()
    }
    
    /**
     * Initialize enhanced logging in the browser
     */
    private fun initializeEnhancedLogging() {
        try {
            driver.executeScript(CONSOLE_LOGGER_SCRIPT)
            logger.info("Enhanced browser console logging initialized")
        } catch (e: Exception) {
            logger.error("Failed to initialize enhanced logging: ${e.message}")
        }
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
        
        // Clear any existing logs
        try {
            driver.manage().logs().get(LogType.BROWSER)
            driver.executeScript("if (window._browserLogs) window._browserLogs = [];")
        } catch (e: Exception) {
            logger.warn("Failed to clear logs: ${e.message}")
        }
    
        val result =
        
        driver.executeScript(
            """
    // Prevent duplicate injections
    if (!window.__drawLineInjected) {
        window.__drawLineInjected = true;

        const script = document.createElement('script');
        script.type = 'text/javascript';
        script.id = 'drawline-injector';

        script.textContent = `
            /*************************************************
             * REDIRECT ALL CONSOLE OUTPUT TO window._browserLogs
             *************************************************/
            (function(){
                if (!window._browserLogs) window._browserLogs = [];
                const levels = ['log','info','warn','error','debug'];
                levels.forEach(level => {
                    const original = console[level];
                    console[level] = function(...args) {
                        try {
                            window._browserLogs.push({
                                level: level.toUpperCase(),
                                message: args.map(a => {
                                    try { return JSON.stringify(a); }
                                    catch(e) { return String(a); }
                                }).join(' '),
                                timestamp: new Date().toISOString()
                            });
                        } catch(e){}

                        original.apply(console, args);
                    };
                });


                console.log('[drawLineInjector] <><> Console capture initialized');
            })();


            /*************************************************
             * LOAD SOCKET.IO THEN RUN drawLine() 
             *************************************************/
            (function(){
                console.log('[drawLineInjector] Starting injection');

                function loadSocketIo(callback) {
                    if (typeof io !== 'undefined') {
                        console.log('[drawLineInjector] socket.io already loaded');
                        return callback();
                    }

                    console.log('[drawLineInjector] Loading socket.io...');
                    const s = document.createElement('script');
                    s.src = 'https://cdn.socket.io/4.0.0/socket.io.min.js';
                    s.onload = () => {
                        console.log('[drawLineInjector] <> <> socket.io loaded');
                        callback();
                    };
                    s.onerror = () => console.error('[drawLineInjector] Failed to load socket.io');
                    document.head.appendChild(s);
                }


                /*************************************************
                 * MAIN DRAW LINE FUNCTION
                 *************************************************/
                function drawLineInit() {
                    console.log('[drawLineInjector] <> <> drawLineInit invoked');

                    // Avoid double canvas creation
                    if (document.getElementById('customCanvas')) {
                        console.log('[drawLineInjector] Canvas already exists — skipping');
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

                    console.log('[drawLineInjector] <> <> Canvas created:', canvas.width, canvas.height);

                    const ctx = canvas.getContext('2d');

                    const link = window.location.href;
                    console.log('[drawLineInjector] <> <> <> Current URL:', link);

                    const meetingId = link.split('/')[3];
                    console.log('[drawLineInjector] <> <> <> Extracted Meeting ID:', meetingId);

                    // Avoid duplicate sockets
                    if (window.activeSocket) {
                        console.log('[drawLineInjector] Disconnecting old socket');
                        window.activeSocket.disconnect();
                    }

                    const socket = window.activeSocket = io('https://uat-samsung.maxicus.com', {
                        transports: ['websocket'],
                        query: { roomID: meetingId }
                    });

                    socket.on('connect', () => {
                        console.log('[drawLineInjector] Socket connected:', socket.id);
                        socket.emit('client-ready');
                    });

                    socket.on('disconnect', reason => {
                        console.log('[drawLineInjector] Socket disconnected:', reason);
                    });

                    socket.on('get-canvas-state', () => {
                        console.log('[drawLineInjector] get-canvas-state received');
                        socket.emit('canvas-state', canvas.toDataURL());
                    });

                    socket.on('canvas-state-from-server', state => {
                        console.log('[drawLineInjector] canvas-state-from-server received');
                        const img = new Image();
                        img.onload = () => ctx.drawImage(img, 0, 0);
                        img.src = state;
                    });

                    socket.on('draw-line', ({prevPoint, currentPoint, color}) => {
                        console.log('[drawLineInjector] draw-line event:', prevPoint, currentPoint);

                        if (!prevPoint || !currentPoint) return;

                        ctx.beginPath();
                        ctx.lineWidth = 5;
                        ctx.strokeStyle = color || 'white';
                        ctx.moveTo(prevPoint.x / 100 * window.innerWidth, prevPoint.y / 100 * window.innerHeight);
                        ctx.lineTo(currentPoint.x / 100 * window.innerWidth, currentPoint.y / 100 * window.innerHeight);
                        ctx.stroke();
                    });

                    socket.on('clear', () => {
                        console.log('[drawLineInjector] Clear event received');
                        ctx.clearRect(0, 0, canvas.width, canvas.height);
                    });

                    function sendDims() {
                        const dims = { width: window.innerWidth, height: window.innerHeight };
                        console.log('[drawLineInjector] Sending dimensions:', dims);
                        socket.emit('browser-dimensions', dims);
                    }

                    sendDims();
                    window.addEventListener('resize', sendDims);

                    console.log('[drawLineInjector] drawLine setup complete');
                }


                /*************************************************
                 * EXECUTE EVERYTHING
                 *************************************************/
                loadSocketIo(drawLineInit);
            })();
        `;

        document.head.appendChild(script);
        console.log("drawLineInjector script attached to DOM");
    }
""".trimMargin()
         )
     
         // Add a small delay to allow logs to be captured
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        // Capture and log browser console output
        logBrowserConsoleLogs()

         logger.info("drawLine JS result <><><><<><><><<><><><><><: $result")
        return result is Boolean && result

     }
     
     
     /**
     * Fetches browser console logs using Selenium and logs them to Jibri logs.
     */
    private fun logBrowserConsoleLogs() {
        try {
            // Get logs from Selenium's log interface
            val logTypes = driver.manage().logs().availableLogTypes
            logger.debug("Available log types: $logTypes")
            
            // Get browser console logs
            val logs = driver.manage().logs().get(LogType.BROWSER)
            if (logs != null && logs.all.isNotEmpty()) {
                logs.forEach { entry ->
                    val message = "[Browser ${entry.timestamp}] ${entry.level}: ${entry.message}"
                    when (entry.level) {
                        Level.SEVERE -> logger.error(message)
                        Level.WARNING -> logger.warn(message)
                        Level.INFO -> logger.info(message)
                        Level.FINE, Level.FINER, Level.FINEST -> logger.debug(message)
                        else -> logger.trace(message)
                    }
                }
            } else {
                logger.debug("No browser console logs available via Selenium log interface")
            }
            
            // Get logs from our enhanced console logger
            try {
                @Suppress("UNCHECKED_CAST")
                val consoleLogs = driver.executeScript("""
                    return window._browserLogs || [];
                """.trimIndent()) as? List<Map<String, Any?>>
                
                consoleLogs?.forEach { log ->
                    val level = log["level"]?.toString()?.uppercase() ?: "UNKNOWN"
                    val message = log["message"]?.toString() ?: ""
                    val timestamp = log["timestamp"]?.toString() ?: ""
                    
                    when (level.uppercase()) {
                        "ERROR" -> logger.error("[EnhancedConsole $timestamp] $message")
                        "WARN" -> logger.warn("[EnhancedConsole $timestamp] $message")
                        "INFO" -> logger.info("[EnhancedConsole $timestamp] $message")
                        "DEBUG" -> logger.debug("[EnhancedConsole $timestamp] $message")
                        else -> logger.trace("[EnhancedConsole $timestamp] $message")
                    }
                }
                
                // Clear logs after reading to avoid duplicates
                driver.executeScript("window._browserLogs = [];")
                
            } catch (e: Exception) {
                logger.warn("Failed to get enhanced console logs: ${e.message}")
            }
            
        } catch (e: Exception) {
            logger.error("Error in logBrowserConsoleLogs: ${e.message}", e)
        }
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
 