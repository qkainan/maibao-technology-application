package com.maibao.tech.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class LoopNodeTest {

    @Test
    void should_stop_when_break_condition_matches() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Node body = new Node() {
            @Override
            public String name() {
                return "body";
            }

            @Override
            public void execute(NodeContext ctx) {
                counter.incrementAndGet();
            }
        };
        LoopNode loop = new LoopNode("loop", "round", 100, c -> c.getInt("round", 0) >= 3, List.of(body));
        NodeContext ctx = new NodeContext();
        loop.execute(ctx);

        assertThat(counter.get()).isEqualTo(3);
        assertThat(ctx.getInt("round_executed", 0)).isEqualTo(3);
    }

    @Test
    void should_run_to_max_count_when_condition_never_matches() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Node body = new Node() {
            @Override
            public String name() {
                return "body";
            }

            @Override
            public void execute(NodeContext ctx) {
                counter.incrementAndGet();
            }
        };
        LoopNode loop = new LoopNode("loop", "round", 5, c -> false, List.of(body));
        loop.execute(new NodeContext());

        assertThat(counter.get()).isEqualTo(5);
    }

    @Test
    void should_reject_illegal_max_count() {
        assertThatThrownBy(() -> new LoopNode("loop", "round", 0, c -> true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoopNode("loop", "round", 101, c -> true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
