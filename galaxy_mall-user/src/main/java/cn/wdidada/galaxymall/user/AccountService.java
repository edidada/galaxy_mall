package cn.wdidada.galaxymall.user;

import cn.wdidada.galaxymall.common.api.LoginView;
import cn.wdidada.galaxymall.common.api.SessionView;
import cn.wdidada.galaxymall.common.domain.User;
import cn.wdidada.galaxymall.db.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 演示级账号与会话服务。
 *
 * <p>刻意不做生产级鉴权，原因写在 {@code docs/} 与接口注释里：
 * 口令只存 SHA-256 摘要（明文不落库、不回显），令牌是随机 UUID、保存在进程内存，
 * 服务重启即全部失效；没有刷新令牌、没有设备绑定、没有验证码与风控。
 * 它的定位是让前端商城页与埋点链路有一个真实的“已登录用户”，而不是安全方案。
 *
 * <p>账号来源有两处：
 * <ol>
 *   <li>{@link UserRepository} 里已播种的用户 —— 用 userId 或 nickname 作为登录名，
 *       默认口令统一 {@value #DEFAULT_PASSWORD}（仅本地/UAT 演示）。</li>
 *   <li>{@link #register} —— 前端注册页新建的账号，同样写入 UserRepository，
 *       这样下单链路里的 {@code user-not-found} 校验才能通过。</li>
 * </ol>
 */
public final class AccountService {

    public static final String DEFAULT_PASSWORD = "123456";

    private static final Duration SESSION_TTL = Duration.ofHours(12);

    private final UserRepository userRepository;
    private final Map<String, String> passwordDigests = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AccountService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 播种账号：绑定演示口令。已存在的登录名不覆盖，避免重启后把注册用户踢掉。 */
    public void bindDefaultPassword(String loginName) {
        passwordDigests.putIfAbsent(loginName.toLowerCase(), sha256(DEFAULT_PASSWORD + loginName.toLowerCase()));
    }

    public boolean register(String loginName, String password, String nickname, int level) {
        if (loginName == null || loginName.isBlank() || password == null || password.length() < 6) {
            return false;
        }
        String key = loginName.toLowerCase();
        String userId = "u-" + key;
        if (userRepository.findById(userId).isPresent() || passwordDigests.containsKey(key)) {
            return false;
        }
        userRepository.save(new User(userId, nickname == null || nickname.isBlank() ? loginName : nickname, level));
        passwordDigests.put(key, sha256(password + key));
        return true;
    }

    public Optional<LoginView> login(String loginName, String password) {
        if (loginName == null || password == null) {
            return Optional.empty();
        }
        String key = loginName.toLowerCase();
        String digest = passwordDigests.get(key);
        if (digest == null || !digest.equals(sha256(password + key))) {
            return Optional.empty();
        }
        return userRepository.findById(userIdOf(key)).map(user -> {
            purgeExpired();
            long now = System.currentTimeMillis();
            String token = UUID.randomUUID().toString().replace("-", "");
            sessions.put(token, new Session(user.userId(), user.nickname(), user.level(), now, now + SESSION_TTL.toMillis()));
            return new LoginView(user.userId(), user.nickname(), user.level(), token, now + SESSION_TTL.toMillis());
        });
    }

    public boolean logout(String token) {
        return token != null && sessions.remove(token) != null;
    }

    public Optional<SessionView> currentSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAtEpochMillis() <= System.currentTimeMillis()) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(new SessionView(
                session.userId(), session.nickname(), session.level(),
                session.issuedAtEpochMillis(), session.expiresAtEpochMillis()));
    }

    public int onlineSessions() {
        purgeExpired();
        return sessions.size();
    }

    /** 登录名 -&gt; userId：注册账号有固定前缀，播种账号则反查 nickname / userId 本身。 */
    private String userIdOf(String loginKey) {
        String prefixed = "u-" + loginKey;
        if (userRepository.findById(prefixed).isPresent()) {
            return prefixed;
        }
        return userRepository.findAll().stream()
                .filter(user -> user.nickname().equalsIgnoreCase(loginKey) || user.userId().equalsIgnoreCase(loginKey))
                .map(User::userId)
                .findFirst()
                .orElse(prefixed);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochMillis() <= now);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    public record Session(
            String userId,
            String nickname,
            int level,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis
    ) {
    }
}
