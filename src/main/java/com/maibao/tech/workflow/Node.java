package com.maibao.tech.workflow;

public interface Node {

    String name();

    void execute(NodeContext ctx) throws Exception;
}
