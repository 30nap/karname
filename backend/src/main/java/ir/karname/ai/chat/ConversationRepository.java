package ir.karname.ai.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findTop100ByUserIdOrderByUpdatedAtDesc(long userId);

    Optional<Conversation> findByIdAndUserId(long id, long userId);
}
