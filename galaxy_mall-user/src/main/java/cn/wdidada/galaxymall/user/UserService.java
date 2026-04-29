package cn.wdidada.galaxymall.user;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.User;
import cn.wdidada.galaxymall.db.UserRepository;

public final class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public OperationResult<User> findUser(String userId) {
        return userRepository.findById(userId)
                .map(user -> OperationResult.success("user-found", user))
                .orElseGet(() -> OperationResult.failure("user-not-found"));
    }
}
