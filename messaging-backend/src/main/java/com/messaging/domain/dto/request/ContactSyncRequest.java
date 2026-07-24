package com.messaging.domain.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request payload for matching device contacts against registered app users by phone number
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactSyncRequest {

    @NotNull(message = "Phone numbers list cannot be null")
    private List<String> phoneNumbers;
}
