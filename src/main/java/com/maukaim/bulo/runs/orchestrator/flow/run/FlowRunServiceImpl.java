package com.maukaim.bulo.runs.orchestrator.flow.run;

import com.maukaim.bulo.runs.orchestrator.flow.FlowRunService;
import com.maukaim.bulo.runs.orchestrator.flow.FlowViewService;
import com.maukaim.bulo.runs.orchestrator.flow.view.FlowStageId;
import com.maukaim.bulo.runs.orchestrator.flow.view.FlowView;
import com.maukaim.bulo.runs.orchestrator.stage.run.StageRunService;
import com.maukaim.bulo.runs.orchestrator.stage.run.model.StageRunView;
import com.maukaim.bulo.runs.orchestrator.util.CloseableEntityLock;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public class FlowRunServiceImpl implements FlowRunService {

    private final FlowViewService flowViewService;
    private final FlowRunCache flowRunCache;
    private final StageRunService stageRunService;

    public FlowRunServiceImpl(FlowViewService flowViewService, FlowRunCache flowRunCache,
                              StageRunService stageRunService) {
        this.flowViewService = flowViewService;
        this.flowRunCache = flowRunCache;
        this.stageRunService = stageRunService;
    }

    @Override
    public FlowRun startRun(String flowId, Set<FlowStageId> rootStageIds) {
        FlowView flowView = this.getExistingFlow(flowId);

        Set<FlowStageId> flowStagesToRunId;
        if (rootStageIds == null || rootStageIds.isEmpty()) {
            flowStagesToRunId = flowView.getExecutionGraph().getRootsIds();
        } else if (flowView.areRootStages(rootStageIds)) {
            flowStagesToRunId = rootStageIds;
        } else {
            throw new FlowRunStartException(String.format("Flow [%s] does not recognize one of the Stages %s as a root.",
                    flowView.getFlowId(),
                    rootStageIds));
        }

        FlowRun newRunNonPersisted = FlowRunFactory.create(flowView);
        FlowRun newRunPersisted = this.flowRunCache.add(newRunNonPersisted);
        Map<String, StageRunView> stageRunById = this.stageRunService.startRuns(newRunPersisted.getFlowRunId(), flowStagesToRunId);

        return this.computeStageRunViewUnderLock(newRunPersisted.getFlowRunId(), (previous) -> stageRunById);
    }

    private FlowView getExistingFlow(String flowId) {
        Optional<FlowView> optionalFlow = this.flowViewService.getFlow(flowId);
        if (optionalFlow.isEmpty()) {
            throw new FlowRunStartException("No flow existing with id: " + flowId);
        }
        return optionalFlow.get();
    }

    @Override
    public FlowRun getById(String flowRunId) {
        return flowRunCache.getRun(flowRunId);
    }

    @Override
    public FlowRun computeStageRunViewUnderLock(String flowRunId, Function<FlowRun, Map<String, StageRunView>> stageRunViewComputer) {
        try (CloseableEntityLock<FlowRun> lockedEntity = this.flowRunCache.getAndLock(flowRunId)) {
            FlowRun flowRun = lockedEntity.getEntity();
            Map<String, StageRunView> stageRunViewToUpdate = stageRunViewComputer.apply(flowRun);
            FlowRun newFlowRunValue = FlowRunFactory.updateStageRunView(flowRun, stageRunViewToUpdate);
            newFlowRunValue = FlowRunFactory.updateState(newFlowRunValue, resolveStatus(newFlowRunValue));
            return this.flowRunCache.update(newFlowRunValue);
        }
    }

    private FlowRunStatus resolveStatus(FlowRun flowRun) {
        FlowRunStatus actualStatus = flowRun.getFlowRunStatus();
        if (actualStatus.isTerminal()) {
            return actualStatus;
        } else {
            Map<String, StageRunView> stageRunViewByStageId = flowRun.getStageRunsById();
            boolean anyCancelled = false;
            boolean anyFailed = false;
            boolean anyRunning = false;
            boolean anyAcknowledged = false;
            boolean anySuccessful = false;
            for (Map.Entry<String, StageRunView> stageRunViewById : stageRunViewByStageId.entrySet()) {
                switch (stageRunViewById.getValue().getStageRunStatus()) {
                    case RUNNING -> anyRunning = true;
                    case CANCELLED -> anyCancelled = true;
                    case FAILED -> anyFailed = true;
                    case ACKNOWLEDGED -> anyAcknowledged = true;
                    case SUCCESS -> anySuccessful = true;
                }
            }

            if (anyFailed) return FlowRunStatus.FAILED;
            else if (anyCancelled) return FlowRunStatus.CANCELLED;
            else if (anyRunning) return FlowRunStatus.RUNNING;
            else if (flowRun.allRunsAreTerminated()) return FlowRunStatus.SUCCESS;
            else if (FlowRunStatus.NEW.equals(actualStatus) && (anyAcknowledged)) return FlowRunStatus.PENDING_START;
            else if (anySuccessful) return FlowRunStatus.RUNNING;
            else return actualStatus;
        }
    }
}