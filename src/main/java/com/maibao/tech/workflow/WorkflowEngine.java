package com.maibao.tech.workflow;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    public NodeContext run(List<Node> nodes, NodeContext ctx) {
        for (Node node : nodes) {
            long start = System.currentTimeMillis();
            try {
                node.execute(ctx);
            } catch (Exception e) {
                log.error("node failed: {}", node.name(), e);
                ctx.set("__failed_node", node.name());
                throw new IllegalStateException("node failed: " + node.name(), e);
            }
            log.debug("node {} done in {} ms", node.name(), System.currentTimeMillis() - start);
        }
        return ctx;
    }
}
