package com.maibao.tech.usage;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.maibao.tech.usage.domain.UserQueryLog;

public interface UserQueryLogRepository extends JpaRepository<UserQueryLog, Long> {

    List<UserQueryLog> findByUserIdOrderByIdDesc(String userId, Pageable pageable);

    List<UserQueryLog> findAllByOrderByIdDesc(Pageable pageable);
}
