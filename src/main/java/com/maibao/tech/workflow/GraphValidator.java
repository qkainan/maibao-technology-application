package com.maibao.tech.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 图校验：三色标记 DFS 找环，并且只裁剪"两端都在环上"的边。
 *
 * <p>为什么不用简单 visited：visited 只能区分"走过 / 没走过"，
 * DAG 里的菱形（共享下游）会被误判成环。只有"灰色（在当前递归栈上）"才是环的判据。</p>
 */
@Component
public class GraphValidator {

    private static final int WHITE = 0;
    private static final int GRAY = 1;
    private static final int BLACK = 2;

    /** @return 处于环上的边，格式 "from->to" */
    public List<String> findCycleEdges(Map<String, List<String>> adj) {
        Map<String, Integer> color = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        List<String[]> cyclePairs = new ArrayList<>();

        for (String start : adj.keySet()) {
            if (color.getOrDefault(start, WHITE) == WHITE) {
                dfs(start, adj, color, parent, cyclePairs);
            }
        }
        // 只保留"两端都在环上"的边
        List<String> result = new ArrayList<>();
        for (String[] pair : cyclePairs) {
            String from = pair[0];
            String to = pair[1];
            if (onCycle(from, to, parent, color)) {
                result.add(from + "->" + to);
            }
        }
        return result;
    }

    private void dfs(String node, Map<String, List<String>> adj, Map<String, Integer> color,
                     Map<String, String> parent, List<String[]> cycles) {
        color.put(node, GRAY);
        for (String next : adj.getOrDefault(node, List.of())) {
            int c = color.getOrDefault(next, WHITE);
            if (c == GRAY) {
                cycles.add(new String[]{node, next});
            } else if (c == WHITE) {
                parent.put(next, node);
                dfs(next, adj, color, parent, cycles);
            }
        }
        color.put(node, BLACK);
    }

    private boolean onCycle(String from, String to, Map<String, String> parent, Map<String, Integer> color) {
        Set<String> path = new HashSet<>();
        String cur = from;
        while (cur != null) {
            path.add(cur);
            if (cur.equals(to)) {
                return true;
            }
            cur = parent.get(cur);
        }
        return path.contains(to);
    }
}
