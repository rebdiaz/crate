/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.expression.scalar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.crate.data.Input;
import io.crate.metadata.CoordinatorTxnCtx;
import io.crate.metadata.FunctionName;
import io.crate.metadata.FunctionProvider;
import io.crate.metadata.NodeContext;
import io.crate.metadata.Scalar;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.functions.BoundSignature;
import io.crate.metadata.functions.Signature;
import io.crate.testing.TestingHelpers;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import io.crate.types.TypeSignature;

/**
 * This test check if the scalar functions fulfill their nullability requirements
 */
public class ScalarNullabilityTest {

    final TransactionContext txnCtx = CoordinatorTxnCtx.systemTransactionContext();
    final NodeContext nodeContext = TestingHelpers.createNodeContext();

    @Test
    public void test_nullability_scalars_return_null_on_null_input() {
        var numberOfFunctionsToTested = 0;
        for (var functionProvider : getFunctionProviders()) {
            var signature = functionProvider.getSignature();
            if (signature.hasFeature(Scalar.Feature.NULLABLE)) {
                var bound = getBoundSignature(signature);
                var function = functionProvider.getFactory().apply(signature, bound);
                var arguments = getArguments(bound);
                if (function instanceof Scalar<?, ?> scalar) {
                    var result = scalar.evaluate(txnCtx, nodeContext, arguments);
                    var name = function.signature().getName();
                    assertThat(result).as("Return value must be null for null arguments: " + name).isNull();
                    numberOfFunctionsToTested++;
                }
            }
        }
        assertThat(numberOfFunctionsToTested).isGreaterThan(0);
    }

    @Test
    public void test_non_nullability_scalars_return_not_null_on_null_input() {
        var numberOfFunctionsToTested = 0;
        for (var functionProvider : getFunctionProviders()) {
            var signature = functionProvider.getSignature();
            if (signature.hasFeature(Scalar.Feature.NON_NULLABLE)) {
                var bound = getBoundSignature(signature);
                var function = functionProvider.getFactory().apply(signature, bound);
                var arguments = getArguments(bound);
                if (function instanceof Scalar<?, ?> scalar) {
                    try {
                        var evaluate = scalar.evaluate(txnCtx, nodeContext, arguments);
                        FunctionName name = function.signature().getName();
                        assertThat(evaluate).as("Return value must not be null for null arguments: " + name).isNotNull();
                    } catch (IllegalArgumentException | AssertionError e) {
                        assertThat(true).isTrue();
                    }
                    numberOfFunctionsToTested++;
                }
            }
        }
        assertThat(numberOfFunctionsToTested).isGreaterThan(0);
    }

    List<FunctionProvider> getFunctionProviders() {
        var functions = nodeContext.functions();
        var functionResolvers = functions.functionResolvers();
        var result = new ArrayList<FunctionProvider>();
        for (var value : functionResolvers.values()) {
            result.addAll(value);
        }
        return result;
    }

    BoundSignature getBoundSignature(Signature signature) {
        var boundTypes = new ArrayList<DataType<?>>();
        for (TypeSignature typeSignature : signature.getArgumentTypes()) {
            boundTypes.add(getDataType(typeSignature));
        }
        var returnType = getDataType(signature.getReturnType());
        return new BoundSignature(boundTypes, returnType);
    }

    Input[] getArguments(BoundSignature boundSignature) {
        var inputArguments = new Input[boundSignature.argTypes().size()];
        for (int i = 0; i < boundSignature.argTypes().size(); i++) {
            inputArguments[i] = (Input<Object>) () -> null;
        }
        return inputArguments;
    }

    DataType<?> getDataType(TypeSignature typeSignature) {
        var baseTypeName = typeSignature.getBaseTypeName();
        if (baseTypeName.length() == 1) {
            // resolve generic types to string
            return DataTypes.STRING;
        }
        return switch (baseTypeName) {
            case "array" -> DataTypes.STRING_ARRAY;
            default -> DataTypes.ofName(typeSignature.getBaseTypeName());
        };
    }

}
