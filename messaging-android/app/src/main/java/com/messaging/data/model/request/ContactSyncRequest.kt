package com.messaging.data.model.request

import com.google.gson.annotations.SerializedName

data class ContactSyncRequest(
    @SerializedName("phoneNumbers")
    val phoneNumbers: List<String>
)
