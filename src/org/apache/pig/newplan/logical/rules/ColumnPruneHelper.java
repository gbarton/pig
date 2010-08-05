/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.newplan.logical.rules;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pig.data.DataType;
import org.apache.pig.newplan.Operator;
import org.apache.pig.newplan.OperatorPlan;
import org.apache.pig.newplan.OperatorSubPlan;
import org.apache.pig.newplan.ReverseDependencyOrderWalker;
import org.apache.pig.newplan.logical.expression.LogicalExpression;
import org.apache.pig.newplan.logical.expression.LogicalExpressionPlan;
import org.apache.pig.newplan.logical.expression.ProjectExpression;
import org.apache.pig.newplan.logical.relational.LOCogroup;
import org.apache.pig.newplan.logical.relational.LOCross;
import org.apache.pig.newplan.logical.relational.LODistinct;
import org.apache.pig.newplan.logical.relational.LOFilter;
import org.apache.pig.newplan.logical.relational.LOForEach;
import org.apache.pig.newplan.logical.relational.LOGenerate;
import org.apache.pig.newplan.logical.relational.LOInnerLoad;
import org.apache.pig.newplan.logical.relational.LOJoin;
import org.apache.pig.newplan.logical.relational.LOLimit;
import org.apache.pig.newplan.logical.relational.LOLoad;
import org.apache.pig.newplan.logical.relational.LOSort;
import org.apache.pig.newplan.logical.relational.LOSplit;
import org.apache.pig.newplan.logical.relational.LOSplitOutput;
import org.apache.pig.newplan.logical.relational.LOStore;
import org.apache.pig.newplan.logical.relational.LOStream;
import org.apache.pig.newplan.logical.relational.LOUnion;
import org.apache.pig.newplan.logical.relational.LogicalPlan;
import org.apache.pig.newplan.logical.relational.LogicalRelationalNodesVisitor;
import org.apache.pig.newplan.logical.relational.LogicalRelationalOperator;
import org.apache.pig.newplan.logical.relational.LogicalSchema;
import org.apache.pig.newplan.logical.relational.SchemaNotDefinedException;
import org.apache.pig.newplan.logical.relational.LogicalSchema.LogicalFieldSchema;

/**
 * Helper class used by ColumnMapKeyPrune to figure out what columns can be pruned.
 * It doesn't make any changes to the operator plan
 *
 */
public class ColumnPruneHelper {
    protected static final String INPUTUIDS = "ColumnPrune:InputUids";
    public static final String OUTPUTUIDS = "ColumnPrune:OutputUids";    
    protected static final String REQUIREDCOLS = "ColumnPrune:RequiredColumns";
    
    private OperatorPlan currentPlan;
    private OperatorSubPlan subPlan;

    public ColumnPruneHelper(OperatorPlan currentPlan) {
        this.currentPlan = currentPlan;
    }    
    
    private OperatorSubPlan getSubPlan() throws IOException {
        OperatorSubPlan p = null;
        if (currentPlan instanceof OperatorSubPlan) {
            p = new OperatorSubPlan(((OperatorSubPlan)currentPlan).getBasePlan());
        } else {
            p = new OperatorSubPlan(currentPlan);
        }
        Iterator<Operator> iter = currentPlan.getOperators();
        
        while(iter.hasNext()) {
            Operator op = iter.next();
            if (op instanceof LOForEach) {
                addOperator(op, p);
            }
        }
        
        return p;
    }
    
    private void addOperator(Operator op, OperatorSubPlan subplan) throws IOException {
        if (op == null) {
            return;
        }
        
        subplan.add(op);
        
        List<Operator> ll = currentPlan.getPredecessors(op);
        if (ll == null) {
            return;
        }
        
        for(Operator pred: ll) {
            addOperator(pred, subplan);
        }
    }
    
        
    @SuppressWarnings("unchecked")
    public boolean check() throws IOException {
        List<Operator> sources = currentPlan.getSources();
        // if this rule has run before, just return false
        if (sources.get(0).getAnnotation(INPUTUIDS) != null) {
            return false;
        }
        
        // create sub-plan that ends with foreach
        subPlan = getSubPlan();
        if (subPlan.size() == 0) {
            return false;
        }
        
        ColumnDependencyVisitor v = new ColumnDependencyVisitor(subPlan);
        try {
            v.visit();
        }catch(SchemaNotDefinedException e) {
            // if any operator has an unknown schema, just return false
            return false;
        }
        
        List<Operator> ll = subPlan.getSources();
        boolean found = false;
        for(Operator op: ll) {
            if (op instanceof LOLoad) {
                Set<Long> uids = (Set<Long>)op.getAnnotation(INPUTUIDS);
                LogicalSchema s = ((LOLoad) op).getSchema();
                Set<Integer> required = getColumns(s, uids);
                
                if (required.size() < s.size()) {
                    op.annotate(REQUIREDCOLS, required);              
                    found = true;
                }
            }
        }
        
        return found;
    }

    // get a set of column indexes from a set of uids
    protected Set<Integer> getColumns(LogicalSchema schema, Set<Long> uids) throws IOException {
        if (schema == null) {
            throw new SchemaNotDefinedException("Schema is not defined.");
        }
        
        Set<Integer> cols = new HashSet<Integer>();
        Iterator<Long> iter = uids.iterator();
        while(iter.hasNext()) {
            long uid = iter.next();
            int index = schema.findField(uid);
            if (index == -1) {
                throw new IOException("UID " + uid + " is not found in the schema");
            }
              
            cols.add(index);
        }
          
        return cols;
    }
    
    public OperatorPlan reportChanges() {
        return subPlan;
    }
   
    // Visitor to calculate the input and output uids for each operator
    // It doesn't change the plan, only put calculated info as annotations
    // The input and output uids are not necessarily the top level uids of
    // a schema. They may be the uids of lower level fields of complex fields
    // that have their own schema.
    static private class ColumnDependencyVisitor extends LogicalRelationalNodesVisitor {    	
        
        public ColumnDependencyVisitor(OperatorPlan plan) {
            super(plan, new ReverseDependencyOrderWalker(plan));            
        }
        
        @Override
        public void visit(LOLoad load) throws IOException {
            Set<Long> output = setOutputUids(load);
            
            // for load, input uids are same as output uids
            load.annotate(INPUTUIDS, output);
        }

        @Override
        public void visit(LOFilter filter) throws IOException {
            Set<Long> output = setOutputUids(filter);
            
            // the input uids contains all the output uids and
            // projections in filter conditions
            Set<Long> input = new HashSet<Long>(output);
            
            LogicalExpressionPlan exp = filter.getFilterPlan();
            collectUids(filter, exp, input);
            
            filter.annotate(INPUTUIDS, input);
        }
        
        @Override
        public void visit(LOStore store) throws IOException {
            Set<Long> output = setOutputUids(store);            
            
            if (output.isEmpty()) {
                // to deal with load-store-load-store case
                LogicalSchema s = store.getSchema();
                if (s == null) {
                    throw new SchemaNotDefinedException("Schema for " + store.getName() + " is not defined.");
                }
                                
                for(int i=0; i<s.size(); i++) {
                    output.add(s.getField(i).uid);
                }                                                
            }        
            
            // for store, input uids are same as output uids
            store.annotate(INPUTUIDS, output);
        }
        
        @Override
        public void visit(LOJoin join) throws IOException {
            Set<Long> output = setOutputUids(join);
            
            // the input uids contains all the output uids and
            // projections in join expressions
            Set<Long> input = new HashSet<Long>(output);
            
            Collection<LogicalExpressionPlan> exps = join.getExpressionPlans();
            Iterator<LogicalExpressionPlan> iter = exps.iterator();
            while(iter.hasNext()) {
                LogicalExpressionPlan exp = iter.next();
                collectUids(join, exp, input);
            }
            
            join.annotate(INPUTUIDS, input);
        }
        
        @Override
        public void visit(LOCogroup cg) throws IOException {
            Set<Long> output = setOutputUids(cg);
            
            // the input uids contains all the output uids and
            // projections in join expressions
            Set<Long> input = new HashSet<Long>();
            
            // Add all the uids required for doing cogroup. As in all the
            // keys on which the cogroup is done.
            for( LogicalExpressionPlan plan : cg.getExpressionPlans().values() ) {
                collectUids(cg, plan, input);
            }
            
            // Now check for the case where the output uid is a generated one
            // If that is the case we need to add the uids which generated it in 
            // the input
            Map<Integer,Long> generatedInputUids = cg.getGeneratedInputUids();
            for( Map.Entry<Integer, Long> entry : generatedInputUids.entrySet() ) {
                Long uid = entry.getValue();
                if( output.contains(uid) ) {
                    // Hence we need to all the full schema of the bag
                    LogicalRelationalOperator pred =
                        (LogicalRelationalOperator) cg.getPlan().getPredecessors(cg).get(entry.getKey());
                    input.addAll( getAllUids( pred.getSchema() ) );
                }
            }
            
            cg.annotate(INPUTUIDS, input);
        }
        
        @Override
        public void visit(LOLimit limit) throws IOException {
            Set<Long> output = setOutputUids(limit);
            limit.annotate(INPUTUIDS, output);
        }
        
        @Override
        public void visit(LOStream stream) throws IOException {
            Set<Long> input = new HashSet<Long>();
            
            // Every field is required
            LogicalSchema s = stream.getSchema();
            if (s == null) {
                throw new SchemaNotDefinedException("Schema for " + stream.getName() + " is not defined.");
            }
            
            for(int i=0; i<s.size(); i++) {
                input.add(s.getField(i).uid);
            }                                                
            stream.annotate(INPUTUIDS, input);
        }
        
        @Override
        public void visit(LODistinct distinct) throws IOException {
            Set<Long> input = new HashSet<Long>();
            
            // Every field is required
            LogicalSchema s = distinct.getSchema();
            if (s == null) {
                throw new SchemaNotDefinedException("Schema for " + distinct.getName() + " is not defined.");
            }
            
            for(int i=0; i<s.size(); i++) {
                input.add(s.getField(i).uid);
            }                                                
            distinct.annotate(INPUTUIDS, input);
        }
        
        @Override
        public void visit(LOCross cross) throws IOException {
            Set<Long> output = setOutputUids(cross);
            // Since we do not change the topology of the plan, we keep
            // at least one input for each predecessor.
            List<Operator> preds = plan.getPredecessors(cross);
            for (Operator pred : preds) {
                LogicalSchema schema = ((LogicalRelationalOperator)pred).getSchema();
                Set<Long> uids = getAllUids(schema);
                boolean allPruned = true;
                for (Long uid : uids) {
                    if (output.contains(uid))
                        allPruned = false;
                }
                if (allPruned)
                    output.add(schema.getField(0).uid);
            }
            cross.annotate(INPUTUIDS, output);
        }
        
        @Override
        public void visit(LOUnion union) throws IOException {
            Set<Long> output = setOutputUids(union);
            Set<Long> input = new HashSet<Long>();
            for (long uid : output) {
                input.addAll(union.getInputUids(uid));
            }
            union.annotate(INPUTUIDS, input);
        }
        
        @Override
        public void visit(LOSplit split) throws IOException {
            Set<Long> output = setOutputUids(split);
            split.annotate(INPUTUIDS, output);
        }
        
        @Override
        public void visit(LOSplitOutput splitOutput) throws IOException {
            Set<Long> output = setOutputUids(splitOutput);
            
            // the input uids contains all the output uids and
            // projections in splitOutput conditions
            Set<Long> input = new HashSet<Long>(output);
            
            LogicalExpressionPlan exp = splitOutput.getFilterPlan();
            collectUids(splitOutput, exp, input);
            
            splitOutput.annotate(INPUTUIDS, input);
        }
        
        @Override
        public void visit(LOSort sort) throws IOException {
            Set<Long> output = setOutputUids(sort);
            
            Set<Long> input = new HashSet<Long>(output);
            
            for (LogicalExpressionPlan exp : sort.getSortColPlans()) {
                collectUids(sort, exp, input);
            }
            
            sort.annotate(INPUTUIDS, input);
        }
        
        /*
         * This function returns all uids present in the given schema
         */
        private Set<Long> getAllUids( LogicalSchema schema ) {            
            Set<Long> uids = new HashSet<Long>();
            
            if( schema == null ) {
                return uids;
            }
            
            for( LogicalFieldSchema field : schema.getFields() ) {
                if( ( field.type == DataType.TUPLE || field.type == DataType.BAG )
                        && field.schema != null ) {
                   uids.addAll( getAllUids( field.schema ) );
                }
                uids.add( field.uid );
            }
            return uids;
        }
        
        @Override
        public void visit(LOForEach foreach) throws IOException {
            Set<Long> output = setOutputUids(foreach);
            
            LogicalPlan innerPlan = foreach.getInnerPlan();
            LOGenerate gen = (LOGenerate)innerPlan.getSinks().get(0);
            gen.annotate(OUTPUTUIDS, output);
            
            visit(gen);
            
            foreach.annotate(INPUTUIDS, gen.getAnnotation(INPUTUIDS));
        }

        @Override
        @SuppressWarnings("unchecked")
        public void visit(LOGenerate gen) throws IOException {
             Set<Long> output = (Set<Long>)gen.getAnnotation(OUTPUTUIDS);
             
             Set<Long> input = new HashSet<Long>();
             
             List<LogicalExpressionPlan> ll = gen.getOutputPlans();
             
             Iterator<Long> iter = output.iterator();
             while(iter.hasNext()) {
                 long uid = iter.next();
                 for(int i=0; i<ll.size(); i++) {
                     boolean found = false;
                     LogicalExpressionPlan exp = ll.get(i);
                     LogicalExpression op = (LogicalExpression)exp.getSources().get(0);
                     
                     if (gen.getFlattenFlags()[i] && (op.getFieldSchema().type==DataType.TUPLE ||
                             op.getFieldSchema().type== DataType.BAG)) {
                         // if uid equal to the expression, get all uids of original projections
                         LogicalSchema schema;

                         schema = op.getFieldSchema().schema;
                         for (LogicalSchema.LogicalFieldSchema fs : schema.getFields())
                         {
                             if (fs.uid==uid) {
                                 found = true;
                                 break;
                             }
                         }
                     }
                     else {
                         // No flatten, collect outer uid
                         if (op.getFieldSchema().uid == uid) {                         
                             found = true;
                         }
                     }
                     
                     if (found) {
                         List<Operator> srcs = exp.getSinks();
                         for (Operator src : srcs) {
                             if (src instanceof ProjectExpression) {
                                 List<LOInnerLoad> innerLoads = LOForEach.findReacheableInnerLoadFromBoundaryProject((ProjectExpression)src);
                                 for (LOInnerLoad innerLoad : innerLoads) {
                                     ProjectExpression prj = innerLoad.getProjection();
                                     if (prj.isProjectStar()) {
                                         if (prj.findReferent().getSchema()!=null) {
                                             for (LogicalSchema.LogicalFieldSchema fs : prj.findReferent().getSchema().getFields()) {
                                                 input.add(fs.uid);
                                             }
                                         }
                                     }
                                     else {
                                         if (prj.findReferent().getSchema()!=null) {
                                             LogicalSchema.LogicalFieldSchema fs = prj.findReferent().getSchema().getField(prj.getColNum()); 
                                             input.add(fs.uid);
                                         }
                                     }
                                 }
                             }
                         }
                     }
                 }
             }
              
             // for the flatten bag, we need to make sure at least one field is in the input
             for(int i=0; i<ll.size(); i++) {
                 if (!gen.getFlattenFlags()[i]) {
                     continue;
                 }
                 LogicalExpressionPlan exp = ll.get(i);
                 List<Operator> srcs = exp.getSinks();
                 for (Operator src : srcs) {
                     List<LOInnerLoad> innerLoads = LOForEach.findReacheableInnerLoadFromBoundaryProject((ProjectExpression)src);
                     for (LOInnerLoad innerLoad : innerLoads) {
                         ProjectExpression prj = innerLoad.getProjection();
                         if (prj.isProjectStar()) {
                             if (prj.findReferent().getSchema()!=null) {
                                 for (LogicalSchema.LogicalFieldSchema fs : prj.findReferent().getSchema().getFields()) {
                                     input.add(fs.uid);
                                 }
                             }
                         }
                         else {
                             if (prj.findReferent().getSchema()!=null) {
                                 LogicalSchema.LogicalFieldSchema fs = prj.findReferent().getSchema().getField(prj.getColNum());
                                 input.add(fs.uid);
                             }
                         }
                     }
                 }
             }
             gen.annotate(INPUTUIDS, input);
        }
        
        @Override
        public void visit(LOInnerLoad load) throws IOException {
            Set<Long> output = setOutputUids(load);
            load.annotate(INPUTUIDS, output);
        }
        
        private void collectUids(LogicalRelationalOperator currentOp, LogicalExpressionPlan exp, Set<Long> uids) throws IOException {
            List<Operator> ll = exp.getSinks();
            for(Operator op: ll) {
                if (op instanceof ProjectExpression) {
                    if (!((ProjectExpression)op).isProjectStar()) {
                        long uid = ((ProjectExpression)op).getFieldSchema().uid;
                        uids.add(uid);
                    } else {
                        LogicalRelationalOperator ref = ((ProjectExpression)op).findReferent();
                        LogicalSchema s = ref.getSchema();
                        if (s == null) {
                            throw new SchemaNotDefinedException("Schema not defined for " + ref.getAlias());
                        }
                        for(LogicalFieldSchema f: s.getFields()) {
                            uids.add(f.uid);
                        }
                    }
                }
            }
        }
        
        @SuppressWarnings("unchecked")
        private Set<Long> setOutputUids(LogicalRelationalOperator op) throws IOException {
            
            List<Operator> ll = plan.getSuccessors(op);
            Set<Long> uids = new HashSet<Long>();
            
            LogicalSchema s = op.getSchema();
            if (s == null) {
                throw new SchemaNotDefinedException("Schema for " + op.getName() + " is not defined.");
            }
                            
            if (ll != null) {
                // if this is not sink, the output uids are union of input uids of its successors
                for(Operator succ: ll) {
                    Set<Long> inputUids = (Set<Long>)succ.getAnnotation(INPUTUIDS);
                    if (inputUids != null) {
                        Iterator<Long> iter = inputUids.iterator();
                        while(iter.hasNext()) {
                            long uid = iter.next();
                            
                            if (s.findField(uid) != -1) {
                                uids.add(uid);
                            }
                        }
                    }
                }
            } else {
                // if  it's leaf, set to its schema                
                for(int i=0; i<s.size(); i++) {
                    uids.add(s.getField(i).uid);
                }                                
            } 
            
            op.annotate(OUTPUTUIDS, uids);
            return uids;
        }
    }
}