package com.shop.agent.dispatch.domain.auth.repository;

import com.shop.agent.dispatch.domain.auth.entity.SysUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {

  Optional<SysUser> findByUsername(String username);
}
