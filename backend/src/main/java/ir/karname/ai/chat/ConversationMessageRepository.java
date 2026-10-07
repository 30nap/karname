package ir.karname.ai.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    List<ConversationMessage> findByConversationIdOrderBySeqAsc(long conversationId);

    @Query("SELECT coalesce(max(m.seq), 0) FROM ConversationMessage m WHERE m.conversationId = :conversationId")
    int maxSeq(@Param("conversationId") long conversationId);

    /** Stops sending a failed turn to the model; its messages stay for the record. */
    @Modifying
    @Query("UPDATE ConversationMessage m SET m.excluded = true WHERE m.conversationId = :conversationId AND m.turn = :turn")
    int exclude(@Param("conversationId") long conversationId, @Param("turn") int turn);

    @Modifying
    @Query("UPDATE ConversationMessage m SET m.error = :error WHERE m.conversationId = :conversationId AND m.turn = :turn "
            + "AND m.seq = (SELECT min(x.seq) FROM ConversationMessage x WHERE x.conversationId = :conversationId AND x.turn = :turn)")
    int setError(@Param("conversationId") long conversationId, @Param("turn") int turn, @Param("error") String error);
}
