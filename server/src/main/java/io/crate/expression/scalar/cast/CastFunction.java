/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.expression.scalar.cast;

import io.crate.data.Input;
import io.crate.exceptions.ConversionException;
import io.crate.expression.scalar.ScalarFunctionModule;
import io.crate.expression.symbol.Function;
import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.FunctionIdent;
import io.crate.metadata.FunctionInfo;
import io.crate.metadata.Scalar;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.functions.Signature;
import io.crate.types.DataType;

import javax.annotation.Nullable;
import java.util.function.BiFunction;

import static io.crate.metadata.functions.TypeVariableConstraint.typeVariable;
import static io.crate.types.TypeSignature.parseTypeSignature;

public class CastFunction extends Scalar<Object, Object> {

    public static final String CAST_NAME = "cast";
    public static final String TRY_CAST_NAME = "try_cast";

    public static void register(ScalarFunctionModule module) {
        module.register(
            Signature
                .scalar(
                    CAST_NAME,
                    parseTypeSignature("E"),
                    parseTypeSignature("V"),
                    parseTypeSignature("V"))
                .withTypeVariableConstraints(typeVariable("E"), typeVariable("V")),
            (signature, args) -> new CastFunction(
                new FunctionInfo(new FunctionIdent(CAST_NAME, args), args.get(1)),
                signature,
                (argument, returnType) -> {
                    throw new ConversionException(argument, returnType);
                },
                (argument, returnType) -> {
                    throw new ConversionException(argument, returnType);
                }
            )
        );
        module.register(
            Signature
                .scalar(TRY_CAST_NAME,
                        parseTypeSignature("E"),
                        parseTypeSignature("V"),
                        parseTypeSignature("V"))
                .withTypeVariableConstraints(typeVariable("E"), typeVariable("V")),
            (signature, args) -> new CastFunction(
                new FunctionInfo(new FunctionIdent(TRY_CAST_NAME, args), args.get(1)),
                signature,
                (argument, returnType) -> Literal.NULL,
                (argument, returnType) -> null
            )
        );
    }


    private final DataType<?> returnType;
    private final FunctionInfo info;
    private final Signature signature;
    private final BiFunction<Symbol, DataType<?>, Symbol> onNormalizeException;
    private final BiFunction<Object, DataType<?>, Object> onEvaluateException;

    private CastFunction(FunctionInfo info,
                         Signature signature,
                         BiFunction<Symbol, DataType<?>, Symbol> onNormalizeException,
                         BiFunction<Object, DataType<?>, Object> onEvaluateException) {
        this.info = info;
        this.signature = signature;
        this.returnType = info.returnType();
        this.onNormalizeException = onNormalizeException;
        this.onEvaluateException = onEvaluateException;
    }

    @Override
    public Object evaluate(TransactionContext txnCtx, Input<Object>[] args) {
        Object value = args[0].value();
        try {
            return returnType.value(value);
        } catch (ClassCastException | IllegalArgumentException e) {
            return onEvaluateException.apply(value, returnType);
        }
    }

    @Override
    public FunctionInfo info() {
        return info;
    }

    @Nullable
    @Override
    public Signature signature() {
        return signature;
    }

    @Override
    public Symbol normalizeSymbol(Function symbol, TransactionContext txnCtx) {
        Symbol argument = symbol.arguments().get(0);
        if (argument.valueType().equals(returnType)) {
            return argument;
        }

        if (argument instanceof Input) {
            Object value = ((Input<?>) argument).value();
            try {
                return Literal.ofUnchecked(returnType, returnType.value(value));
            } catch (ClassCastException | IllegalArgumentException e) {
                return onNormalizeException.apply(argument, returnType);
            }
        }
        return symbol;
    }
}