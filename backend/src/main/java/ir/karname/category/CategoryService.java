package ir.karname.category;

import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class CategoryService {

    private static final Pattern ICON = Pattern.compile("^[a-z0-9-]{1,32}$");

    private final CategoryRepository categories;
    private final JdbcClient jdbc;

    public CategoryService(CategoryRepository categories, JdbcClient jdbc) {
        this.categories = categories;
        this.jdbc = jdbc;
    }

    public record CategoryView(long id, Long parentId, CategoryKind kind, String name, String icon, String systemKey,
            boolean archived, int sortOrder) {

        public static CategoryView of(Category c) {
            return new CategoryView(c.getId(), c.getParentId(), c.getKind(), c.getName(), c.getIcon(), c.getSystemKey(),
                    c.isArchived(), c.getSortOrder());
        }
    }

    public record CategoryRequest(String name, CategoryKind kind, Long parentId, String icon, Boolean archived) {
    }

    @Transactional(readOnly = true)
    public List<Category> list(long userId) {
        return categories.findByUserIdOrderByKindAscSortOrderAscIdAsc(userId);
    }

    @Transactional(readOnly = true)
    public Category require(long userId, long id) {
        return categories.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("category.notFound"));
    }

    /** A category usable for a transaction of the given kind. */
    @Transactional(readOnly = true)
    public Category requireForKind(long userId, long id, CategoryKind kind) {
        Category category = require(userId, id);
        if (category.getKind() != kind) {
            throw ApiException.badRequest("category.kindMismatch");
        }
        return category;
    }

    @Transactional(readOnly = true)
    public Optional<Category> bySystemKey(long userId, String key) {
        return categories.findByUserIdAndSystemKey(userId, key);
    }

    @Transactional
    public Category create(long userId, CategoryRequest request) {
        if (request.kind() == null) {
            throw ApiException.badRequest("category.kindRequired");
        }
        String name = cleanName(request.name());
        Long parentId = validateParent(userId, request.parentId(), request.kind(), null);
        int order = (int) categories.countByUserId(userId);
        return categories.save(new Category(userId, parentId, request.kind(), name, cleanIcon(request.icon()), null, order));
    }

    @Transactional
    public Category update(long userId, long id, CategoryRequest request) {
        Category category = require(userId, id);
        if (request.name() != null) {
            category.setName(cleanName(request.name()));
        }
        category.setIcon(cleanIcon(request.icon()));
        if (request.parentId() == null || !request.parentId().equals(category.getParentId())) {
            category.setParentId(validateParent(userId, request.parentId(), category.getKind(), category));
        }
        if (request.archived() != null) {
            category.setArchived(request.archived());
        }
        return category;
    }

    /**
     * Deletes a category (and its children). Their transactions move to {@code reassignTo}, or
     * become uncategorized.
     */
    @Transactional
    public void delete(long userId, long id, Long reassignTo) {
        Category category = require(userId, id);
        if (category.getSystemKey() != null) {
            throw ApiException.conflict("category.systemCategory");
        }
        Long target = null;
        if (reassignTo != null) {
            Category to = requireForKind(userId, reassignTo, category.getKind());
            if (to.getId().equals(category.getId()) || category.getId().equals(to.getParentId())) {
                throw ApiException.badRequest("category.invalidReassign");
            }
            target = to.getId();
        }
        jdbc.sql("""
                UPDATE transactions SET category_id = :target, updated_at = now()
                WHERE user_id = :userId AND category_id IN (SELECT id FROM categories WHERE id = :id OR parent_id = :id)
                """)
                .param("target", target)
                .param("userId", userId)
                .param("id", category.getId())
                .update();
        categories.delete(category);
    }

    private Long validateParent(long userId, Long parentId, CategoryKind kind, Category self) {
        if (parentId == null) {
            return null;
        }
        Category parent = require(userId, parentId);
        if (parent.getParentId() != null) {
            throw ApiException.badRequest("category.tooDeep");
        }
        if (parent.getKind() != kind) {
            throw ApiException.badRequest("category.kindMismatch");
        }
        if (self != null) {
            if (parent.getId().equals(self.getId())) {
                throw ApiException.badRequest("category.invalidParent");
            }
            if (!categories.findByUserIdAndParentId(userId, self.getId()).isEmpty()) {
                throw ApiException.badRequest("category.tooDeep");
            }
        }
        return parent.getId();
    }

    private static String cleanName(String name) {
        String clean = PersianText.clean(name);
        if (clean == null || clean.length() > 60) {
            throw ApiException.badRequest("category.invalidName");
        }
        return clean;
    }

    private static String cleanIcon(String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        String trimmed = icon.trim();
        if (!ICON.matcher(trimmed).matches()) {
            throw ApiException.badRequest("category.invalidIcon");
        }
        return trimmed;
    }
}
