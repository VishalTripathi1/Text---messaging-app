package com.messaging.data.datasource.remote

import com.messaging.data.model.request.ContactSyncRequest
import com.messaging.data.model.request.DeviceRegisterRequest
import com.messaging.data.model.request.GroupCreateRequest
import com.messaging.data.model.request.MessageRequest
import com.messaging.data.model.request.UserProfileUpdateRequest
import com.messaging.data.model.request.UserSettingsUpdateRequest
import com.messaging.data.model.response.ChatResponse
import com.messaging.data.model.response.GroupResponse
import com.messaging.data.model.response.MessageResponse
import com.messaging.data.model.response.UserResponse
import com.messaging.data.model.response.UserSettingsResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

/**
 * ChatApiService - Retrofit HTTP endpoints for messaging operations
 */
interface ChatApiService {
    @POST("v1/chats/message")
    suspend fun sendHttpMessage(
        @Body request: MessageRequest
    ): Response<MessageResponse>

    @POST("v1/chats")
    suspend fun getOrCreateChat(
        @Query("recipientId") recipientId: Long
    ): Response<ChatResponse>

    @GET("v1/chats")
    suspend fun getUserChats(
        @Query("page") page: Int,
        @Query("size") size: Int
    ): Response<List<ChatResponse>>

    @GET("v1/chats/archived")
    suspend fun getArchivedChats(
        @Query("page") page: Int,
        @Query("size") size: Int
    ): Response<List<ChatResponse>>

    @GET("v1/chats/{chatId}/messages")
    suspend fun getChatMessages(
        @Path("chatId") chatId: Long,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): Response<List<MessageResponse>>

    @POST("v1/chats/{chatId}/read")
    suspend fun markChatAsRead(
        @Path("chatId") chatId: Long
    ): Response<Unit>

    @GET("v1/users/search")
    suspend fun searchUsers(
        @Query("query") query: String
    ): Response<List<UserResponse>>

    @POST("v1/users/sync-contacts")
    suspend fun syncContacts(
        @Body request: ContactSyncRequest
    ): Response<List<UserResponse>>

    @GET("v1/users/all")
    suspend fun getAllUsers(): Response<List<UserResponse>>

    // Group Chat API
    @POST("v1/groups")
    suspend fun createGroup(
        @Body request: GroupCreateRequest
    ): Response<GroupResponse>

    @GET("v1/groups")
    suspend fun getUserGroups(
        @Query("page") page: Int,
        @Query("size") size: Int
    ): Response<List<GroupResponse>>

    @GET("v1/groups/{groupId}")
    suspend fun getGroupDetails(
        @Path("groupId") groupId: Long
    ): Response<GroupResponse>

    @POST("v1/groups/{groupId}/members")
    suspend fun addGroupMembers(
        @Path("groupId") groupId: Long,
        @Body userIds: List<Long>
    ): Response<Unit>

    @DELETE("v1/groups/{groupId}/members/{userId}")
    suspend fun removeGroupMember(
        @Path("groupId") groupId: Long,
        @Path("userId") userId: Long
    ): Response<Unit>

    @GET("v1/chats/group/{groupId}/messages")
    suspend fun getGroupMessages(
        @Path("groupId") groupId: Long,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): Response<List<MessageResponse>>

    // Attachment Upload API
    @Multipart
    @POST("v1/attachments/upload")
    suspend fun uploadAttachment(
        @Part file: MultipartBody.Part
    ): Response<Map<String, Any>>

    @PUT("v1/chats/message/{messageId}")
    suspend fun editMessage(
        @Path("messageId") messageId: Long,
        @Query("text") text: String
    ): Response<MessageResponse>

    @DELETE("v1/chats/message/{messageId}")
    suspend fun deleteMessage(
        @Path("messageId") messageId: Long
    ): Response<MessageResponse>

    @POST("v1/chats/message/{messageId}/react")
    suspend fun addReaction(
        @Path("messageId") messageId: Long,
        @Query("reaction") reaction: String
    ): Response<Unit>

    @DELETE("v1/chats/message/{messageId}/react")
    suspend fun removeReaction(
        @Path("messageId") messageId: Long
    ): Response<Unit>

    @PUT("v1/chats/{chatId}/pin")
    suspend fun pinChat(
        @Path("chatId") chatId: Long,
        @Query("pinned") pinned: Boolean
    ): Response<ChatResponse>

    @PUT("v1/chats/{chatId}/archive")
    suspend fun archiveChat(
        @Path("chatId") chatId: Long,
        @Query("archived") archived: Boolean
    ): Response<ChatResponse>

    @PUT("v1/chats/{chatId}/block")
    suspend fun blockChat(
        @Path("chatId") chatId: Long
    ): Response<Unit>

    @DELETE("v1/chats/{chatId}/block")
    suspend fun unblockChat(
        @Path("chatId") chatId: Long
    ): Response<Unit>

    @GET("v1/chats/{chatId}/blocked")
    suspend fun isChatBlocked(
        @Path("chatId") chatId: Long
    ): Response<Boolean>

    @DELETE("v1/chats/{chatId}")
    suspend fun deleteChat(
        @Path("chatId") chatId: Long
    ): Response<Unit>

    @DELETE("v1/chats/{chatId}/messages")
    suspend fun clearChat(
        @Path("chatId") chatId: Long
    ): Response<Unit>

    @GET("v1/chats/search")
    suspend fun searchMessages(
        @Query("query") query: String,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): Response<List<MessageResponse>>

    @POST("v1/devices")
    suspend fun registerDevice(
        @Body request: DeviceRegisterRequest
    ): Response<Unit>

    @DELETE("v1/devices/{deviceId}")
    suspend fun unregisterDevice(
        @Path("deviceId") deviceId: String
    ): Response<Unit>

    // User Profile & Settings API
    @GET("v1/users/me")
    suspend fun getCurrentUser(): Response<UserResponse>

    @PUT("v1/users/profile")
    suspend fun updateProfile(
        @Body request: UserProfileUpdateRequest
    ): Response<UserResponse>

    @GET("v1/users/settings")
    suspend fun getSettings(): Response<UserSettingsResponse>

    @PUT("v1/users/settings")
    suspend fun updateSettings(
        @Body request: UserSettingsUpdateRequest
    ): Response<UserSettingsResponse>
}
