package com.fixcampus.repository;

import java.util.Optional;
import java.util.List;
import com.fixcampus.entity.User;
import com.fixcampus.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    Optional<User> findByIdAndRole(Long id, Role role);
    List<User> findAllByRoleOrderByUsernameAsc(Role role);
}
