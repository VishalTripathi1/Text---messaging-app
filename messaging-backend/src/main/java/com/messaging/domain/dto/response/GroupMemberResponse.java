package com.messaging.domain.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GroupMemberResponse - A group member's user info paired with their role.
 *
 * GroupResponse.members used to just be a List<UserResponse>, with no way for a client to tell
 * an admin from a regular member - the data model (GroupMember.role) already had this, it just
 * was never surfaced through the API, which is why the app had no admin-only controls despite
 * the backend already enforcing admin-only permission on removeMember().
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupMemberResponse {
    private UserResponse user;
    private boolean isAdmin;
}
