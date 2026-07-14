package com.messaging.data.datasource.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * UserEntity - Local database entity representing cached user profile details
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: Long,
    val username: String,
    val email: String,
    val displayName: String,
    val profilePictureUrl: String? = null
)
