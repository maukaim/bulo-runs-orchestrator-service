package com.maukaim.bulo.runs.orchestrator.flow.view;

import java.util.Set;

public interface ExecutionGraph<KEY> {
    Set<KEY> getAllStageIds();

    Set<KEY> getRootsIds();

    Set<KEY> getChildren(KEY stageId);

    Set<KEY> getAncestors(KEY stageId);
}