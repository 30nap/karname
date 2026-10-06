package ir.karname.user;

/** Published inside the registration transaction so other modules can seed per-user data. */
public record UserRegisteredEvent(long userId) {
}
