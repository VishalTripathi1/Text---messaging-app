package com.messaging.presentation.controller;

import com.messaging.application.service.GroupService;
import com.messaging.domain.dto.request.GroupCreateRequest;
import com.messaging.domain.dto.response.GroupResponse;
import com.messaging.infrastructure.security.CustomUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * GroupController - REST controller providing endpoints for managing group rooms and members
 */
@RestController
@RequestMapping("/v1/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestBody GroupCreateRequest request
    ) {
        GroupResponse group = groupService.createGroup(principal.getId(), request);
        return ResponseEntity.ok(group);
    }

    @GetMapping
    public ResponseEntity<List<GroupResponse>> getUserGroups(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<GroupResponse> groups = groupService.getUserGroups(principal.getId(), page, size);
        return ResponseEntity.ok(groups);
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<GroupResponse> getGroupDetails(
            @PathVariable("groupId") Long groupId
    ) {
        GroupResponse group = groupService.getGroupDetails(groupId);
        return ResponseEntity.ok(group);
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<Void> addMembers(
            @PathVariable("groupId") Long groupId,
            @RequestBody List<Long> userIds
    ) {
        groupService.addMembers(groupId, userIds);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable("groupId") Long groupId,
            @PathVariable("userId") Long userId
    ) {
        groupService.removeMember(groupId, userId);
        return ResponseEntity.ok().build();
    }
}
