package ir.karname.category;

import ir.karname.category.DefaultCategories.Seed;
import ir.karname.user.UserRegisteredEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/** Gives every new user the default Persian category tree. */
@Component
class CategorySeeder {

    private final CategoryRepository categories;

    CategorySeeder(CategoryRepository categories) {
        this.categories = categories;
    }

    @EventListener
    void onUserRegistered(UserRegisteredEvent event) {
        seed(event.userId(), CategoryKind.EXPENSE, DefaultCategories.EXPENSE);
        seed(event.userId(), CategoryKind.INCOME, DefaultCategories.INCOME);
    }

    private void seed(long userId, CategoryKind kind, List<Seed> seeds) {
        int order = 0;
        for (Seed seed : seeds) {
            Category parent = categories.save(new Category(userId, null, kind, seed.name(), seed.icon(), seed.systemKey(), order++));
            int childOrder = 0;
            for (Seed child : seed.children()) {
                categories.save(new Category(userId, parent.getId(), kind, child.name(), null, child.systemKey(), childOrder++));
            }
        }
    }
}
