package com.fixcampus.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoAccountService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public DemoAccountService(UserRepository users, PasswordEncoder passwords, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional
    public void initialize(Map<Role, String> suppliedPasswords) {
        record Account(String username, String realName, Role role) { }
        var accounts = List.of(new Account("admin01", "演示管理员", Role.ADMIN),
                new Account("worker01", "演示维修员", Role.WORKER),
                new Account("student01", "演示学生", Role.STUDENT));
        var pending = new ArrayList<Account>();
        var distinctPasswords = new HashSet<String>();
        for (var account : accounts) {
            var existing = users.findByUsername(account.username());
            if (existing.isPresent()) {
                if (existing.get().getRole() != account.role()) {
                    throw new IllegalStateException("演示账号角色冲突：" + account.username() + "；初始化已停止，未覆盖原账号。");
                }
                continue;
            }
            String password = suppliedPasswords.get(account.role());
            int length = password == null ? 0 : password.codePointCount(0, password.length());
            if (length < 8 || length > 64 || !distinctPasswords.add(password)) {
                throw new IllegalStateException("首次创建演示账号需要各自独立的 8～64 字符密码，请检查本地演示配置。");
            }
            pending.add(account);
        }
        // Validate all accounts first; the transaction also rolls back any later write failure.
        for (var account : pending) {
            users.save(new User(account.username(), passwords.encode(suppliedPasswords.get(account.role())),
                    account.realName(), null, account.role(), LocalDateTime.now(clock)));
        }
        users.flush();
    }
}
