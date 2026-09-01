# CampusMesh — Phased Development Prompt

You are the senior Android engineer responsible for building a decentralized offline messaging application called **CampusMesh**.

CampusMesh is intended primarily for university/campus environments where users should be able to send messages without internet, mobile data, or a central messaging server.

The network should use nearby Android phones as peer-to-peer relay nodes.

A message should eventually be capable of travelling like:

Phone A → Phone B → Phone C → Phone D

even when Phone A and Phone D are never directly within Bluetooth range of each other.

The system should use an opportunistic **store-and-forward architecture**, meaning intermediate devices may temporarily store encrypted packets and forward them when suitable peers become available.

The final application may eventually support:

* Offline one-to-one messaging
* Multi-hop message forwarding
* End-to-end encryption
* Profiles
* Profile pictures
* Local chat history
* Delivery states
* Groups
* Images and files
* Campus announcement channels
* Emergency broadcasts
* Routing optimizations
* Offline-first operation

However:

## CRITICAL DEVELOPMENT RULE

DO NOT BUILD THE ENTIRE APPLICATION AT ONCE.

The project must be implemented strictly in phases.

At the end of every phase:

1. Stop development.
2. Verify the phase actually works.
3. Run relevant tests.
4. Explain what was implemented.
5. Explain problems discovered.
6. Explain architectural decisions made.
7. Explain any assumptions.
8. Explain what should happen in the next phase.
9. Wait for explicit approval before starting the next phase.

Do not begin future-phase functionality early unless it is absolutely necessary for the architecture of the current phase.

Avoid overengineering.

Do not create placeholder implementations pretending functionality works.

If something cannot work because of Android limitations, BLE limitations, permissions, hardware behavior, OS restrictions, or architectural conflicts, state this clearly instead of hiding it.

You are a coding agent and senior engineer.

Think through implementation details yourself.

Do not blindly follow architecture suggestions if a better technical approach exists.

If you find a better architecture than what is described here, explain:

* what you would change
* why
* advantages
* disadvantages
* compatibility implications

before implementing the change.

---

# TARGET TECHNOLOGY

Primary platform:

Android

Preferred language:

Kotlin

Preferred UI:

Jetpack Compose

Recommended architecture:

MVVM or another clean Android architecture you believe better suits the project.

Likely technologies include:

* Kotlin
* Jetpack Compose
* Android Bluetooth APIs
* BLE
* Room
* SQLite
* Kotlin Coroutines
* Flow / StateFlow
* Android Keystore
* WorkManager where appropriate
* Foreground Services where required
* Kotlin Serialization or Protocol Buffers
* Hilt or another justified DI solution
* Coil for local images

These are recommendations rather than rigid requirements.

Choose libraries carefully.

Avoid adding dependencies simply because they exist.

Use Android's native capabilities when appropriate.

---

# CORE ARCHITECTURAL PRINCIPLE

There are two fundamentally different categories of stored information.

## User-owned data

Examples:

* conversations
* chat history
* contacts
* user profile
* user preferences
* profile pictures
* received images
* message status

## Mesh relay data

Examples:

* encrypted packets temporarily being carried
* packet IDs
* destination identifiers
* TTL
* hop count
* expiry time
* routing metadata
* peer discovery information

These should remain logically separated.

A relay device should not need to decrypt the private content of messages it carries.

---

# DATABASE STRATEGY

Use a local database such as Room backed by SQLite.

Possible logical entities include:

Users

* userId
* username/display name
* publicKey
* profilePhotoPath
* createdAt
* lastSeen

Conversations

* conversationId
* participant information
* lastMessage
* updatedAt

Messages

* messageId
* conversationId
* senderId
* recipientId
* encrypted or decrypted local representation as appropriate
* timestamp
* status
* messageType

RelayPackets

* packetId
* messageId
* senderId
* destinationId
* encryptedPayload
* ttl
* hopCount
* createdAt
* expiresAt
* forwarding status

SeenPackets / packet deduplication may either be a separate structure or part of RelayPackets depending on your architecture.

Do NOT store large images or videos directly inside SQLite unless there is a compelling technical reason.

Prefer filesystem/app-storage media with database references pointing to those files.

Design the actual schema yourself based on good Android/database practices.

---

# PACKET CONCEPT

Messages travelling through the mesh will eventually require a transport representation.

Conceptually a packet may contain information similar to:

* protocolVersion
* packetId
* messageId
* sourceId
* destinationId
* timestamp
* TTL
* hopCount
* payloadType
* encryptedPayload
* integrity/authentication information

This is conceptual.

Design the final packet protocol yourself.

Avoid inefficient formats if BLE transport limitations make another format more appropriate.

JSON may be acceptable for debugging/prototyping, while a binary format such as Protocol Buffers may eventually be more appropriate.

Decide when migration is justified.

---

# DUPLICATE PROTECTION

The network must eventually prevent packets from circulating forever.

A device should be able to determine whether it has already handled a packet.

Possible mechanisms include:

* globally unique packet IDs
* local packet cache
* expiry timestamps
* TTL
* hop count limits

Design this properly when that phase arrives.

---

# SECURITY PRINCIPLE

Do not create custom cryptographic algorithms.

Eventually the application should provide end-to-end encryption.

Intermediate relay devices should ideally see transport metadata required for routing but should not be capable of reading message contents.

Keys should be stored using appropriate Android security mechanisms such as Android Keystore.

Use established cryptographic primitives/libraries.

Security is NOT required in the first BLE prototype phase unless required by architecture.

Introduce security gradually so networking failures and encryption failures can be debugged independently.

---

# DEVELOPMENT PHASES

---

# PHASE 0 — ENGINEERING PLAN AND FEASIBILITY

DO NOT WRITE THE APPLICATION YET.

Your first job is to analyze the project.

Produce:

1. Proposed application architecture.
2. Package/module structure.
3. Major Android components.
4. BLE communication strategy.
5. Data persistence strategy.
6. Background execution strategy.
7. Permissions required.
8. Minimum Android version recommendation.
9. Target Android version considerations.
10. Dependencies you recommend.
11. Risks and platform limitations.
12. Testing strategy.
13. Multi-device testing strategy.
14. Whether BLE alone is appropriate for both discovery and message transport.
15. Alternatives worth considering, including combinations such as BLE discovery + another local transport mechanism if technically superior.

Evaluate Android constraints carefully.

Especially investigate:

* BLE scanning restrictions
* BLE advertising restrictions
* background execution
* foreground service requirements
* Android permission changes between OS versions
* device manufacturer battery optimizations
* concurrent BLE roles
* BLE MTU limitations
* connection limits
* scanning reliability
* advertising reliability
* power usage
* operation when app is backgrounded
* process death

Do not proceed to Phase 1.

Finish Phase 0 and wait for approval.

---

# PHASE 1 — PROJECT FOUNDATION

Goal:

Create a clean Android project that launches reliably.

Implement only:

* Kotlin Android project
* Jetpack Compose UI foundation
* navigation structure if necessary
* dependency injection if justified
* permissions architecture
* basic logging infrastructure
* basic project package structure

Create a simple developer/debug screen capable of showing application status.

Do NOT implement actual messaging.

Do NOT build complete profiles.

Do NOT build chat UI.

Do NOT implement routing.

Do NOT implement encryption.

At completion:

* build project
* run tests
* verify app launches
* explain architecture

STOP.

---

# PHASE 2 — BLE DEVICE DISCOVERY

Goal:

Two Android devices running CampusMesh must be able to detect each other.

Implement:

* BLE scanning
* BLE advertising
* application-specific service identifier
* peer representation
* nearby-peer list
* signal strength if available
* discovery timestamps

Create a simple developer UI such as:

Nearby CampusMesh devices

Device A
RSSI
Last seen

Device B
RSSI
Last seen

Solve lifecycle and permission behavior correctly.

Handle Bluetooth being disabled.

Handle missing Bluetooth permissions.

Handle unsupported hardware.

Do NOT send chat messages yet.

At completion test:

Device A sees Device B.

Device B sees Device A.

Test discovery repeatedly.

Test screen off/background behavior where practical.

Document limitations.

STOP.

---

# PHASE 3 — DIRECT PHONE-TO-PHONE TEXT TRANSPORT

Goal:

Send a small piece of text between two phones that are directly within communication range.

This is NOT yet mesh routing.

Implement an appropriate direct transport mechanism based on your Phase 0 conclusions.

Example desired behavior:

Phone A:
Send "hello"

Phone B:
Receives "hello"

Create debugging information showing:

* connection status
* bytes sent
* bytes received
* transfer failures
* disconnects

Implement framing/reassembly if transport fragmentation requires it.

Do NOT add encryption yet unless absolutely necessary.

Do NOT add routing.

Do NOT relay packets.

Focus entirely on reliable transport.

Test:

* repeated messages
* message bursts
* reconnects
* Bluetooth disabled during communication
* application restart
* malformed packets
* payload larger than one transport frame

STOP.

---

# PHASE 4 — LOCAL DATABASE

Goal:

Persist application data.

Introduce Room/SQLite.

Implement only the minimum entities required for current functionality.

Likely:

* LocalUser
* Peer
* Message
* Conversation if justified

Messages should survive application restarts.

Implement repository abstractions.

Ensure UI does not directly talk to database DAOs.

Create migration strategy from the beginning.

Do not prematurely create twenty tables.

Profile image metadata may exist but image functionality is not required yet.

STOP.

---

# PHASE 5 — BASIC CHAT EXPERIENCE

Goal:

Turn direct transport into a usable one-to-one chat between two nearby devices.

Implement:

* local user identity
* basic username/display name
* peer selection
* conversation screen
* outgoing message
* incoming message
* timestamp
* basic message statuses

Possible states:

* pending
* sent
* received
* failed

Do not pretend "delivered" means something unless the recipient actually acknowledges receipt.

Use acknowledgements if required.

Do NOT implement multi-hop yet.

STOP.

---

# PHASE 6 — PACKET PROTOCOL

Goal:

Create the transport abstraction needed for mesh operation.

Messages should no longer be arbitrary strings sent directly.

Create a versioned packet structure.

It should support concepts such as:

* packet ID
* message ID
* source
* destination
* payload
* timestamp
* TTL
* hop count
* protocol version

Implement:

* serialization
* deserialization
* packet validation
* malformed packet rejection
* version handling
* duplicate detection

Packets should be testable independently from Bluetooth.

Create unit tests.

STOP.

---

# PHASE 7 — STORE-AND-FORWARD RELAYING

This is the most important milestone.

Goal:

Three phones:

A
B
C

A and C should NOT require a direct connection.

Desired result:

A → B → C

Phone B temporarily stores the packet and later forwards it to C.

Implement:

* relay packet storage
* forwarding queue
* packet expiry
* duplicate prevention
* TTL
* hop counter
* destination detection
* delivery acknowledgement strategy

Initially keep routing simple.

Simple controlled flooding or another straightforward strategy is acceptable for the first proof of concept.

Prioritize correctness over routing sophistication.

Critical test:

Arrange devices so A cannot directly communicate with C.

A sends a message addressed to C.

B receives it.

B later encounters C.

C receives the original message.

If this works, the core CampusMesh concept is proven.

STOP.

Do NOT start optimizing routing yet.

---

# PHASE 8 — END-TO-END ENCRYPTION

Goal:

Relay devices must not be able to read message content.

Design identity/key handling.

Use established cryptographic libraries.

Do not create custom cryptography.

Requirements:

Sender encrypts.

Relay stores encrypted payload.

Relay forwards encrypted payload.

Only recipient decrypts.

Protect private keys with Android security mechanisms where appropriate.

Consider:

* identity verification
* public key exchange
* replay attacks
* tampering
* authenticated encryption
* key rotation
* device reinstall
* lost keys

Do not implement overly complex Signal-style infrastructure unless necessary for project goals.

Aim for secure, explainable architecture appropriate for the application and academic project.

STOP.

---

# PHASE 9 — BETTER RELAY ROUTING

Only after simple forwarding works.

Improve network behavior.

Study options such as:

* epidemic routing
* controlled flooding
* probabilistic forwarding
* encounter-history routing
* destination-aware forwarding
* relay scoring
* hop-limited routing

Do not choose an algorithm simply because it sounds advanced.

Choose based on:

* battery
* campus device density
* reliability
* implementation complexity
* duplicate traffic
* storage
* delivery probability

Create metrics.

Examples:

* packets forwarded
* duplicates discarded
* average hop count
* time to delivery
* packet expiry rate
* peer encounter frequency

STOP.

---

# PHASE 10 — PROFILE SYSTEM

Implement:

* display name
* local profile identity
* profile picture
* profile persistence

Store image files outside SQLite.

Store only references/metadata in Room.

Design how profiles propagate across the mesh.

Profile propagation should not create excessive network traffic.

Consider content hashes or versions so unchanged profile pictures are not repeatedly transferred.

STOP.

---

# PHASE 11 — IMAGE AND FILE TRANSFER

Only after text messaging is reliable.

Implement attachments.

Challenges to solve:

* chunking
* transfer interruption
* partial download
* resume
* checksums
* duplicate files
* storage limits
* large payloads
* prioritization

Do not allow a 100 MB video to block urgent text packets.

Design separate transfer priorities.

Possible priority idea:

Emergency
Text
Profile metadata
Images
Large files

Decide the final system yourself.

STOP.

---

# PHASE 12 — GROUP CHAT

Implement groups after one-to-one messaging is stable.

Consider:

* group identity
* membership
* group encryption
* forwarding duplicates
* message ordering
* membership changes
* offline members

Avoid central-server assumptions.

STOP.

---

# PHASE 13 — CAMPUS BROADCASTS

Implement optional broadcast channels.

Examples:

Campus announcements.

Club announcements.

Emergency notifications.

Design authorization carefully.

Any user must NOT automatically be able to impersonate the university.

Consider signed broadcast identities.

STOP.

---

# PHASE 14 — BATTERY AND BACKGROUND OPTIMIZATION

Analyze real device behavior.

Optimize:

* BLE scan intervals
* advertising intervals
* connection duration
* queue processing
* wakeups
* foreground services
* background behavior
* Doze
* Android vendor battery restrictions

Measure instead of guessing.

STOP.

---

# PHASE 15 — RELIABILITY HARDENING

Simulate failure cases.

Test:

* Bluetooth disabled
* peer disappears
* interrupted transfer
* corrupted packet
* duplicate packet
* expired packet
* low storage
* application killed
* phone reboot
* database corruption handling
* key unavailable
* protocol mismatch
* app update
* hundreds of queued packets
* many nearby peers

Fix major reliability issues.

STOP.

---

# PHASE 16 — UI/UX POLISH

Only now polish the application.

Possible screens:

Chats
Nearby
Contacts
Campus
Settings
Profile

Keep the UI modern and minimal.

Do not sacrifice network reliability for visual polish.

Add clear connectivity states such as:

Nearby peer available

Message waiting for relay

Relaying

Delivered

Expired

Failed

Offline mode

STOP.

---

# PHASE 17 — TESTING AND DEMONSTRATION MODE

Prepare the application for university demonstration.

Create a developer diagnostics screen.

Useful diagnostics:

Device ID

Nearby nodes

Active connections

Packets stored

Packets forwarded

Duplicates blocked

Messages delivered

Current BLE status

Scan status

Advertise status

Routing activity

Allow demonstration with three or more devices.

Create logs useful enough to visibly prove:

A sent packet.

B received packet.

B became relay.

B forwarded packet.

C received packet.

C decrypted message.

This is extremely important for demonstrating the project academically.

STOP.

---

# POTENTIAL PROBLEMS YOU MUST THINK ABOUT

These are risks to investigate, not implementation instructions.

You are responsible for determining the proper engineering solution.

## Bluetooth limitations

Potential issues:

* BLE range varies drastically by hardware.
* Walls and humans reduce signal strength.
* BLE is not automatically a multi-hop network between phones.
* Android devices may behave differently as advertisers/servers/clients.
* BLE packet size is limited.
* Large payloads require fragmentation.
* Connections may unexpectedly drop.
* Not all phones support identical BLE functionality.
* Some devices limit simultaneous BLE connections.

## Android restrictions

Potential issues:

* background BLE scanning limitations
* Android permission changes
* Nearby Devices permissions
* location-related behavior on older Android versions
* foreground service restrictions
* Doze mode
* process death
* OEM battery managers
* screen-off behavior
* app standby

## Mesh-network problems

Potential issues:

* broadcast storms
* duplicate packets
* infinite forwarding loops
* overloaded relay queues
* unreliable routes
* changing topology
* nodes appearing and disappearing
* delayed message delivery
* TTL decisions
* message ordering
* packet expiry
* congestion

## Security problems

Potential issues:

* fake identities
* malicious relay nodes
* packet modification
* packet replay
* public key spoofing
* metadata leakage
* spam
* denial of service
* malicious oversized payloads
* key loss
* compromised phones

## Storage problems

Potential issues:

* relay cache growing indefinitely
* media storage exhaustion
* expired packet cleanup
* duplicated attachments
* database migrations
* corrupted files
* application reinstall
* orphaned media files

## User-experience problems

Potential issues:

A message may not arrive immediately because no useful relay exists.

Users must understand states such as:

Waiting for network

Waiting for relay

Forwarding

Delivered

Expired

This system is fundamentally different from Internet messaging.

The UI should represent reality rather than falsely implying instant delivery.

## Privacy

Relay phones should not learn more information than necessary.

Think carefully about:

* sender IDs
* recipient IDs
* conversation IDs
* message timestamps
* profile information
* routing metadata

Determine which metadata must remain visible for routing and which can be hidden.

---

# IMPORTANT ENGINEERING BEHAVIOR

When you encounter a problem:

Do NOT immediately hack around it.

First identify:

1. Root cause.
2. Whether it comes from Android, Bluetooth, architecture, hardware, or code.
3. Whether the issue is deterministic or device-specific.
4. Possible solutions.
5. Trade-offs.

Then implement the most appropriate solution.

You are encouraged to improve the architecture when justified.

---

# DO NOT ASSUME BLUETOOTH IS ALWAYS THE BEST TRANSPORT

The project concept is:

Offline nearby peer-to-peer mesh communication.

Bluetooth is an intended technology, but you should evaluate whether a hybrid architecture is technically better.

For example, you may investigate architectures such as:

BLE for:

Discovery
Presence
Handshake

and another local communication method for:

larger/faster data transfer

if Android supports it reliably without requiring Internet access.

Potential technologies worth evaluating include:

* BLE
* Bluetooth Classic
* Wi-Fi Direct
* Wi-Fi Aware
* Nearby technologies where licensing/dependency constraints allow

Do not replace the project's decentralized/offline principle.

The final communication mechanism must work without relying on Internet infrastructure.

---

# CODE QUALITY REQUIREMENTS

Use:

* meaningful class names
* separation of concerns
* coroutines instead of unmanaged threads
* structured concurrency
* lifecycle-aware components
* repositories where appropriate
* clear domain models
* defensive parsing
* useful logs
* error handling
* unit tests for packet logic
* documentation for non-obvious networking logic

Avoid:

* giant God classes
* networking code inside Compose screens
* database calls directly from UI
* magic constants
* hardcoded user IDs
* hardcoded Bluetooth addresses
* custom cryptography
* silent exception swallowing
* unnecessary abstractions

---

# VERSION CONTROL

Prefer each major phase to correspond to a clean Git checkpoint.

Example:

phase-1-foundation

phase-2-ble-discovery

phase-3-direct-transport

phase-4-local-storage

phase-5-direct-chat

phase-6-packet-protocol

phase-7-multihop-relay

This makes regression easier to diagnose.

---

# FINAL SUCCESS CONDITION

The most important demonstration is NOT the UI.

The project's defining success test is:

Three Android phones exist:

A
B
C

A cannot directly reach C.

A creates a message for C.

A discovers B.

A transfers the encrypted or appropriately encoded packet to B.

B stores it.

B later discovers C.

B forwards the packet.

C recognizes itself as the destination.

C accepts the packet.

C displays the message.

Duplicate copies do not endlessly circulate.

That is the core CampusMesh system.

Everything beyond that is an extension.

---

# BEGIN NOW

Perform **PHASE 0 ONLY**.

Do not create application code yet.

Analyze feasibility and propose the architecture.

Think critically rather than merely agreeing with this specification.

If you believe any major assumption in CampusMesh is technically weak, identify it and propose a better solution.

End the response with:

PHASE 0 STATUS: COMPLETE / BLOCKED

Then provide:

Recommended next step

Do not start Phase 1 until explicitly instructed.
