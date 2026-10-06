package ir.karname.notification;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserIdOrderByCreatedAtDescIdDesc(long userId, Limit limit);

    List<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(long userId, Limit limit);

    long countByUserIdAndReadAtIsNull(long userId);

    Optional<Notification> findByIdAndUserId(long id, long userId);
}
