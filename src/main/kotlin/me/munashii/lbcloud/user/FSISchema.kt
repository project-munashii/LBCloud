package me.munashii.lbcloud.user

import kotlinx.serialization.Serializable

@Serializable
data class FileSchema(
    val version: Int,
    val name: String,
    val uploadTime: Long
)

@Serializable
data class FSSchema(
    val version: Int,
    val name: String,
    val files: MutableList<FileSchema>
)

@Serializable
data class FSISchema(
    val version: Int,
    var totalSize: Long,
    val schemas: MutableList<FSSchema>
)
