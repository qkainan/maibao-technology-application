package com.maibao.tech.mq;

import org.springframework.data.jpa.repository.JpaRepository;
import com.maibao.tech.mq.domain.FailedTask;

public interface FailedTaskRepository extends JpaRepository<FailedTask, Long> {

    long countByTaskId(String taskId);
}
