package com.maibao.tech.mq;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedTaskRepository extends JpaRepository<FailedTask, Long> {

    long countByTaskId(String taskId);
}
