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
 class CallPage(driver: RemoteWebDriver, private val appWebSocketUrl: String = "") : AbstractPageObject(driver) {
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
 
     /**
     * Initializes a collaborative drawing canvas with WebSocket synchronization.
     * This function creates a canvas overlay that allows for real-time drawing that's synchronized
     * across all participants via WebSocket.
     *
     * @param url The WebSocket server URL to connect to for synchronization
     * @return true if initialization was successful, false otherwise
     */
    fun drawLine(url: String): Boolean {
        logger.info("Initializing drawLine with WebSocket URL: $url")
    
        val result = driver.executeScript(
            """
            (function() {
                // Configuration
                const CONFIG = {
                    CANVAS_ID: 'jibri-canvas-overlay',
                    LINE_WIDTH: 5,
                    POINT_RADIUS: 2,
                    MAX_RECONNECT_ATTEMPTS: 5,
                    RECONNECT_DELAY: 3000,
                    DEBOUNCE_DELAY: 100
                };

                // State management
                const state = {
                    socket: null,
                    canvas: null,
                    ctx: null,
                    isDrawing: false,
                    lastPoint: null,
                    reconnectAttempts: 0,
                    resizeTimeout: null,
                    eventListeners: []
                };

                // Error handling
                function handleError(error, context = '') {
                    console.error(`[drawLine] Error${context ? ' in ' + context : ''}:`, error);
                    return error.message || String(error);
                }

                // Cleanup resources
                function cleanup() {
                    // Remove all event listeners
                    state.eventListeners.forEach(({ element, event, handler }) => {
                        try {
                            element.removeEventListener(event, handler);
                        } catch (e) {
                            console.warn('Error removing event listener:', e);
                        }
                    });
                    state.eventListeners = [];

                    // Disconnect socket
                    if (state.socket?.connected) {
                        state.socket.disconnect();
                        state.socket = null;
                    }

                    // Remove canvas
                    if (state.canvas && state.canvas.parentNode) {
                        state.canvas.parentNode.removeChild(state.canvas);
                    }

                    // Clear state
                    state.canvas = null;
                    state.ctx = null;
                }

                // Add event listener with cleanup tracking
                function addEventListener(element, event, handler) {
                    element.addEventListener(event, handler);
                    state.eventListeners.push({ element, event, handler });
                    return () => element.removeEventListener(event, handler);
                }

                // Get meeting ID from URL
                function getMeetingId() {
                    try {
                        const url = new URL(window.location.href);
                        const pathParts = url.pathname.split('/').filter(Boolean);
                        if (pathParts.length > 0) {
                            return pathParts[0]; // First non-empty path segment
                        }
                        throw new Error('Could not extract meeting ID from URL');
                    } catch (error) {
                        console.error('[drawLine] Error extracting meeting ID:', error);
                        return 'unknown-room';
                    }
                }

                // Initialize WebSocket connection
                function initSocket() {
                    if (state.socket) {
                        state.socket.disconnect();
                    }

                    if (!'$appWebSocketUrl') {
                        throw new Error('WebSocket URL is required');
                    }

                    // Load socket.io client if not already loaded
                    if (typeof io === 'undefined') {
                        console.log('[drawLine] Loading socket.io client');
                        return new Promise((resolve, reject) => {
                            const script = document.createElement('script');
                            script.src = 'https://cdn.socket.io/4.0.0/socket.io.min.js';
                            script.onload = () => {
                                console.log('[drawLine] Socket.io client loaded');
                                setupSocket();
                                resolve();
                            };
                            script.onerror = (error) => {
                                reject(new Error('Failed to load socket.io client'));
                            };
                            document.head.appendChild(script);
                        });
                    } else {
                        setupSocket();
                        return Promise.resolve();
                    }
                }

                function setupSocket() {
                    state.socket = io('$appWebSocketUrl', {
                        transports: ['websocket'],
                        reconnectionAttempts: CONFIG.MAX_RECONNECT_ATTEMPTS,
                        reconnectionDelay: CONFIG.RECONNECT_DELAY,
                        query: { roomID: getMeetingId() }
                    });

                    // Socket event handlers
                    state.socket
                        .on('connect', onSocketConnect)
                        .on('disconnect', onSocketDisconnect)
                        .on('connect_error', onSocketError)
                        .on('reconnect_attempt', onReconnectAttempt)
                        .on('reconnect_failed', onReconnectFailed)
                        .on('get-canvas-state', onGetCanvasState)
                        .on('canvas-state-from-server', onCanvasStateFromServer)
                        .on('draw-line', onDrawLine)
                        .on('clear', onClear);
                }

                // Socket event handlers
                function onSocketConnect() {
                    console.log('[drawLine] Connected to server, Socket ID:', state.socket.id);
                    state.reconnectAttempts = 0;
                    state.socket.emit('client-ready');
                }

                function onSocketDisconnect(reason) {
                    console.log('[drawLine] Disconnected from server, Reason:', reason);
                    if (reason === 'io server disconnect') {
                        // The server intentionally disconnected the socket, don't try to reconnect
                        cleanup();
                    }
                }

                function onSocketError(error) {
                    console.error('[drawLine] Socket connection error:', error);
                }

                function onReconnectAttempt(attemptNumber) {
                    console.log(`[drawLine] Reconnection attempt ${attemptNumber}/${CONFIG.MAX_RECONNECT_ATTEMPTS}`);
                    state.reconnectAttempts = attemptNumber;
                }

                function onReconnectFailed() {
                    console.error('[drawLine] Failed to reconnect after maximum attempts');
                    cleanup();
                }

                function onGetCanvasState() {
                    if (!state.canvas) return;
                    console.log('[drawLine] Sending canvas state');
                    state.socket.emit('canvas-state', state.canvas.toDataURL());
                }

                function onCanvasStateFromServer(stateData) {
                    if (!state.canvas || !state.ctx) return;
                    
                    console.log('[drawLine] Received canvas state from server');
                    const img = new Image();
                    img.onload = () => {
                        state.ctx.drawImage(img, 0, 0);
                    };
                    img.onerror = (e) => {
                        console.error('[drawLine] Error loading canvas state image:', e);
                    };
                    img.src = stateData;
                }

                function onDrawLine({ prevPoint, currentPoint, color: receivedColor }) {
                    try {
                        if (!state.ctx || !prevPoint?.x || !prevPoint?.y || !currentPoint?.x || !currentPoint?.y) {
                            return;
                        }

                        const scaledPrevPoint = scalePoint(prevPoint);
                        const scaledCurrentPoint = scalePoint(currentPoint);
                        drawLineSegment(scaledPrevPoint, scaledCurrentPoint, receivedColor || 'white');
                    } catch (error) {
                        handleError(error, 'draw-line handler');
                    }
                }

                function onClear() {
                    if (state.ctx && state.canvas) {
                        console.log('[drawLine] Clearing canvas');
                        state.ctx.clearRect(0, 0, state.canvas.width, state.canvas.height);
                    }
                }

                // Drawing utilities
                function scalePoint({ x, y }) {
                    return {
                        x: (x / 100) * window.innerWidth,
                        y: (y / 100) * window.innerHeight
                    };
                }

                function drawLineSegment(start, end, color) {
                    if (!state.ctx) return;

                    state.ctx.beginPath();
                    state.ctx.lineWidth = CONFIG.LINE_WIDTH;
                    state.ctx.strokeStyle = color;
                    state.ctx.moveTo(start.x, start.y);
                    state.ctx.lineTo(end.x, end.y);
                    state.ctx.stroke();

                    // Draw points at the ends
                    drawPoint(start, color);
                    drawPoint(end, color);
                }

                function drawPoint(point, color) {
                    if (!state.ctx) return;
                    
                    state.ctx.fillStyle = color;
                    state.ctx.beginPath();
                    state.ctx.arc(point.x, point.y, CONFIG.POINT_RADIUS, 0, 2 * Math.PI);
                    state.ctx.fill();
                }

                // Handle window resize with debounce
                function handleResize() {
                    if (state.resizeTimeout) {
                        clearTimeout(state.resizeTimeout);
                    }
                    
                    state.resizeTimeout = setTimeout(() => {
                        if (state.canvas) {
                            state.canvas.width = window.innerWidth;
                            state.canvas.height = window.innerHeight;
                            state.socket?.emit('browser-dimensions', { 
                                width: window.innerWidth, 
                                height: window.innerHeight 
                            });
                        }
                    }, CONFIG.DEBOUNCE_DELAY);
                }

                // Mouse/touch event handlers
                function handleMouseDown(e) {
                    if (!state.ctx) return;
                    
                    const point = getEventPoint(e);
                    state.isDrawing = true;
                    state.lastPoint = point;
                    e.preventDefault();
                }

                function handleMouseMove(e) {
                    if (!state.isDrawing || !state.ctx || !state.lastPoint) return;
                    
                    const point = getEventPoint(e);
                    const color = 'white'; // Default color, can be made configurable
                    
                    // Draw locally
                    drawLineSegment(state.lastPoint, point, color);
                    
                    // Broadcast to other clients
                    if (state.socket?.connected) {
                        state.socket.emit('draw-line', {
                            prevPoint: {
                                x: (state.lastPoint.x / window.innerWidth) * 100,
                                y: (state.lastPoint.y / window.innerHeight) * 100
                            },
                            currentPoint: {
                                x: (point.x / window.innerWidth) * 100,
                                y: (point.y / window.innerHeight) * 100
                            },
                            color: color
                        });
                    }
                    
                    state.lastPoint = point;
                    e.preventDefault();
                }

                function handleMouseUp() {
                    state.isDrawing = false;
                    state.lastPoint = null;
                }

                function getEventPoint(e) {
                    const rect = state.canvas.getBoundingClientRect();
                    const clientX = e.clientX || (e.touches?.[0]?.clientX);
                    const clientY = e.clientY || (e.touches?.[0]?.clientY);
                    
                    return {
                        x: clientX - rect.left,
                        y: clientY - rect.top
                    };
                }

                // Main initialization
                try {
                    // Check if already initialized
                    if (document.getElementById(CONFIG.CANVAS_ID)) {
                        console.log('[drawLine] Canvas already initialized');
                        return true;
                    }

                    // Create canvas
                    state.canvas = document.createElement('canvas');
                    state.canvas.id = CONFIG.CANVAS_ID;
                    state.canvas.style.position = 'fixed';
                    state.canvas.style.top = '0';
                    state.canvas.style.left = '0';
                    state.canvas.style.zIndex = '1000';
                    state.canvas.style.pointerEvents = 'auto'; // Make sure it can receive events
                    document.body.appendChild(state.canvas);

                    // Set initial size
                    state.canvas.width = window.innerWidth;
                    state.canvas.height = window.innerHeight;

                    // Get 2D context
                    state.ctx = state.canvas.getContext('2d');
                    if (!state.ctx) {
                        throw new Error('Could not get 2D rendering context');
                    }

                    // Set up drawing style
                    state.ctx.lineCap = 'round';
                    state.ctx.lineJoin = 'round';

                    // Initialize socket connection
                    return initSocket().then(() => {
                        // Set up event listeners
                        const events = [
                            { element: window, event: 'resize', handler: handleResize },
                            { element: window, event: 'beforeunload', handler: cleanup },
                            { element: state.canvas, event: 'mousedown', handler: handleMouseDown },
                            { element: state.canvas, event: 'mousemove', handler: handleMouseMove },
                            { element: window, event: 'mouseup', handler: handleMouseUp },
                            { element: state.canvas, event: 'touchstart', handler: handleMouseDown },
                            { element: state.canvas, event: 'touchmove', handler: handleMouseMove },
                            { element: window, event: 'touchend', handler: handleMouseUp }
                        ];

                        events.forEach(({ element, event, handler }) => {
                            addEventListener(element, event, handler);
                        });

                        // Initial dimensions
                        state.socket?.emit('browser-dimensions', {
                            width: window.innerWidth,
                            height: window.innerHeight
                        });

                        console.log('[drawLine] Initialized successfully');
                        return true;
                    }).catch(error => {
                        console.error('[drawLine] Error initializing socket:', error);
                        cleanup();
                        return false;
                    });
                } catch (error) {
                    console.error('[drawLine] Initialization error:', error);
                    cleanup();
                    return false;
                }
            })();
            """.trimIndent()
        )
    
        return result == true
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
 