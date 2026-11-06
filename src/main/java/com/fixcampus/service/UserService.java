package com.fixcampus.service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.exception.DuplicateUsernameException;
import com.fixcampus.form.RegisterForm;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.view.WorkerOption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public UserService(UserRepository users, PasswordEncoder passwords, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WorkerOption> listWorkers(CampusPrincipal actor) {
        if (actor == null || actor.getId() == null || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("此操作仅限管理员账号");
        }
        return users.findAllByRoleOrderByUsernameAsc(Role.WORKER).stream()
                .map(worker -> new WorkerOption(worker.getId(), worker.getUsername(), worker.getRealName())).toList();
    }

    @Transactional
    public void register(@NotNull @Valid RegisterForm form) {
        if (users.existsByUsername(form.getUsername())) {
            throw new DuplicateUsernameException();
        }
        var user = new User(form.getUsername(), passwords.encode(form.getPassword()),
                form.getRealName(), form.getPhone(), Role.STUDENT, LocalDateTime.now(clock));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // The UNIQUE index also covers registrations racing after the pre-check.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                    throw new DuplicateUsernameException();
                }
            }
            throw exception;
        }
    }
}
