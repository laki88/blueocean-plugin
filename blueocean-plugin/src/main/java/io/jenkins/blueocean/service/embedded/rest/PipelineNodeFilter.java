package io.jenkins.blueocean.service.embedded.rest;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import io.jenkins.blueocean.rest.model.BluePipelineNode;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.StageAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filters {@link FlowGraphTable} to BlueOcean specific model representing DAG like graph objects
 *
 * @author Vivek Pandey
 */
public class PipelineNodeFilter {

    private final List<FlowGraphTable.Row> rows;
    private final WorkflowRun run;
    private FlowNode lastNode;

    List<BluePipelineNode> stages = new ArrayList<>();


    public PipelineNodeFilter(WorkflowRun run) {
        this.run = run;
        FlowGraphTable nodeGraphTable = new FlowGraphTable(run.getExecution());
        nodeGraphTable.build();

        this.rows = nodeGraphTable.getRows();
    }

    public List<BluePipelineNode> getPipelineNodes(){
        filter();
        if(nodeMap.isEmpty()){
            return Collections.emptyList();
        }
        List<BluePipelineNode> stages = new ArrayList<>();
        for(FlowNode node: nodeMap.keySet()){
            if(errorNodes.get(node) != null){
                ErrorAction[] errorActions = Iterables.toArray(errorNodes.get(node), ErrorAction.class);
                stages.add(new PipelineNodeImpl(run, node, nodeMap.get(node), errorActions));
            }else{
                stages.add(new PipelineNodeImpl(run, node, nodeMap.get(node)));
            }

        }
        return stages;
    }

    public final Predicate<FlowNode> acceptable = new Predicate<FlowNode>() {
        @Override
        public boolean apply(@Nullable FlowNode input) {
            return isStage.apply(input) || isParallel.apply(input);
        }
    };

    public static final Predicate<FlowNode> isStage = new Predicate<FlowNode>() {
        @Override
        public boolean apply(@Nullable FlowNode input) {
            return input !=null && input.getAction(StageAction.class) != null;
        }
    };

    public static final  Predicate<FlowNode> isParallel = new Predicate<FlowNode>() {
        @Override
        public boolean apply(@Nullable FlowNode input) {
            return input !=null && input.getAction(LabelAction.class) != null &&
                input.getAction(ThreadNameAction.class) != null;
        }
    };

    private final Map<FlowNode, List<FlowNode>> nodeMap = new LinkedHashMap<>();
    private final Map<FlowNode, List<ErrorAction>> errorNodes = new HashMap<>();
    /**
     * Identifies Pipeline nodes and parallel branches, identifies the edges connecting them
     *
     */
    private void filter(){
        FlowNode previous=null;
        for(int i=0; i< rows.size(); i++) {
            FlowGraphTable.Row row = rows.get(i);
            FlowNode flowNode = row.getNode();

            ErrorAction action = flowNode.getError();
            if(previous != null && action != null){
                putErrorAction(previous,action);
            }
            if (acceptable.apply(flowNode)) {
                getOrCreate(flowNode);
                if(isStage.apply(flowNode)) {
                    if(previous == null){
                        previous = flowNode;
                        if(action != null){
                            putErrorAction(previous,action);
                        }
                    }else {
                        nodeMap.get(previous).add(flowNode);
                        previous = flowNode;
                    }
                }else if(isParallel.apply(flowNode)){
                    List<FlowNode> parallels = new ArrayList<>();
                    parallels.add(flowNode);

                    //fast forward till you find another stage
                    FlowNode nextStage=null;
                    FlowNode prevParallelNode=flowNode;
                    for(int j=i+1; j<rows.size();j++){
                        FlowGraphTable.Row r = rows.get(j);
                        FlowNode n = r.getNode();
                        if(n.getError() != null){
                            putErrorAction(prevParallelNode,n.getError());
                            if(previous != null) {
                                putErrorAction(previous, n.getError());
                            }
                        }
                        if(isParallel.apply(n)){
                            prevParallelNode = n;
                            parallels.add(n);
                        }else if(isStage.apply(n)){
                            nextStage = n;
                            i=j;
                            break;
                        }
                    }

                    for(FlowNode f: parallels){
                        List<FlowNode> cn = getOrCreate(f);
                        if(nextStage != null) {
                            cn.add(nextStage);
                        }
                        if(previous != null) {
                            getOrCreate(previous).add(f);
                        }
                    }

                    if(nextStage != null){
                        if(nodeMap.get(nextStage) == null){
                            nodeMap.put(nextStage, new ArrayList<FlowNode>());
                        }
                    }

                    previous = nextStage;
                }
            }
        }
    }

    private List<FlowNode> getOrCreate(@Nonnull FlowNode p){
        List<FlowNode> nodes = nodeMap.get(p);
        if(nodes == null){
            nodes = new ArrayList<>();
            nodeMap.put(p, nodes);
        }
        return nodes;
    }


    private void putErrorAction(@Nonnull FlowNode node, @Nonnull ErrorAction action){
        List<ErrorAction> actions = errorNodes.get(node);
        if(actions == null){
            actions = new ArrayList<>();
            errorNodes.put(node, actions);
        }
        actions.add(action);
    }
}