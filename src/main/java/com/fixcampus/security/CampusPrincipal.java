package com.fixcampus.security;

import java.util.Collection;
import java.util.List;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Store a small authentication snapshot in the session, never a managed JPA entity. */
public class CampusPrincipal implements UserDetails, CredentialsContainer {
    private final Long id;
    private final String username;
    private final String realName;
    private final Role role;
    private String password;

    public CampusPrincipal(User user) {
        id = user.getId();
        username = user.getUsername();
        realName = user.getRealName();
        role = user.getRole();
        password = user.getPassword();
    }

    public Long getId() { return id; }
    public String getRealName() { return realName; }
    public Role getRole() { return role; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return password; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public void eraseCredentials() { password = null; }
}
