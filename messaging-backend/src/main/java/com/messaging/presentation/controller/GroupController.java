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

    @GetMapping("/cleared")
    public ResponseEntity<List<GroupResponse>> getClearedGroups(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<GroupResponse> groups = groupService.getClearedGroups(principal.getId(), page, size);
        return ResponseEntity.ok(groups);
    }

    @GetMapping("/deleted")
    public ResponseEntity<List<GroupResponse>> getDeletedGroups(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<GroupResponse> groups = groupService.getDeletedGroups(principal.getId(), page, size);
        return ResponseEntity.ok(groups);
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<GroupResponse> getGroupDetails(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("groupId") Long groupId
    ) {
        GroupResponse group = groupService.getGroupDetails(principal.getId(), groupId);
        return ResponseEntity.ok(group);
    }

    @PatchMapping("/{groupId}")
    public ResponseEntity<GroupResponse> updateGroup(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("groupId") Long groupId,
            @RequestBody com.messaging.domain.dto.request.GroupUpdateRequest request
    ) {
        GroupResponse group = groupService.updateGroup(principal.getId(), groupId, request);
        return ResponseEntity.ok(group);
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<Void> addMembers(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("groupId") Long groupId,
            @RequestBody List<Long> userIds
    ) {
        groupService.addMembers(principal.getId(), groupId, userIds);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("groupId") Long groupId,
            @PathVariable("userId") Long userId
    ) {
        groupService.removeMember(principal.getId(), groupId, userId);
        return ResponseEntity.ok().build();
    }
}
