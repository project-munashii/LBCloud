package me.munashii.lbcloud.user

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import me.munashii.lbcloud.UserIdentifier
import java.io.File

object UserFS {

    private val fsIndexCache = mutableMapOf<UserIdentifier, File>()

    private val fsLocks = mutableListOf<UserIdentifier>()

    @Suppress("NOTHING_TO_INLINE")
    private inline fun getFSIndex(user: UserIdentifier): File {
        return fsIndexCache.getOrPut(user) {
            File("userfs/$user/.fsi.json")
        }
    }

    enum class UploadStatus {
        OK,
        LIMIT_EXCEEDED
    }

    fun upload(user: UserIdentifier, location: String, name: String, data: String): UploadStatus {
        while (fsLocks.contains(user)) {
            Thread.sleep(100)
        }

        fsLocks.add(user)

        val fsi = getFSIndex(user)

        if (!fsi.exists()) {
            addUser(user)
        }

        val fsiData = Json.decodeFromString<FSISchema>(fsi.readText())

        if (fsiData.totalSize + data.length > 3.2e+7) { // 32mb, 16mb of files
            fsLocks.remove(user)
            println(":: UserFS: FS size limit exceeded by user ${user}.")
            return UploadStatus.LIMIT_EXCEEDED
        }

        val fs = fsiData.schemas.firstOrNull { it.name == location }

        if (fs == null) {
            fsiData.schemas.add(
                FSSchema(
                    1,
                    location,
                    mutableListOf()
                )
            )
        }

        val fsData = fs ?: fsiData.schemas.first { it.name == location }

        fsData.files.add(
            FileSchema(
                1,
                name,
                System.currentTimeMillis()
            )
        )

        fsiData.totalSize += data.length

        fsi.writeText(Json.encodeToString(fsiData))

        File("userfs/$user/$location/$name").apply {
            parentFile.mkdirs()
            createNewFile()
        }.writeText(data)

        fsLocks.remove(user)

        println(":: UserFS: File uploaded.")

        return UploadStatus.OK
    }

    enum class DownloadStatus {
        OK,
        NOT_FOUND
    }

    fun download(user: UserIdentifier, location: String, name: String): Pair<DownloadStatus, String> {
        while (fsLocks.contains(user)) {
            Thread.sleep(100)
        }

        fsLocks.add(user)

        val fsi = getFSIndex(user)

        if (!fsi.exists()) {
            addUser(user)

            fsLocks.remove(user)

            println(":: UserFS: UserFS index not found for user $user with request: $location/$name")
            return Pair(DownloadStatus.NOT_FOUND, "")
        }

        val fsiData = Json.decodeFromString<FSISchema>(fsi.readText())

        val fs = fsiData.schemas.firstOrNull { it.name == location }

        if (fs == null) {
            fsLocks.remove(user)

            println(":: UserFS: Location not found for user $user with request: $location/$name")
            return Pair(DownloadStatus.NOT_FOUND, "")
        }

        val file = fs.files.firstOrNull { it.name == name }

        if (file == null) {
            fsLocks.remove(user)

            println(":: UserFS: File not found for user $user with request: $location/$name")
            return Pair(DownloadStatus.NOT_FOUND, "")
        }

        val data = File("userfs/$user/$location/$name").readText()

        fsLocks.remove(user)

        println(":: UserFS: File downloaded, size: ${data.length} bytes (${data.length/2} after decryption).")
        return Pair(DownloadStatus.OK, data)
    }

    fun list(user: UserIdentifier, location: String): List<String> {
        while (fsLocks.contains(user)) {
            Thread.sleep(100)
        }

        fsLocks.add(user)

        val fsi = getFSIndex(user)

        if (!fsi.exists()) {
            addUser(user)

            fsLocks.remove(user)

            println(":: UserFS: UserFS index not found for user $user.")
            return emptyList()
        }

        val fsiData = Json.decodeFromString<FSISchema>(fsi.readText())

        val fsData = fsiData.schemas.firstOrNull { it.name == location }

        fsLocks.remove(user)

        println(":: UserFS: Locations listed for user $user.")
        return fsData?.files?.map { it.name } ?: emptyList()
    }

    fun addUser(user: UserIdentifier) {
        val fsi = getFSIndex(user)

        if (!fsi.exists()) {
            fsi.parentFile.mkdirs()
            fsi.createNewFile()

            fsi.writeText(Json.encodeToString(
                FSISchema(
                    1,
                    0,
                    mutableListOf()
                )
            ))

            println(":: Created UserFS index for $user.")
        }
    }

    fun validateUser(user: UserIdentifier) {
        val fsi = getFSIndex(user)

        if (!fsi.exists()) {
            addUser(user)
        }
    }

    fun checkCache(allowedSize: Int) {
        if (fsIndexCache.size > allowedSize) {
            fsIndexCache.clear()
            println(":: Cleared UserFS cache.")
        }
    }

}