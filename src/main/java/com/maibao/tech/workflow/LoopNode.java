package com.maibao.tech.workflow;

import java.util.List;
import java.util.function.Predicate;

/**
 * 循环节点。
 *
 * <p>对应简历：以"扁平循环体 + 起止标记"实现，跨轮状态传递靠输出写回循环变量，
 * 终止条件在轮末求值，另设轮次上限防死循环。</p>
 *
 * <p>关键设计：<br>
 * 1) 循环变量 loopVar 每轮结束写回，下一轮通过它读上一轮结果；<br>
 * 2) breakWhen 在<b>本轮体执行完之后</b>求值，所以能引用本轮产出；<br>
 * 3) maxCount 是兜底：条件永远不满足时也不会无限跑。</p>
 */
public class LoopNode implements Node {

    private final String nodeName;
    private final String loopVar;
    private final int maxCount;
    private final Predicate<NodeContext> breakWhen;
    private final List<Node> body;

    public LoopNode(String nodeName, String loopVar, int maxCount,
                    Predicate<NodeContext> breakWhen, List<Node> body) {
        if (maxCount <= 0 || maxCount > 100) {
            throw new IllegalArgumentException("maxCount must be in (0, 100], got " + maxCount);
        }
        this.nodeName = nodeName;
        this.loopVar = loopVar;
        this.maxCount = maxCount;
        this.breakWhen = breakWhen;
        this.body = body;
    }

    @Override
    public String name() {
        return nodeName;
    }

    @Override
    public void execute(NodeContext ctx) throws Exception {
        int executed = 0;
        for (int round = 1; round <= maxCount; round++) {
            ctx.set(loopVar, round);
            for (Node node : body) {
                node.execute(ctx);
            }
            executed = round;
            if (breakWhen.test(ctx)) {
                break;
            }
        }
        ctx.set(loopVar + "_executed", executed);
    }
}
