/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.metadata.functions;

import org.apache.asterix.metadata.declared.MetadataProvider;
import org.apache.asterix.metadata.entities.Dataset;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.typecomputer.base.IResultTypeComputer;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.om.utils.ConstantExpressionUtil;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import org.apache.hyracks.algebricks.core.algebra.metadata.IMetadataProvider;

public class MetadataBuiltinFunctions {

    static {
        addMetadataBuiltinFunctions();
        BuiltinFunctions.addUnnestFun(BuiltinFunctions.DATASET, false);
        BuiltinFunctions.addDatasetFunction(BuiltinFunctions.DATASET);
        BuiltinFunctions.addUnnestFun(BuiltinFunctions.FEED_COLLECT, false);
        BuiltinFunctions.addDatasetFunction(BuiltinFunctions.FEED_COLLECT);
        BuiltinFunctions.addUnnestFun(BuiltinFunctions.FEED_INTERCEPT, false);
        BuiltinFunctions.addDatasetFunction(BuiltinFunctions.FEED_INTERCEPT);
    }

    public static void addMetadataBuiltinFunctions() {

        BuiltinFunctions.addFunction(BuiltinFunctions.DATASET, new IResultTypeComputer() {

            @Override
            public IAType computeType(ILogicalExpression expression, IVariableTypeEnvironment env,
                    IMetadataProvider<?, ?> mp) throws AlgebricksException {
                AbstractFunctionCallExpression f = (AbstractFunctionCallExpression) expression;
                if (f.getArguments().size() != 1) {
                    throw new AlgebricksException("dataset arity is 1, not " + f.getArguments().size());
                }
                ILogicalExpression a1 = f.getArguments().get(0).getValue();
                IAType t1 = (IAType) env.getType(a1);
                if (t1.getTypeTag() == ATypeTag.ANY) {
                    return BuiltinType.ANY;
                }
                if (t1.getTypeTag() != ATypeTag.STRING) {
                    throw new AlgebricksException("Illegal type " + t1 + " for dataset() argument.");
                }
                String datasetArg = ConstantExpressionUtil.getStringConstant(a1);
                if (datasetArg == null) {
                    return BuiltinType.ANY;
                }
                MetadataProvider metadata = (MetadataProvider) mp;
                Pair<String, String> datasetInfo = getDatasetInfo(metadata, datasetArg);
                String dataverseName = datasetInfo.first;
                String datasetName = datasetInfo.second;
                if (dataverseName == null) {
                    throw new AlgebricksException("Unspecified dataverse!");
                }
                Dataset dataset = metadata.findDataset(dataverseName, datasetName);
                if (dataset == null) {
                    throw new AlgebricksException(
                            "Could not find dataset " + datasetName + " in dataverse " + dataverseName);
                }
                String tn = dataset.getItemTypeName();
                IAType t2 = metadata.findType(dataset.getItemTypeDataverseName(), tn);
                if (t2 == null) {
                    throw new AlgebricksException("No type for dataset " + datasetName);
                }
                return t2;
            }
        }, true);

        BuiltinFunctions.addPrivateFunction(BuiltinFunctions.FEED_COLLECT, new IResultTypeComputer() {

            @Override
            public IAType computeType(ILogicalExpression expression, IVariableTypeEnvironment env,
                    IMetadataProvider<?, ?> mp) throws AlgebricksException {
                AbstractFunctionCallExpression f = (AbstractFunctionCallExpression) expression;
                if (f.getArguments().size() != BuiltinFunctions.FEED_COLLECT.getArity()) {
                    throw new AlgebricksException("Incorrect number of arguments -> arity is "
                            + BuiltinFunctions.FEED_COLLECT.getArity() + ", not " + f.getArguments().size());
                }
                ILogicalExpression a1 = f.getArguments().get(5).getValue();
                IAType t1 = (IAType) env.getType(a1);
                if (t1.getTypeTag() == ATypeTag.ANY) {
                    return BuiltinType.ANY;
                }
                if (t1.getTypeTag() != ATypeTag.STRING) {
                    throw new AlgebricksException("Illegal type " + t1 + " for feed-ingest argument.");
                }
                String typeArg = ConstantExpressionUtil.getStringConstant(a1);
                if (typeArg == null) {
                    return BuiltinType.ANY;
                }
                MetadataProvider metadata = (MetadataProvider) mp;
                Pair<String, String> argInfo = getDatasetInfo(metadata, typeArg);
                String dataverseName = argInfo.first;
                String typeName = argInfo.second;
                if (dataverseName == null) {
                    throw new AlgebricksException("Unspecified dataverse!");
                }
                IAType t2 = metadata.findType(dataverseName, typeName);
                if (t2 == null) {
                    throw new AlgebricksException("Unknown type  " + typeName);
                }
                return t2;
            }
        }, true);

        BuiltinFunctions.addFunction(BuiltinFunctions.FEED_INTERCEPT, new IResultTypeComputer() {

            @Override
            public IAType computeType(ILogicalExpression expression, IVariableTypeEnvironment env,
                    IMetadataProvider<?, ?> mp) throws AlgebricksException {
                AbstractFunctionCallExpression f = (AbstractFunctionCallExpression) expression;
                if (f.getArguments().size() != 1) {
                    throw new AlgebricksException("dataset arity is 1, not " + f.getArguments().size());
                }
                ILogicalExpression a1 = f.getArguments().get(0).getValue();
                IAType t1 = (IAType) env.getType(a1);
                if (t1.getTypeTag() == ATypeTag.ANY) {
                    return BuiltinType.ANY;
                }
                if (t1.getTypeTag() != ATypeTag.STRING) {
                    throw new AlgebricksException("Illegal type " + t1 + " for dataset() argument.");
                }
                String datasetArg = ConstantExpressionUtil.getStringConstant(a1);
                if (datasetArg == null) {
                    return BuiltinType.ANY;
                }
                MetadataProvider metadata = (MetadataProvider) mp;
                Pair<String, String> datasetInfo = getDatasetInfo(metadata, datasetArg);
                String dataverseName = datasetInfo.first;
                String datasetName = datasetInfo.second;
                if (dataverseName == null) {
                    throw new AlgebricksException("Unspecified dataverse!");
                }
                Dataset dataset = metadata.findDataset(dataverseName, datasetName);
                if (dataset == null) {
                    throw new AlgebricksException(
                            "Could not find dataset " + datasetName + " in dataverse " + dataverseName);
                }
                String tn = dataset.getItemTypeName();
                IAType t2 = metadata.findType(dataset.getItemTypeDataverseName(), tn);
                if (t2 == null) {
                    throw new AlgebricksException("No type for dataset " + datasetName);
                }
                return t2;
            }
        }, true);
    }

    private static Pair<String, String> getDatasetInfo(MetadataProvider metadata, String datasetArg) {
        String[] nameComponents = datasetArg.split("\\.");
        String first;
        String second;
        if (nameComponents.length == 1) {
            first = metadata.getDefaultDataverse() == null ? null : metadata.getDefaultDataverse().getDataverseName();
            second = nameComponents[0];
        } else {
            first = nameComponents[0];
            second = nameComponents[1];
        }
        return new Pair<String, String>(first, second);
    }
}
