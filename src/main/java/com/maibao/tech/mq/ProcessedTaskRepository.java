package com.maibao.tech.mq;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedTaskRepository extends JpaRepository<ProcessedTask, Long> {

    boolean existsByTaskId(String taskId);
}
