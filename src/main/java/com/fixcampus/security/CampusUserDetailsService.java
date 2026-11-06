package com.fixcampus.security;

import java.util.Locale;
import com.fixcampus.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampusUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public CampusUserDetailsService(UserRepository users) { this.users = users; }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        String normalized = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        // Match registration rules before comparing input with MySQL's ASCII username column.
        if (!normalized.matches("[a-z0-9_]{4,30}")) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }
        return users.findByUsername(normalized).map(CampusPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("用户名或密码错误"));
    }
}
