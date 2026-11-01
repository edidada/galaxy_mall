package cn.wdidada.galaxymall.db;

import cn.wdidada.galaxymall.common.domain.User;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 用户内存实现：单测/单机演示用，重启即回到调用方播种的状态。 */
public final class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> users = new ConcurrentHashMap<>();

    @Override
    public void save(User user) {
        users.put(user.userId(), user);
    }

    @Override
    public Optional<User> findById(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    /** 供账号服务按 nickname / userId 反查登录名，只读快照。 */
    @Override
    public Collection<User> findAll() {
        return new ArrayList<>(users.values());
    }
}
