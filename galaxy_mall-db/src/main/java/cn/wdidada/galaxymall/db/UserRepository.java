package cn.wdidada.galaxymall.db;

import cn.wdidada.galaxymall.common.domain.User;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class UserRepository {

    private final Map<String, User> users = new ConcurrentHashMap<>();

    public void save(User user) {
        users.put(user.userId(), user);
    }

    public Optional<User> findById(String userId) {
        return Optional.ofNullable(users.get(userId));
    }
}
