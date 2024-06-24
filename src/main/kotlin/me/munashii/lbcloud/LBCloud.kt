package me.munashii.lbcloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.munashii.lbcloud.db.SaveOnWriteHashMap
import me.munashii.lbcloud.user.UserFS
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.CONFLICT
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.UNPROCESSABLE_ENTITY
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.math.max

// mostly just to make my life easier
typealias UserIdentifier = String
typealias UserToken = String
typealias AuthToken = String

object LBCloud {

    @Serializable
    private data class UserDB (
        val users: MutableMap<AuthToken, UserIdentifier>
    )

    private val userDBFile = Paths.get("user_db.json")

    private val userFSDir = Paths.get("userfs")

    private val db: MutableMap<AuthToken, UserIdentifier> by lazy {
        SaveOnWriteHashMap {
            userDBFile.toFile().writeText(Json.encodeToString(UserDB(it)))
            println(":: Saved user database.")

            UserFS.checkCache(256)
            println(":: Checked UserFS cache.")
        }
    }

    private val dbIdentifierCache: MutableMap<UserToken, UserIdentifier> = mutableMapOf()

    suspend fun init() = coroutineScope {

        println(":: Starting LBCloud...")

        val ws = { request: Request ->
            when (request.uri.path) {

                "/v1/register" -> {
                    println(":: /v1/register")

                    if (db.size > 32) { // FIXME: TEMP
                        println("^: RES: User limit reached.")
                        Response(INTERNAL_SERVER_ERROR).body("{ \"error\": \"spam ping @._._._.__.___ on discord!!!\"}")
                    }

                    @Serializable
                    data class RegisterRequest(val token: UserToken)

                    val registerRequest = Json.decodeFromString<RegisterRequest>(request.bodyString())

                    println("^: REQ: $registerRequest")

                    val reqIdentifierComponent = registerRequest.token
                        .toByteArray().let { MessageDigest.getInstance("MD5").digest(it) }.copyOf(16)
                        .joinToString("") { byte -> "%02x".format(byte) }.lowercase()

                    println("^: RID: $reqIdentifierComponent")

                    if (dbIdentifierCache.containsKey(reqIdentifierComponent)) {
                        println("^: RES: User already registered.")
                        Response(OK).body("{ \"error\": \"USER_EXISTS\"}")
                    } else {

                        println("^: GEN: Generating user token.")

                        /*
                        This is sadly not foolproof, there is a condition where you generate conflicting data, but it's so unlikely that it's not worth the effort to fix.

                        The case where this happens is when the timeComponent for 2 users is generated in the same millisecond, and one of the following occurs:
                        1. The first 16 bytes of their user tokens' MD5 are identical;
                        2. They generate the exact same 6 random bytes.

                        If this happens, I will go and eat cat food. Quote me on this.
                         */

                        val timeComponent = System.currentTimeMillis().toString(16).run { substring(max(0, length - 16), length) }.lowercase()

                        println("^: TIME: $timeComponent")

                        val identifierComponent = registerRequest.token
                            .toByteArray().let { MessageDigest.getInstance("MD5").digest(it) }.copyOf(16)
                            .joinToString("") { byte -> "%02x".format(byte) }.lowercase()

                        println("^: ID: $identifierComponent")

                        val randomComponent = (0 until 6).map { (0..255).random().toByte() }.joinToString("")
                        { byte -> "%02x".format(byte) }.lowercase()

                        println("^: RAND: $randomComponent")

                        db["$randomComponent$timeComponent"] = "$identifierComponent$timeComponent"
                        dbIdentifierCache[identifierComponent] = "$identifierComponent$timeComponent"

                        UserFS.addUser("$identifierComponent$timeComponent")

                        println("^: RES: User registered.")

                        Response(OK).body("{ \"token\": \"${randomComponent}${timeComponent}\"}")
                    }
                }

                // FIXME: REMOVE IN PROD - SHOULD NOT EXIST !!!
                "/v1/identify" -> {
                    println(":: /v1/identify")

                    @Serializable
                    data class IdentifyRequest(val token: AuthToken)

                    val identifyRequest = Json.decodeFromString<IdentifyRequest>(request.bodyString())

                    if (db.containsKey(identifyRequest.token)) {
                        println("^: RES: User found.")
                        Response(OK).body("{ \"user\": \"${db[identifyRequest.token]}\"}")
                    } else {
                        println("^: RES: User not found.")
                        Response(OK).body("{ \"error\": \"USER_404\"}")
                    }
                }

                "/v1/upload" -> {
                    println(":: /v1/upload")

                    @Serializable
                    data class UploadRequest(
                        val token: AuthToken,
                        val location: String,
                        val name: String,
                        val data: String
                    )

                    val uploadRequest = Json.decodeFromString<UploadRequest>(request.bodyString())

                    println("^: REQ: $uploadRequest")

                    if (db.containsKey(uploadRequest.token)) {
                        println("^: RES: User found.")

                        val userIdentifier = db[uploadRequest.token]!!

                        val status = UserFS.upload(
                            userIdentifier,
                            uploadRequest.location,
                            uploadRequest.name,
                            uploadRequest.data
                        )

                        println("^: RES: Status: ${status.name}.")

                        Response(OK).body("{ \"result\": \"${status.name}\"}")
                    } else {
                        println("^: RES: User not found.")
                        Response(OK).body("{ \"error\": \"USER_404\"}")
                    }
                }

                "/v1/download" -> {
                    println(":: /v1/download")

                    @Serializable
                    data class DownloadRequest(
                        val token: AuthToken,
                        val location: String,
                        val name: String
                    )

                    val downloadRequest = Json.decodeFromString<DownloadRequest>(request.bodyString())

                    println("^: REQ: $downloadRequest")

                    if (db.containsKey(downloadRequest.token)) {
                        println("^: RES: User found.")

                        val userIdentifier = db[downloadRequest.token]!!

                        val (status, data) = UserFS.download(
                            userIdentifier,
                            downloadRequest.location,
                            downloadRequest.name
                        )

                        println("^: RES: Status: ${status.name}.")

                        if (status == UserFS.DownloadStatus.OK) {
                            Response(OK).body("{ \"data\": \"$data\"}")
                        } else {
                            Response(OK).body("{ \"error\": \"FILE_404\"}")
                        }
                    } else {
                        println("^: RES: User not found.")
                        Response(OK).body("{ \"error\": \"USER_404\"}")
                    }
                }

                "/v1/list" -> {
                    println(":: /v1/list")

                    @Serializable
                    data class ListRequest
                    (
                        val token: AuthToken,
                        val location: String
                    )

                    val listRequest = Json.decodeFromString<ListRequest>(request.bodyString())

                    println("^: REQ: $listRequest")

                    if (db.containsKey(listRequest.token)) {
                        println("^: RES: User found.")

                        val userIdentifier = db[listRequest.token]!!

                        val list = UserFS.list(userIdentifier, listRequest.location)

                        println("^: RES: Found ${list.size} entries.")

                        Response(OK).body("{ \"list\": ${Json.encodeToString(list)} }")
                    } else {
                        println("^: RES: User not found.")
                        Response(OK).body("{ \"error\": \"USER_404\"}")
                    }

                }

                else -> Response(NOT_FOUND).body("{ \"error\": \"Endpoint not found.\"}")
            }
        }.asServer(Undertow(3000))

        println(":: Starting WebServer...")

        if (userDBFile.toFile().exists()) {
            println(":: Loading user database...")

            (db as SaveOnWriteHashMap).lock()

            val reader = userDBFile.toFile().bufferedReader()

            val userDBObj = Json.decodeFromString<UserDB>(reader.readText())

            db.putAll(userDBObj.users)
            dbIdentifierCache.putAll(userDBObj.users.map { it.value.substring(0, 32) to it.value }) // if you get a hash collision, shoot me

            db.values.forEach {
                UserFS.validateUser(it)
            }
            UserFS.checkCache(128)

            withContext(Dispatchers.IO) {
                reader.close()
            }

            (db as SaveOnWriteHashMap).unlock()
        } else {
            println(":: No user database found, creating new one.")
        }

        userFSDir.toFile().mkdirs()

        ws.start()

        println(":: LBCloud started on port ${ws.port()}, ${db.size} entries in user DB.")

        withContext(Dispatchers.IO) {
            (db as SaveOnWriteHashMap).init()
        }
    }

}