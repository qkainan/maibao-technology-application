package com.maibao.tech.mq;

import org.springframework.data.jpa.repository.JpaRepository;
import com.maibao.tech.mq.domain.ProcessedTask;

public interface ProcessedTaskRepository extends JpaRepository<ProcessedTask, Long> {

    boolean existsByTaskId(String taskId);
}
