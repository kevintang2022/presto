/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.analyzer;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.type.MapType;
import com.facebook.presto.spi.PrestoWarning;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.analyzer.AccessControlInfo;
import com.facebook.presto.sql.tree.AliasedRelation;
import com.facebook.presto.sql.tree.Cube;
import com.facebook.presto.sql.tree.DefaultTraversalVisitor;
import com.facebook.presto.sql.tree.DereferenceExpression;
import com.facebook.presto.sql.tree.Except;
import com.facebook.presto.sql.tree.ExistsPredicate;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FieldReference;
import com.facebook.presto.sql.tree.GroupingSets;
import com.facebook.presto.sql.tree.Identifier;
import com.facebook.presto.sql.tree.InPredicate;
import com.facebook.presto.sql.tree.Intersect;
import com.facebook.presto.sql.tree.Join;
import com.facebook.presto.sql.tree.JoinCriteria;
import com.facebook.presto.sql.tree.JoinOn;
import com.facebook.presto.sql.tree.JoinUsing;
import com.facebook.presto.sql.tree.LambdaArgumentDeclaration;
import com.facebook.presto.sql.tree.LambdaExpression;
import com.facebook.presto.sql.tree.Lateral;
import com.facebook.presto.sql.tree.Node;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.QuerySpecification;
import com.facebook.presto.sql.tree.Relation;
import com.facebook.presto.sql.tree.Rollup;
import com.facebook.presto.sql.tree.SampledRelation;
import com.facebook.presto.sql.tree.SubqueryExpression;
import com.facebook.presto.sql.tree.Table;
import com.facebook.presto.sql.tree.TableSubquery;
import com.facebook.presto.sql.tree.Union;
import com.facebook.presto.sql.tree.Unnest;
import com.facebook.presto.sql.tree.Values;
import com.facebook.presto.sql.tree.With;
import com.facebook.presto.sql.tree.WithQuery;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.facebook.presto.spi.StandardWarningCode.UTILIZED_COLUMN_ANALYSIS_FAILED;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.intersection;

/**
 * Finds all utilized columns in the query. Utilized columns are those that would have an "impact" on the query's results.
 * <p>
 * For example, in the query:
 * SELECT nationkey FROM (SELECT * FROM nation WHERE name = 'USA')
 * Even though all the columns in table nation are referenced by the query (in the SELECT * part), only the columns
 * "name" and "nationkey" have an "impact" on the query's results.
 * <p>
 * The high-level algorithm works as follows:
 * 1. Find all fields referenced in all clauses of the outermost SELECT query, and add them to an explore list.
 * 2. For each field reference F in the explore list, find its referenced relation R.
 * 3. If R is a SELECT query:
 * a. Find the SELECT item expression that F references. Add all fields referenced by that expression to the explore list.
 * b. Add all fields referenced by every other clause of the SELECT query to the explore list.
 * 4. Otherwise,
 * a. Add F's referenced field to a referenced fields list.
 * b. For each child of R, find the corresponding child of F, and add it to the explore list.
 * 5. Repeat from step 2 for all fields in the explore list, until all have been resolved to a base table relation.
 * <p>
 * The referenced fields list at the end of this algorithm will contain all the columns referenced by the query, that impact the output.
 * Step 3a is where fields that do not impact the output are pruned.
 */
public class UtilizedColumnsAnalyzer
{
    private static final Logger LOG = Logger.get(UtilizedColumnsAnalyzer.class);

    private final Analysis analysis;

    public static void analyzeForUtilizedColumns(Analysis analysis, Node node, WarningCollector warningCollector)
    {
        UtilizedColumnsAnalyzer analyzer = new UtilizedColumnsAnalyzer(analysis);
        try {
            analyzer.analyze(node);
        }
        catch (Exception e) {
            warningCollector.add(new PrestoWarning(
                    UTILIZED_COLUMN_ANALYSIS_FAILED,
                    "Error in analyzing utilized columns for access control, falling back to checking access on all columns: " + e.getMessage()));
            analysis.getTableColumnReferences().forEach(analysis::addUtilizedTableColumnReferences);
        }
    }

    public UtilizedColumnsAnalyzer(Analysis analysis)
    {
        this.analysis = analysis;
    }

    public void analyze(Node node)
    {
        UtilizedFieldsBuilderVisitor visitor = new UtilizedFieldsBuilderVisitor(analysis);
        ImmutableSet.Builder<Field> utilizedFieldsBuilder = ImmutableSet.builder();
        visitor.process(node, new Context(utilizedFieldsBuilder));

        // Keep only the utilized fields that are actual table columns
        HashMultimap<QualifiedObjectName, String> utilizedTableColumns = HashMultimap.create();
        for (Field field : utilizedFieldsBuilder.build()) {
            if (field.getOriginTable().isPresent() && field.getOriginColumnName().isPresent()) {
                utilizedTableColumns.put(field.getOriginTable().get(), field.getOriginColumnName().get());
            }
        }

        // For each access control, keep only the table columns that impact the final results
        for (Entry<AccessControlInfo, Map<QualifiedObjectName, Set<String>>> entry : analysis.getTableColumnReferences().entrySet()) {
            AccessControlInfo accessControlInfo = entry.getKey();
            Map<QualifiedObjectName, Set<String>> tableColumnsForThisAccessControl = entry.getValue();
            Map<QualifiedObjectName, Set<String>> utilizedTableColumnsForThisAccessControl = new HashMap<>();
            for (QualifiedObjectName table : tableColumnsForThisAccessControl.keySet()) {
                utilizedTableColumnsForThisAccessControl.put(table, intersection(utilizedTableColumns.get(table), tableColumnsForThisAccessControl.get(table)));
            }
            analysis.addUtilizedTableColumnReferences(accessControlInfo, utilizedTableColumnsForThisAccessControl);
        }
    }

    private static class UtilizedFieldsBuilderVisitor
            extends DefaultTraversalVisitor<Void, Context>
    {
        private final Analysis analysis;

        private UtilizedFieldsBuilderVisitor(Analysis analysis)
        {
            this.analysis = analysis;
        }

        @Override
        protected Void visitAliasedRelation(AliasedRelation aliasedRelation, Context context)
        {
            handleRelation(aliasedRelation, context, aliasedRelation.getRelation());
            process(aliasedRelation.getRelation(), context);

            return null;
        }

        @Override
        protected Void visitExcept(Except except, Context context)
        {
            handleRelation(except, context, except.getLeft(), except.getRight());
            process(except.getLeft(), context);
            process(except.getRight(), context);

            return null;
        }

        @Override
        protected Void visitIntersect(Intersect intersect, Context context)
        {
            handleRelation(intersect, context, intersect.getRelations().toArray(new Relation[0]));
            for (Relation relation : intersect.getRelations()) {
                process(relation, context);
            }

            return null;
        }

        @Override
        protected Void visitJoin(Join join, Context context)
        {
            handleRelation(join, context);

            if (join.getCriteria().isPresent()) {
                JoinCriteria joinCriteria = join.getCriteria().get();
                if (joinCriteria instanceof JoinOn) {
                    process(((JoinOn) joinCriteria).getExpression(), context);
                }
                else if (joinCriteria instanceof JoinUsing) {
                    for (Identifier column : ((JoinUsing) joinCriteria).getColumns()) {
                        process(column, context);
                    }
                }
            }

            int numLeftFields = analysis.getScope(join.getLeft()).getRelationType().getAllFieldCount();
            for (FieldId fieldId : context.getFieldIdsToExploreInRelation(join)) {
                if (fieldId.getFieldIndex() < numLeftFields) {
                    context.addFieldIdToExplore(new FieldId(RelationId.of(join.getLeft()), fieldId.getFieldIndex()));
                }
                else {
                    context.addFieldIdToExplore(new FieldId(RelationId.of(join.getRight()), fieldId.getFieldIndex() - numLeftFields));
                }
            }

            // Process right before left because the right relation can reference the left (e.g. in a lateral join or unnest clause)
            process(join.getRight(), context);
            process(join.getLeft(), context);

            return null;
        }

        @Override
        protected Void visitLateral(Lateral lateral, Context context)
        {
            handleRelation(lateral, context, lateral.getQuery().getQueryBody());
            process(lateral.getQuery(), context);

            return null;
        }

        @Override
        protected Void visitQuery(Query query, Context context)
        {
            process(query.getQueryBody(), context);
            if (query.getOrderBy().isPresent()) {
                process(query.getOrderBy().get(), context);
            }
            // With clause must be processed last
            if (query.getWith().isPresent()) {
                process(query.getWith().get(), Context.newPrunableContext(context));
            }

            return null;
        }

        @Override
        protected Void visitQuerySpecification(QuerySpecification querySpec, Context context)
        {
            handleRelation(querySpec, context);

            // Wildcards are unresolved in the QuerySpecification's list of SelectItem, so we use output expressions from analysis instead
            List<Expression> selectItems = analysis.getOutputExpressions(querySpec);
            if (!context.prunable) {
                // Examine all the output expressions
                for (Expression expression : selectItems) {
                    process(expression, context);
                }
            }
            else {
                // Prune (Only examine output expressions that have been referenced)
                for (FieldId fieldId : context.getFieldIdsToExploreInRelation(querySpec)) {
                    process(selectItems.get(fieldId.getFieldIndex()), context);
                }
            }

            Context unprunableContext = Context.newUnprunableContext(context);
            if (querySpec.getWhere().isPresent()) {
                process(querySpec.getWhere().get(), unprunableContext);
            }
            if (querySpec.getGroupBy().isPresent()) {
                process(querySpec.getGroupBy().get(), unprunableContext);
            }
            if (querySpec.getHaving().isPresent()) {
                process(querySpec.getHaving().get(), unprunableContext);
            }
            if (querySpec.getOrderBy().isPresent()) {
                process(querySpec.getOrderBy().get(), context);
            }

            // FROM clause must be processed last, after all the field references from other clauses have been gathered
            if (querySpec.getFrom().isPresent()) {
                process(querySpec.getFrom().get(), Context.newPrunableContext(context));
            }

            return null;
        }

        @Override
        protected Void visitWith(With node, Context context)
        {
            ImmutableList.copyOf(node.getQueries()).reverse().forEach(query -> process(query, context));

            return null;
        }

        @Override
        protected Void visitWithQuery(WithQuery withQuery, Context context)
        {
            context.copyFieldIdsToExploreForWithQuery(withQuery);
            process(withQuery.getQuery(), context);

            return null;
        }

        @Override
        protected Void visitSampledRelation(SampledRelation sampledRelation, Context context)
        {
            handleRelation(sampledRelation, context, sampledRelation.getRelation());
            process(sampledRelation.getSamplePercentage(), context);
            process(sampledRelation.getRelation(), context);

            return null;
        }

        @Override
        protected Void visitTable(Table table, Context context)
        {
            handleRelation(table, context);

            Analysis.NamedQuery namedQuery = analysis.getNamedQuery(table);
            if (namedQuery != null && namedQuery.isFromView()) {
                handleRelation(table, context, namedQuery.getQuery().getQueryBody());
                process(namedQuery.getQuery(), context);
            }
            else {
                handleRelation(table, context);
            }

            return null;
        }

        @Override
        protected Void visitTableSubquery(TableSubquery tableSubquery, Context context)
        {
            handleRelation(tableSubquery, context, tableSubquery.getQuery().getQueryBody());
            process(tableSubquery.getQuery(), context);

            return null;
        }

        @Override
        protected Void visitUnion(Union union, Context context)
        {
            handleRelation(union, context, union.getRelations().toArray(new Relation[0]));
            for (Relation relation : union.getRelations()) {
                process(relation, context);
            }

            return null;
        }

        @Override
        protected Void visitUnnest(Unnest unnest, Context context)
        {
            handleRelation(unnest, context);

            List<Expression> unnestExpressions = unnest.getExpressions();
            List<Expression> expandedExpressions = new ArrayList<>();
            for (Expression expression : unnestExpressions) {
                if (analysis.getType(expression) instanceof MapType) {
                    // Map produces two output columns, so input expression gets added twice
                    // in order for key and value columns to map back to the correct input expression
                    expandedExpressions.add(expression);
                }
                expandedExpressions.add(expression);
            }

            for (FieldId field : context.getFieldIdsToExploreInRelation(unnest)) {
                process(expandedExpressions.get(field.getFieldIndex()), context);
            }

            return null;
        }

        @Override
        protected Void visitValues(Values values, Context context)
        {
            handleRelation(values, context);
            for (Expression row : values.getRows()) {
                process(row, context);
            }

            return null;
        }

        @Override
        protected Void visitExists(ExistsPredicate existsPredicate, Context context)
        {
            // All select items in an EXISTS subquery should be ignored (but table-level permissions will still be checked).
            super.visitExists(existsPredicate, Context.newPrunableContext(context));

            return null;
        }

        @Override
        protected Void visitInPredicate(InPredicate inPredicate, Context context)
        {
            // All select items in an IN subquery should be added. This is required because an IN predicate might appear in the FILTER clause of an aggregate expression.
            super.visitInPredicate(inPredicate, Context.newUnprunableContext(context));

            return null;
        }

        @Override
        protected Void visitSubqueryExpression(SubqueryExpression subqueryExpression, Context context)
        {
            // All select items in a scalar subquery should be added. This is required because a scalar subquery can appear in a projection expression.
            super.visitSubqueryExpression(subqueryExpression, analysis.isScalarSubquery(subqueryExpression) ? Context.newUnprunableContext(context) : context);

            return null;
        }

        // LambdaExpression, Cube, Rollup, and GroupingSets are not visited by the default traversal, so we visit them explicitly to capture any column references

        @Override
        protected Void visitLambdaExpression(LambdaExpression lambdaExpression, Context context)
        {
            for (LambdaArgumentDeclaration arg : lambdaExpression.getArguments()) {
                process(arg, context);
            }
            process(lambdaExpression.getBody(), context);

            return null;
        }

        @Override
        protected Void visitCube(Cube cube, Context context)
        {
            for (Expression expr : cube.getExpressions()) {
                process(expr, context);
            }

            return null;
        }

        @Override
        protected Void visitRollup(Rollup rollup, Context context)
        {
            for (Expression expr : rollup.getExpressions()) {
                process(expr, context);
            }

            return null;
        }

        @Override
        protected Void visitGroupingSets(GroupingSets groupingSets, Context context)
        {
            for (Expression expr : groupingSets.getExpressions()) {
                process(expr, context);
            }

            return null;
        }

        // DereferenceExpression, Identifier, and FieldReference are the only nodes that can be column references

        @Override
        protected Void visitDereferenceExpression(DereferenceExpression dereferenceExpression, Context context)
        {
            handleExpression(dereferenceExpression, context);
            process(dereferenceExpression.getBase(), context);

            return null;
        }

        @Override
        protected Void visitIdentifier(Identifier identifier, Context context)
        {
            handleExpression(identifier, context);

            return null;
        }

        @Override
        protected Void visitFieldReference(FieldReference fieldReference, Context context)
        {
            handleExpression(fieldReference, context);

            return null;
        }

        private void handleRelation(Relation relation, Context context, Relation... children)
        {
            for (FieldId fieldId : context.getFieldIdsToExploreInRelation(relation)) {
                context.addUtilizedField(analysis.getScope(relation).getRelationType().getFieldByIndex(fieldId.getFieldIndex()));
                for (Relation child : children) {
                    context.addFieldIdToExplore(new FieldId(RelationId.of(child), fieldId.getFieldIndex()));
                }
            }
        }

        private void handleExpression(Expression expression, Context context)
        {
            if (analysis.getColumnReferenceFields().containsKey(NodeRef.of(expression))) {
                analysis.getColumnReferenceFields().get(NodeRef.of(expression))
                        .forEach(context::addFieldIdToExplore);
            }
        }
    }

    private static class Context
    {
        // Set of all fields referenced by the query
        ImmutableSet.Builder<Field> utilizedFieldsBuilder;
        // Set of field references to explore, indexed by their referenced relation
        HashMultimap<RelationId, FieldId> fieldsToExplore;
        // Can we prune unreferenced select expressions?
        boolean prunable;

        private static Context newPrunableContext(Context context)
        {
            return new Context(context.utilizedFieldsBuilder, context.fieldsToExplore, true);
        }

        private static Context newUnprunableContext(Context context)
        {
            return new Context(context.utilizedFieldsBuilder, context.fieldsToExplore, false);
        }

        private Context(ImmutableSet.Builder<Field> utilizedFieldsBuilder)
        {
            this(utilizedFieldsBuilder, HashMultimap.create(), false);
        }

        private Context(ImmutableSet.Builder<Field> utilizedFieldsBuilder, HashMultimap<RelationId, FieldId> fieldsToExplore, boolean prunable)
        {
            this.utilizedFieldsBuilder = utilizedFieldsBuilder;
            this.fieldsToExplore = fieldsToExplore;
            this.prunable = prunable;
        }

        private Set<FieldId> getFieldIdsToExploreInRelation(Relation relation)
        {
            return fieldsToExplore.get(RelationId.of(relation));
        }

        private void addUtilizedField(Field field)
        {
            utilizedFieldsBuilder.add(field);
        }

        private void addFieldIdToExplore(FieldId fieldId)
        {
            fieldsToExplore.put(fieldId.getRelationId(), fieldId);
        }

        // Associate the relation from the with clause with the fieldIdsToExplore that we collected for it
        // when processing the main part of the query
        public void copyFieldIdsToExploreForWithQuery(WithQuery withQuery)
        {
            QualifiedName name = QualifiedName.of(withQuery.getName().getValue());
            List<RelationId> relationIds = fieldsToExplore.keySet().stream()
                    .filter(key -> key.getSourceNode() instanceof Table && ((Table) key.getSourceNode()).getName().equals(name))
                    .collect(toImmutableList());
            // if a cte is used more than once, it will be listed multiple times in the fieldIds to explore
            // if multiple ctes have the same name, this will also be captured here. These will fail later if the columns used don't exist in the tables
            for (RelationId relationId : relationIds) {
                fieldsToExplore.putAll(
                        RelationId.of(withQuery.getQuery().getQueryBody()),
                        fieldsToExplore.get(relationId));
            }
        }
    }
}
