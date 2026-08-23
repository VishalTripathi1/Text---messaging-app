package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.DeliveryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes every batch query added for the chat-list and message-history load path.
 *
 * <p>These are hand-written JPQL, and Spring Data parses @Query strings with its own grammar on each
 * invocation rather than at startup - a query can therefore compile, deploy, and only then throw
 * BadJpqlGrammarException the first time a screen asks for it (see the note on
 * ReadReceiptRepository.countUnreadMessagesInChat, which is exactly how that happened before). So the
 * point here is simply that each one parses and runs; the empty database is enough for that.
 */
@DataJpaTest
class BatchQueryGrammarTest {

    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private MessageReactionRepository messageReactionRepository;
    @Autowired private ReadReceiptRepository readReceiptRepository;
    @Autowired private DeliveryStatusRepository deliveryStatusRepository;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private UserSettingsRepository userSettingsRepository;
    @Autowired private ChatUserPreferenceRepository chatUserPreferenceRepository;
    @Autowired private ChatRepository chatRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;

    private static final List<Long> IDS = List.of(1L, 2L, 3L);

    @Test
    void everyBatchQueryParsesAndRuns() {
        assertThat(attachmentRepository.findAttachmentRowsByMessageIds(IDS)).isEmpty();
        assertThat(messageReactionRepository.findReactionRowsByMessageIds(IDS)).isEmpty();
        assertThat(readReceiptRepository.findReaderIdsByMessageIds(IDS)).isEmpty();
        assertThat(readReceiptRepository.countUnreadMessagesInChats(IDS, 1L)).isEmpty();
        assertThat(deliveryStatusRepository.findRecipientIdsByMessageIdsAndStatus(
                IDS, DeliveryStatus.DeliveryStatusType.DELIVERED)).isEmpty();
        assertThat(userProfileRepository.findProfileRowsByUserIds(IDS)).isEmpty();
        assertThat(userSettingsRepository.findShowReadReceiptsRowsByUserIds(IDS)).isEmpty();
        assertThat(chatUserPreferenceRepository.findPreferenceRowsByUserIdAndChatIds(1L, IDS)).isEmpty();
        assertThat(chatRepository.findParticipantIdRowsByChatIds(IDS)).isEmpty();
        assertThat(groupMemberRepository.findMemberIdsByGroupId(1L)).isEmpty();
    }
}
